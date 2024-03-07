package org.jboss.pnc.artsync;

import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.virtual.threads.VirtualThreads;
import io.vertx.core.Vertx;
import io.vertx.core.file.FileSystem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.ListUtils;
import org.commonjava.atlas.maven.ident.ref.ProjectRef;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.da.common.version.VersionComparator;
import org.jboss.da.common.version.VersionParser;
import org.jboss.pnc.artsync.aws.AWSService;
import org.jboss.pnc.artsync.config.ArtsyncConfig;
import org.jboss.pnc.artsync.indy.IndyService;
import org.jboss.pnc.artsync.model.Asset;
import org.jboss.pnc.artsync.model.GPAsset;
import org.jboss.pnc.artsync.model.GPAssets;
import org.jboss.pnc.artsync.model.GPProjectAssets;
import org.jboss.pnc.artsync.model.MavenAsset;
import org.jboss.pnc.artsync.model.MvnGAAssets;
import org.jboss.pnc.artsync.model.MvnGAVAssets;
import org.jboss.pnc.artsync.model.NpmAsset;
import org.jboss.pnc.artsync.model.NpmNVAssets;
import org.jboss.pnc.artsync.model.NpmProjectAssets;
import org.jboss.pnc.artsync.model.ProjectAssets;
import org.jboss.pnc.artsync.model.Results;
import org.jboss.pnc.artsync.model.UploadResult;
import org.jboss.pnc.artsync.model.UploadResult.Error.GenericError;
import org.jboss.pnc.artsync.model.VersionAssets;
import org.jboss.pnc.artsync.model.hibernate.BuildStat;
import org.jboss.pnc.artsync.model.hibernate.Job;
import org.jboss.pnc.artsync.pnc.PncService;
import org.jboss.pnc.artsync.pnc.Result;
import org.jboss.pnc.artsync.pnc.ResultAgg;
import org.jboss.pnc.dto.Build;
import org.jboss.pnc.dto.BuildRef;

import java.io.File;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.partitioningBy;
import static java.util.stream.Collectors.reducing;
import static java.util.stream.Collectors.toMap;

@ApplicationScoped
@Slf4j
public class GrouperManager {
    private final ProcessedArtifactsCache processedCache;

    private final BuildArtifactCollector artifactCollector;

    private final PncService pnc;

    private final IndyService indy;

    private final AWSService aws;

    private final FileSystem fs;

    private final VersionComparator vc;

    private final VersionParser vp;

    private final ArtsyncConfig config;

    private final ResultsRepository rr;

    private final Semaphore pipelineSemaphore;

    private final boolean dryRun;

    @Inject
    @VirtualThreads
    ExecutorService executor;

    public GrouperManager(@Any ProcessedArtifactsCache processedCache,
                          BuildArtifactCollector artifactCollector,
                          PncService pnc,
                          IndyService indy,
                          AWSService aws,
                          Vertx vertx,
                          VersionComparator vc,
                          VersionParser vp,
                          ArtsyncConfig config,
                          ResultsRepository rr,
                          @ConfigProperty(name = "aws.dry-run") boolean dryRun) {
        this.processedCache = processedCache;
        this.artifactCollector = artifactCollector;
        this.pnc = pnc;
        this.indy = indy;
        this.aws = aws;
        this.fs = vertx.fileSystem();
        this.vc = vc;
        this.vp = vp;
        this.config = config;
        this.rr = rr;
        this.pipelineSemaphore = new Semaphore(config.pipelineConcurrencyLimit(), true);
        this.dryRun = dryRun;
    }

