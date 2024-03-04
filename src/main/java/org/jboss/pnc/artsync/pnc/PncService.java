package org.jboss.pnc.artsync.pnc;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.da.common.version.VersionAnalyzer;
import org.jboss.pnc.artsync.config.ArtifactConfig;
import org.jboss.pnc.artsync.config.ArtsyncConfig;
import org.jboss.pnc.artsync.model.hibernate.BuildStat;
import org.jboss.pnc.artsync.pnc.Result.Success;
import org.jboss.pnc.dto.Artifact;
import org.jboss.pnc.dto.Build;
import org.jboss.pnc.dto.response.Page;
import org.jboss.pnc.enums.RepositoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Pattern;

@ApplicationScoped
public class PncService {
    private final PncClient client;
    private final ArtifactConfig config;
    private static final Logger LOG = LoggerFactory.getLogger(PncService.class);

    public PncService(PncClient client,
                      ArtsyncConfig config) {
        this.client = client;
        this.config = config.artifactConfigs();
    }

    public ResultAgg<Build> getBuilds(ZonedDateTime since, int limit) {
        var aggregate = doQuery((pageIndex) -> client.getBuilds(since, pageIndex), limit);
        aggregate.successes().sort(Comparator.comparing(Build::getEndTime).thenComparing(Build::getId));

        if (aggregate.successes().size() > limit) {
            aggregate.successes().subList(limit, aggregate.successes().size()).clear();
        }

        return aggregate;
    }

    public ResultAgg<Artifact> getArtifacts(BuildStat build) {
        var builtAggregate = doQuery((pageIndex) -> client.getBuiltArtifacts(build.getBuildID(), pageIndex), Integer.MAX_VALUE);
        var dependencyAggregate = doQuery((pageIndex) -> client.getDependencies(build.getBuildID(), pageIndex), Integer.MAX_VALUE);

        var allSuccesses = new ArrayList<Artifact>(builtAggregate.successes());
        allSuccesses.addAll(dependencyAggregate.successes());
        build.setTotal(allSuccesses.size());

        var allErrors = new ArrayList<Result.Error>(builtAggregate.errors());
        allErrors.addAll(dependencyAggregate.errors());

        Set<Pattern> upload = new HashSet<>();
        boolean worked = allSuccesses.removeIf(art -> config.uploadFilter().denies(art, upload));

        Set<Pattern> source = new HashSet<>();
        boolean worrked = allSuccesses.removeIf(art -> config.sourceFilter().denies(art, source));

        Set<RepositoryType> types = new HashSet<>();
        boolean worrrked = allSuccesses.removeIf(art -> config.allowedTypes().denies(art, types));

        build.setFiltered(build.getTotal() - allSuccesses.size());

        if (!upload.isEmpty()) LOG.info("Removed PNC artifacts from Build {} with UPLOAD(filename) filter triggering these Patterns {}", build.buildID, upload);

        if (!source.isEmpty()) LOG.info("Removed PNC artifacts from Build {} with SOURCE filter triggering these Patterns {}", build.buildID, source);

        if (!types.isEmpty()) LOG.info("Removed PNC artifacts from Build {} by using TYPE filter with these Types {}", build.buildID, types);

        return new ResultAgg<>(allSuccesses, allErrors);
    }

    private <T> void handleResult(ResultAgg<T> aggregate, Result<Page<T>> page, Throwable t) {
        LOG.trace("Handling result, Rate-limiter Metrics: waiting: {} permissions: {}",client.getExecutor().getNumberOfWaitingThreads(),
            client.getExecutor().getNumberOfPermissions());
        switch (page) {
            case Success(var result) -> aggregate.successes().addAll(result.getContent());
            case Result.Error error -> aggregate.errors().add(error);
        }
    }

    private  <T> ResultAgg<T> doQuery(Function<Integer, CompletableFuture<Result<Page<T>>>> query, int limit) {
        var aggregate = new ResultAgg<T>(new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>());
        var firstResult = query.apply(0).whenComplete((r, t) -> handleResult(aggregate, r, t)).join();
        if (aggregate.hasErrors()) {
            return aggregate;
        }

        if (firstResult instanceof Success<Page<T>>(var page) ) {
            var maxDownloads = limit - page.getContent().size();
            var maxPages = (double) maxDownloads / page.getPageSize();
            var remainingPages = Math.ceil(Math.min(maxPages, page.getTotalPages() - 1));

            if (remainingPages <= 0) {
                return aggregate;
            }

            List<CompletableFuture<Result<Page<T>>>> futures = new ArrayList<>();

            for (int i = 1; i < remainingPages + 1; i++) {
                futures.add(query.apply(i).whenComplete((r, t) -> handleResult(aggregate,r,t)));
                LOG.trace("Printing i:{}", i);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        return aggregate;
    }

}
