package org.jboss.pnc.artsync.indy;

import io.quarkus.virtual.threads.VirtualThreads;
import io.vertx.core.Vertx;
import io.vertx.core.file.FileSystem;
import io.vertx.core.file.OpenOptions;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.codec.BodyCodec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.commonjava.indy.folo.dto.TrackedContentDTO;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.pnc.artsync.concurrency.ConstrainedExecutor;
import org.jboss.pnc.artsync.pnc.Result;
import org.jboss.pnc.artsync.pnc.Result.Error.ServerError;
import org.jboss.pnc.artsync.pnc.Result.Error.UncaughtException;

import javax.net.ssl.SSLException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static org.jboss.pnc.artsync.pnc.Result.Error.ClientError.*;

@ApplicationScoped
@Slf4j
public class IndyClient {

    private final IndyRestClient client;

    private final WebClient webClient;

    private final FileSystem fs;

    private final IndyApplicationConfig config;

    private final ConstrainedExecutor executor;

    public IndyClient(@RestClient IndyRestClient client,
                      Vertx vertx,
                      IndyApplicationConfig config,
                      @VirtualThreads ExecutorService delegate,
                      ScheduledExecutorService scheduler,
                      WebClientOptions httpConfig) {
        this.client = client;
        this.webClient = WebClient.create(vertx, httpConfig);
        this.fs = vertx.fileSystem();
        this.config = config;
        // TODO implement retryOn
        this.executor = new ConstrainedExecutor(delegate, scheduler, config, (ign) -> false, null, this::retryOn);
    }

    private boolean retryOn(Object response) {
        if (response instanceof Result.Error err) {
            log.warn("Error from indy: " + err.getClass().getSimpleName());
            return switch (err) {
                // RETRY
                case ServerError.SystemError se -> true;
                case ClientTimeout ct -> true;
                case ServerUnreachable su -> true;
                case ServerError.UnknownError(Response r, String desc) -> switch (Integer.valueOf(r.getStatus())) {
                    case Integer i when i >= 500 -> true;
                    default -> false;
                };

                // DO NOT RETRY
                case SSLError ssl -> false;
                case AuthorizationError ae -> false;
                case NotFound nf -> false;
                case UncaughtException ue -> false;
            };
        }
        return false;
    }

    public CompletableFuture<Result<TrackedContentDTO>> getTrackingReport(String buildId) {
        return executor.supplyAsync(() -> client.getTrackingReport("build-" + buildId))
            .handle(this::restResponseHandler);
    }

    public CompletableFuture<Result<String>> downloadFile(String uri, String filePath, boolean overrideIndyUrl) {
        // FIXME Error handling + maybe record response (maybe in the same handler)
        URI uriuri = URI.create(uri);

        OpenOptions brandNew = new OpenOptions().setCreate(true).setTruncateExisting(true);

        return executor.supplyAsync(() -> fs.open(filePath, brandNew).toCompletionStage()
            .thenCompose(openFile ->
                webClient.get(getPort(uriuri),
                        overrideIndyUrl ? config.indyURI().getHost() : uriuri.getHost(),
                        uriuri.getRawPath())
                    .as(BodyCodec.pipe(openFile))
                    .ssl(uriuri.getScheme().equals("https"))
                    .send().toCompletionStage())
            .handle((r,e) -> vertxResponseHandler(r, uri, e))
            .thenApply(response -> convertResult(uri, response))
            .toCompletableFuture().join()
        );
    }

    private <T, F> Result<T> convertResult(T uri, Result<F> response) {
        return switch (response) {
            case Result.Success<F> ign -> new Success<>(uri);
            case Result.Error err -> err;
        };
    }

    private int getPort(URI uri) {
        return switch (Integer.valueOf(uri.getPort())) {
            case Integer i when i.equals(-1) && uri.getScheme().equals("https") -> 443;
            case Integer i when i.equals(-1) && uri.getScheme().equals("http") -> 80;
            default -> uri.getPort();
        };
    }

    private <T> Result<T> restResponseHandler(T response, Throwable error) {
        if (response != null) {
            return new Success<>(response);
        } else {
            return handleError(error);
        }
    }

    private <T> Result<T> vertxResponseHandler(HttpResponse<T> response, String initialUri, Throwable error) {
        if (response != null) {
            return switch (Integer.valueOf(response.statusCode())) {
                case Integer i when i >= 500 -> new ServerError.SystemError("Err " + i + " body: " + response.bodyAsString());
                case Integer i when i.equals(404) -> new NotFound(initialUri);
                case Integer i when i.equals(409) -> new AuthorizationError();
                case Integer i when i >= 400 -> new ServerError.UnknownError(Response.status(i).entity(response.body()).build(), "Unmatched Indy-Download Status");
                default -> new Success<>(response.body());
            };
        } else {
            return handleError(error);
        }
    }

    private static <T> Result<T> handleError(Throwable error) {
        return switch (error) {
            //TODO moah categorization
            case CompletionException e -> switch (e.getCause()) {
                // server connection established
                case WebApplicationException wap -> new ServerError.UnknownError(wap.getResponse(), wap.getMessage());
                // internal rest client error
                case ProcessingException proc -> switch (proc.getCause()) {
                    case SSLException ssle -> new SSLError(ssle.getMessage());
                    case null -> new UncaughtException(proc);
                    default -> new UncaughtException(proc.getCause());
                };
                case null -> new UncaughtException(e);
                default -> new UncaughtException(e.getCause());
            };
            case null -> throw new UnsupportedOperationException();
            default -> new UncaughtException(error);
        };
    }
}