    public void processBuilds(ZonedDateTime timestamp, int limit, int partitionLimit) {
        ResultAgg<Build> builds = pnc.getBuilds(timestamp, limit);
        if (builds.hasErrors()) {
            log.error("Error getting builds: {}", builds.errors().toString());
            // FIXME probably a deal breaker in terms of the application
            throw new IllegalStateException("Issue with getting builds.");
        }

        List<List<BuildStat>> batchedBuilds = ListUtils.partition(builds.successes().stream().map(pncBuild -> new BuildStat(pncBuild.getId(), pncBuild.getEndTime().atZone(ZoneId.systemDefault()))).toList(), partitionLimit);
        log.info("Partitioning {} builds into partitions of {}.", builds.successes().size(), config.cron().batchSize());
        StringBuilder partitionStat = new StringBuilder();
        for (int i = 0; i < batchedBuilds.size(); i++) {
            partitionStat
                .append("\n  ")
                .append(i).append(": ")
                .append(String.join(", ", batchedBuilds.get(i).stream().map(BuildStat::getBuildID).toList()));
        }

        Job job = new Job();
        QuarkusTransaction.requiringNew().run(() -> {
            job.setStartTime(ZonedDateTime.now());
            job.persist();
        });


        log.info("Partitions: {}", partitionStat);
        for (int i = 0; i < batchedBuilds.size(); i++) {
            var partition = batchedBuilds.get(i);
            log.info("Processing partition {}: {}", i, String.join(", ", partition.stream().map(BuildStat::getBuildID).toList()));

            uploadBuilds(partition, job);
        }

        QuarkusTransaction.requiringNew().run(() -> {
            Job job2 = Panache.getEntityManager().merge(job);
            job2.setEndTime(ZonedDateTime.now());
            job2.persist();
        });

        log.info("Job {} finished. Collective stats: \n  : successes={}, errors={}, processed={}, filtered={}, total={}",
            job.id, job.successes, job.errors, job.cached, job.filtered, job.total);
    }

    public void uploadBuildsIds(List<String> buildIds, Job job) {
        List<BuildStat> builds = buildIds.stream().map(BuildStat::new).toList();

        uploadBuilds(builds, job);
    }

    public void uploadBuildsIds(List<String> buildIds) {
        uploadBuildsIds(buildIds, null);
    }

    public void uploadBuilds(List<BuildStat> builds, Job job) {
        log.info("Processing these build IDs {}", builds.stream().map(BuildStat::getBuildID).toList());

        if (job != null) {
            builds.forEach(build -> build.setJob(job));
        }

        List<ProjectAssets<?, ?>> projectAssets = analyzeBuilds(builds);
        List<Results<? extends Asset>> results = uploadAssets(projectAssets);

        collectStatsAndPersistResults(results, builds);

        log.info("Number of processed builds in partition: {}", builds.size());
        builds.forEach(stat -> log.info(
            "  {}: successes={}, errors={}, processed={}, filtered={}, total={}",
            stat.buildID, stat.successes, stat.errors, stat.cached, stat.filtered, stat.total));
    }

    private void collectStatsAndPersistResults(List<Results<? extends Asset>> results, List<BuildStat> builds) {
        // increase build stats
        for (var result : results) {
            result.successes().forEach(succ -> succ.result().asset().getProcessingBuildID().incSuccess(1));
            result.errors().forEach(err -> err.context().getProcessingBuildID().incError(1));
        }

        builds.stream()
            .filter(stat -> stat.getJob() != null)
            .collect(groupingBy(BuildStat::getJob))
            .forEach((job, jobBuilds) -> {
                // set timestamp of last processed build
                Stream<ZonedDateTime> stream = jobBuilds.stream()
                    .filter(stat -> !(stat.successes == 0 && stat.errors != 0)) // ignore builds without a single success
                    .map(BuildStat::getTimestamp);

                if (job.lastProcessed != null) {
                    stream = Stream.concat(stream, Stream.of(job.lastProcessed));
                }

                Optional<ZonedDateTime> biggest = stream.max(Comparator.naturalOrder());
                biggest.ifPresent(job::setLastProcessed);
            });


        if (!dryRun) {
            rr.persistResults(results, builds);
        }
    }

