package org.jboss.pnc.artsync.indy;

import io.vertx.core.Vertx;
import io.vertx.core.file.FileSystem;
import jakarta.enterprise.context.ApplicationScoped;
import org.commonjava.indy.folo.dto.TrackedContentDTO;
import org.commonjava.indy.folo.dto.TrackedContentEntryDTO;
import org.jboss.pnc.artsync.config.ArtifactConfig;
import org.jboss.pnc.artsync.config.ArtsyncConfig;
import org.jboss.pnc.artsync.model.Asset;
import org.jboss.pnc.artsync.model.NpmNVAssets;
import org.jboss.pnc.artsync.model.VersionAssets;
import org.jboss.pnc.artsync.pnc.Result;
import org.jboss.pnc.artsync.pnc.Result.Success;
import org.jboss.pnc.artsync.pnc.ResultAgg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

@ApplicationScoped
public class IndyService {

    private final IndyClient client;

    private final ArtifactConfig config;

    private final FileSystem fs;

    private static final Logger LOG = LoggerFactory.getLogger(IndyService.class);

    public IndyService(IndyClient client,
                       ArtsyncConfig config,
                       Vertx vertx) {
        this.client = client;
        this.config = config.artifactConfigs();
        this.fs = vertx.fileSystem();
    }

    public ResultAgg<TrackedContentEntryDTO> getTrackingReport(String buildId) {
        Result<TrackedContentDTO> result = client.getTrackingReport(buildId).join();

        return switch (result) {
            case Success<TrackedContentDTO>(var report) -> {
                var downloads = report.getDownloads() != null ? report.getDownloads() : new HashSet<TrackedContentEntryDTO>();
                var uploads = report.getUploads() != null ? report.getUploads() : new HashSet<TrackedContentEntryDTO>();

                downloads.removeIf(art -> config.uploadFilter().denies(art) || config.sourceFilter().denies(art) || config.allowedTypes().denies(art));
                uploads.removeIf(art -> config.uploadFilter().denies(art) || config.sourceFilter().denies(art) || config.allowedTypes().denies(art));

                var artList = new ArrayList<>(uploads);
                artList.addAll(downloads);

                yield new ResultAgg<>(artList, List.of());
            }
            case Result.Error error -> new ResultAgg<>(List.of(), List.of(error));
        };
    }

    /**
     * Map of <Indy URI, Destination Path(with Filename)
     *
     * @param uriPathMap uri file
     * @return paths to files
     */
    public CompletableFuture<ResultAgg<File>> downloadByPath(Map<String, FileSize> uriPathMap, boolean overrideIndyUrl) {
        ResultAgg<File> agg = new ResultAgg<>(new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>());

        validFilenames(uriPathMap, agg);

        if (agg.hasErrors()) {
            return CompletableFuture.completedFuture(agg);
        }

        List<CompletableFuture<Result<File>>> futures = new ArrayList<>();

        for (var entry : uriPathMap.entrySet()) {
            futures.add(
                fs.mkdirs(entry.getValue().file().getParent()).toCompletionStage()
                    .thenCompose(destination -> client.downloadFile(entry.getKey(),
                        entry.getValue().file().getPath(),
                        entry.getValue().size(),
                        overrideIndyUrl))
                    .thenApply(r -> returnFile(r, entry.getValue().file()))
                    .whenComplete((r, t) -> handleResult(agg, r, t))
                    .toCompletableFuture());
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenApply(ign -> agg);
    }

    public <T extends Asset> CompletableFuture<ResultAgg<File>> downloadToDirectory(VersionAssets<T> projectVersion,
                                                      Path versionRootDir,
                                                      boolean overrideIndyUrl) {
        if (projectVersion instanceof NpmNVAssets ass) {
            // NPM doesn't need pre-downloaded artifacts, you can use Download URL in commands line and npm will do the
            // rest
            return CompletableFuture.completedFuture(new ResultAgg<>(List.of(), List.of()));
        }

        Map<String, FileSize> urlPathMap = new HashMap<>();
        for (T asset : projectVersion.assets()) {
            File file = versionRootDir.resolve(asset.getFilename()).toFile();
            urlPathMap.put(asset.getDownloadURI().toString(), new FileSize(file, asset.getSize()));
        }

        return downloadByPath(urlPathMap, overrideIndyUrl);
    }

    private Result<File> returnFile(Result<String> uriResult, File file) {
        return switch (uriResult) {
            case Success(String ign) -> new Success<>(file);
            case Result.Error err -> err;
        };
    }

    private void validFilenames(Map<String, FileSize> uriPathMap, ResultAgg<File> agg) {
        uriPathMap.forEach((uri, file) -> {
            var uriuri = URI.create(uri);
            String[] split = uriuri.getPath().split("/");
            String filenameInUri = split[split.length-1];
            String filenameInDirectory = file.file().getName();

            if (!filenameInDirectory.equals(filenameInUri)) {
                agg.errors().add(new Result.Error.UncaughtException(new IllegalArgumentException("Filenames do not match")));
            }
        });
    }

    private <T> void handleResult(ResultAgg<T> agg, Result<T> single, Throwable t) {
        switch (single) {
            case null -> new Result.Error.UncaughtException(t);
            case Success(var result) -> agg.successes().add(result);
            case Result.Error err -> agg.errors().add(err);
        }
    }
}
