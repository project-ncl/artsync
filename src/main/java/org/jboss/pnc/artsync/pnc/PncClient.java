package org.jboss.pnc.artsync.pnc;

import io.quarkus.virtual.threads.VirtualThreads;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import lombok.Getter;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.pnc.artsync.concurrency.ConstrainedExecutor;
import org.jboss.pnc.artsync.pnc.Result.Error.ServerError.*;
import org.jboss.pnc.artsync.pnc.Result.Error.ServerError.UnknownError;
import org.jboss.pnc.artsync.pnc.Result.Success;
import org.jboss.pnc.dto.Artifact;
import org.jboss.pnc.dto.Build;
import org.jboss.pnc.dto.response.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static org.jboss.pnc.artsync.pnc.Result.Error.*;

@ApplicationScoped
public class PncClient {

    private static final Logger LOG = LoggerFactory.getLogger(PncClient.class);

    private final PncRestClient restClient;

    private final PncApplicationConfig config;

    @Getter
    private final ConstrainedExecutor executor;

    public PncClient(@RestClient PncRestClient restClient,
                     PncApplicationConfig config,
                     @VirtualThreads ExecutorService delegate,
                     ScheduledExecutorService scheduler) {
        this.restClient = restClient;
        this.config = config;
        this.executor = new ConstrainedExecutor(delegate, scheduler, config, (ign) -> false, null, null);
    }

    public CompletableFuture<Result<Page<Build>>> getBuilds(ZonedDateTime since, int pageIndex) {
        return executor.supplyAsync(1, () -> {
            LOG.trace("PNC-builds: Page {}, Thread: virt: {}, name: {}", pageIndex, Thread.currentThread().isVirtual(), Thread.currentThread().getName());

            String query = "temporaryBuild==false;status==SUCCESS";
            if (since != null) {
                query = query + ";submitTime>" + since.format(DateTimeFormatter.ISO_DATE_TIME);
            }
            Page<Build> builds = restClient.getBuilds(pageIndex, query);
            LOG.trace("PNC-builds-returned: Page {}, Thread: virt: {}, name: {}", pageIndex, Thread.currentThread().isVirtual(), Thread.currentThread().getName());
            return builds;
        }).handle(this::restHandler);
    }

    private <T> Result<T> restHandler(T result, Throwable error) {
        if (result != null) {
            LOG.trace("PNC-handler: Page {}, Thread: virt: {}, name: {}", ((Page<Build>) result).getPageIndex(), Thread.currentThread().isVirtual(), Thread.currentThread().getName());
            return new Success<>(result);
        } else {
            return switch (error) {
                //TODO moah categorization
                case CompletionException e ->
                    switch (e.getCause()) {
                        // server connection established
                        case WebApplicationException wap -> new UnknownError(wap.getResponse(), wap.getMessage());
                        // internal rest client error
                        case ProcessingException proc -> switch (proc.getCause()) {
                            case SSLException ssle -> new ClientError.SSLError(ssle.getMessage());
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

    CompletableFuture<Result<Page<Artifact>>> getBuiltArtifacts(String buildId, int pageIndex) {
        return executor.supplyAsync(1, () -> {
            LOG.trace("PNC-built-arts: Page {}, Thread: virt: {}, name: {}", pageIndex, Thread.currentThread().isVirtual(), Thread.currentThread().getName());
            Page<Artifact> builds = restClient.getBuiltArtifacts(buildId, pageIndex, "artifactQuality!=DELETED");
            LOG.trace("PNC-built-arts returned: Page {}, Thread: virt: {}, name: {}", pageIndex, Thread.currentThread().isVirtual(), Thread.currentThread().getName());
            return builds;
        }).handle(this::restHandler);
    }

    CompletableFuture<Result<Page<Artifact>>> getDependencies(String buildId, int pageIndex) {
        return executor.supplyAsync(1, () -> {
            LOG.trace("PNC-dep-arts: Page {}, Thread: virt: {}, name: {}", pageIndex, Thread.currentThread().isVirtual(), Thread.currentThread().getName());
            Page<Artifact> builds = restClient.getDependenciesPage(buildId, pageIndex, "artifactQuality!=DELETED");
            LOG.trace("PNC-dep-arts returned: Page {}, Thread: virt: {}, name: {}", pageIndex, Thread.currentThread().isVirtual(), Thread.currentThread().getName());
            return builds;
        }).handle(this::restHandler);
    }
}