    public List<Results<? extends Asset>> uploadAssets(List<ProjectAssets<?, ?>> projectAssets) {
        AtomicInteger inc = new AtomicInteger(0);
        var results = new CopyOnWriteArrayList<Results<? extends Asset>>();
        var futures = new CopyOnWriteArrayList<CompletableFuture<List<Results<? extends Asset>>>>();
        for (var project : projectAssets) {
            futures.add(uploadAssets(project).thenApply(res -> {
                results.addAll(res);

                if (inc.get() % 100 == 0) {
                    log.info("UPLOADED {} projects, {} to go.", inc.get(), futures.size() - inc.get());
                }

                inc.incrementAndGet();
                return res;
            }));
        }

        for (var future : futures) future.join();

        return results;
    }

    public List<ProjectAssets<?, ?>> analyzeBuilds(List<BuildStat> builds) {
        // Assets have to be unique
        Set<Asset> assets = ConcurrentHashMap.newKeySet(builds.size() * 100);

        var allFutures = new CopyOnWriteArrayList<CompletableFuture<Void>>();
        for (var id : builds) {
            var future = artifactCollector.collectAssetsAsync(id)
                .thenApply(buildAssets -> {
                    // FILTER by processed cache
                    buildAssets.removeIf(asset -> {
                        boolean shouldProcess = !processedCache.shouldProcess(asset.getIdentifier());
                        if (shouldProcess) asset.getProcessingBuildID().incCached();

                        return shouldProcess;
                    });
                    return buildAssets;
                })
                .thenAccept(assets::addAll);
            allFutures.add(future);
        }
        CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[0])).join();

        // Group by G:A:V, N:V...
        List<VersionAssets<? extends Asset>> versionAssets = groupByVersion(assets);

        // Group by G:A, N... + Versions have to be correctly ordered
        return groupByProject(versionAssets);
    }

    public CompletableFuture<List<Results<? extends Asset>>> uploadAssets(ProjectAssets<?, ?> project) {
        var projectRootDir = determineProjectDir(project, config.downloadRootDir());
        CompletableFuture<? extends Results<? extends Asset>> future = null;
        List<CompletableFuture<? extends Results<? extends Asset>>> futures = new ArrayList<>();

        // Compose versions sequentially (thenCompose)
        for (var version : project.getProjectVersionAssets()) {
            Path versionRoot = determineVersionDir(projectRootDir, version);
            if (future == null) {
                future = handleProjectVersion(version, versionRoot);
            } else {
                future = future.thenCompose((prev) -> {
                    if (prev == null || prev.haveCriticalErrors()) {
                        log.error("Cancelling version " + version.versionIdentifier() + " because last one was error or cancelled.");
                        return CompletableFuture.completedFuture(null);
                    } else {
                        return handleProjectVersion(version, versionRoot);
                    }
                });
            }

            futures.add(future);
        }

        if (future == null) {
            // should never happen (only if project has no version)
            return CompletableFuture.completedFuture(List.of());
        }

        return future.thenApply((ign) -> {
            List<Results<? extends Asset>> toReturn = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                // at this point all futures[] have to be finished
                var uploadResult = futures.get(i).join();
                if (uploadResult == null) {
                    var version = project.getProjectVersionAssets().get(i);

                    // Recreate Result
                    var skip = new Results<>();
                    version.assets().forEach((Asset ass) -> skip.addError(new GenericError.Skipped<>(ass)));
                    toReturn.add(skip);
                } else {
                    toReturn.add(uploadResult);
                }

            }
            int successes = toReturn.stream().map(res -> res.successes().size()).reduce(Integer::sum).orElse(0);
            int errors = toReturn.stream().map(res -> res.errors().size()).reduce(Integer::sum).orElse(0);
            log.info("Project {} uploaded. Successes {}. Errors {}.", project, successes, errors);
            if (errors > 0) {
                String errStats = toReturn.stream().map(Results::errors).flatMap(Collection::stream)
                    .collect(groupingBy(err -> err.getClass().getSimpleName())).entrySet().stream()// group by Error class
                    .collect(toMap(Map.Entry::getKey, entry -> entry.getValue().size())) // tuples of 'number of errors per Error class'
                    .entrySet().stream().map(errStat -> "\n" + errStat.getKey() + ": " + errStat.getValue())
                    .reduce((err1, err2) -> err1 + err2).orElse("");
                log.warn("Project {} errors: {}", project.projectIdentifier(), errStats);
            }
            return toReturn;
        });
    }

    private CompletableFuture<? extends Results<? extends Asset>> handleProjectVersion(VersionAssets<?> version, Path versionRoot) {
        return CompletableFuture.runAsync(() -> {
            try {
                pipelineSemaphore.acquire();
            } catch (InterruptedException e) {
                throw new IllegalStateException("", e);
            }}, executor)
            .thenCompose(ign -> fs.mkdirs(versionRoot.toAbsolutePath().toString()).toCompletionStage().toCompletableFuture())
            .thenCompose(ign -> indy.downloadToDirectory(version, versionRoot, config.overrideIndyUrl()))
            .thenCompose(res -> {
                CompletableFuture<? extends Results<? extends Asset>> future;
                if (res.hasErrors()) {
                    future = CompletableFuture.completedFuture(convertResults(version, res));
                } else {
                    future = aws.uploadVersion(version, versionRoot);
                }
                return future;
            })
            .thenCompose(ass -> {
                if (config.cleanArtifacts()) {
                    return fs.deleteRecursive(versionRoot.toAbsolutePath().toString(), true)
                        .toCompletionStage()
                        .thenApply(ign -> ass);
                } else {
                    return CompletableFuture.completedStage(ass);
                }
            })
            .whenComplete((ass, ign) -> pipelineSemaphore.release());
    }

    private <T extends Asset> Results<T> convertResults(VersionAssets<T> version, ResultAgg<File> res) {
        Results<T> results = new Results<>();
        Set<T> remains = new HashSet<>(version.assets());
        res.successes().forEach(
            succ -> {
                Optional<T> match = version.assets().stream()
                    .filter(asset -> asset.getFilename().equals(succ.getName()))
                    .findFirst();
                if (match.isPresent()) {
                    results.addError(new GenericError.Invalidated<>(match.get(), null, null, null));
                    remains.remove(match.get());
                }
            }
        );
        res.errors().forEach(
            err -> {
                Optional<T> first = remains.stream().findFirst();
                if (first.isPresent()) {
                    results.addError(mapIndyError(err, first.get()));
                    remains.remove(first.get());
                } else {
                    log.error("INDY ERROR, couldn't find a scapegoat Asset to propagate Error {}", err.toString());
                }
            }
        );

        return results;
    }

    private static <T extends Asset> UploadResult.Error<T> mapIndyError(Result.Error err, T context) {
        return switch (err) {
            case Result.Error.UncaughtException(Throwable e) -> new GenericError.UncaughtException<>(context, e);
            case Result.Error.ClientError.ClientTimeout ct -> new GenericError.Timeout<>(context);
            case Result.Error.ClientError.SSLError(String message) ->
                new UploadResult.Error.IndyError.SSLError<>(context, message);
            case Result.Error.ServerError.UnknownError(Response response, String description) ->
                new GenericError.UnknownError<>(context, "Response Status " + response.getStatus() +
                    "; Body " + response.getEntity() +
                    "; " + description);
            case Result.Error.ClientError.NotFound(String uri) ->
                new UploadResult.Error.IndyError.NotFound<>(context, uri);
            case Result.Error.ServerError.SystemError(String description) ->
                new UploadResult.Error.IndyError.ServerError<>(context, description);
            case Result.Error.ClientError.AuthorizationError auth ->
                new UploadResult.Error.IndyError.ServerError<>(context, "Authorization Error");
            case Result.Error.ClientError.ServerUnreachable server ->
                new UploadResult.Error.IndyError.ServerError<>(context, "Can't reach Indy");
            case Result.Error.ServerError.ContentCorrupted(String filePath) ->
                new GenericError.CorruptedData<>(context, "Download " + filePath + " corrupted. Check download URL.");
        };
    }

    private Path determineProjectDir(ProjectAssets<?, ?> project, Path rootDir) {
        return switch (project) {
            case MvnGAAssets mvn -> {
                ProjectRef ga = mvn.getProjectRef();
                String[] artPart = ga.getGroupId().split("\\.");
                Path projectDir = rootDir.resolve("mvn");
                for (String part : artPart) {
                    projectDir = projectDir.resolve(part);
                }
                yield projectDir.resolve(ga.getArtifactId());
            }
            case NpmProjectAssets npm -> rootDir.resolve("npm").resolve(npm.getProjectRef().getName());
            case GPProjectAssets gp -> rootDir.resolve("gp");
        };
    }


    /**
     * Must be unique for EACH projectVersion
     *
     * @param project
     * @return
     */
    private <T extends Asset> Path determineVersionDir(Path projectDir, VersionAssets<T> version) {
        return switch (version) {
            case MvnGAVAssets gav -> projectDir.resolve(gav.getVersionRef().getVersionString());
            case NpmNVAssets nv -> projectDir.resolve(nv.getPackageRef().getVersionString());

            // FIXME make sure that it's unique
            case GPAssets gps -> projectDir.resolve(DigestUtils.md5Hex(gps.versionIdentifier()));
        };
    }

    private List<ProjectAssets<?, ?>> groupByProject(Collection<VersionAssets<? extends Asset>> versionAssets) {
        return versionAssets.stream().collect(groupingBy(pver -> pver.assets().getFirst().getPackageString()))
            .values()
            .stream()
            .map(vers -> switch (vers.getFirst()) {
                case MvnGAVAssets x -> new MvnGAAssets(vers.stream().map(MvnGAVAssets.class::cast)
                    .sorted((gav1, gav2) -> {
                        String v1 = gav1.getVersionRef().getVersionString();
                        String v2 = gav2.getVersionRef().getVersionString();
//                        SuffixedVersion parse1 = vp.parse(v1);
//                        SuffixedVersion parse2 = vp.parse(v2);
//
//                        if (parse1.getQualifier() != null && !parse1.getQualifier().isBlank() && !parse1.isSuffixed()) {
//                            log.warn("Consider adding a new version-suffix. Found {}", parse1.getOriginalVersion());
//                        }
//                        if (parse2.getQualifier() != null && !parse2.getQualifier().isBlank() && !parse2.isSuffixed()) {
//                            log.warn("Consider adding a new version-suffix. Found {}", parse2.getOriginalVersion());
//                        }

                        return vc.compare(v1, v2);}
                    ).toList());
                case NpmNVAssets x -> new NpmProjectAssets(vers.stream().map(NpmNVAssets.class::cast).sorted((nv1, nv2) -> {
                        String v1 = nv1.getPackageRef().getVersionString();
                        String v2 = nv2.getPackageRef().getVersionString();
                        return vc.compare(v1, v2);}
                    ).toList());
                case GPAssets x -> new GPProjectAssets(vers.stream().map(GPAssets.class::cast).toList());
            })
            .toList();
    }

    private List<VersionAssets<? extends Asset>> groupByVersion(Collection<Asset> assets) {
        return assets.stream()
            .collect(groupingBy(Asset::getPackageVersionString))
            .values()
            .stream()
            .map(list -> switch (list.getFirst()) {
                case MavenAsset x -> new MvnGAVAssets(list.stream().map(MavenAsset.class::cast).toList());
                case NpmAsset y -> new NpmNVAssets(list.stream().map(NpmAsset.class::cast).toList());
                case GPAsset z -> new GPAssets(list.stream().map(GPAsset.class::cast).toList());
            })
            .toList();
    }
}
