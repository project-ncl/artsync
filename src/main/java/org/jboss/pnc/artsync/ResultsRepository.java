package org.jboss.pnc.artsync;

import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.transaction.Transactional;
import org.jboss.pnc.artsync.model.Asset;
import org.jboss.pnc.artsync.model.Results;
import org.jboss.pnc.artsync.model.hibernate.AssetEntry;
import org.jboss.pnc.artsync.model.hibernate.BuildStat;
import org.jboss.pnc.artsync.model.hibernate.Job;

import java.util.List;
import java.util.Objects;

@ApplicationScoped
public class ResultsRepository {

    private final ProcessedArtifactsCache artifactsCache;

    public ResultsRepository(@Any ProcessedArtifactsCache artifactsCache) {
        this.artifactsCache = artifactsCache;
    }

    @Transactional
    public void persistResults(List<Results<? extends Asset>> results, List<BuildStat> builds) {
        // persist job if present
        builds.stream()
            .map(BuildStat::getJob)
            .filter(Objects::nonNull)
            .distinct()
            .forEach(job -> {
                Job merged = Panache.getEntityManager().merge(job);
                job.getBuilds().forEach(build -> build.setJob(job));
                merged.persist();
            });

        // persist buildStats
        builds.forEach(stat -> stat.persist());

        BuildStat.flush();
        // persist AssetEntries
        for (var result : results) {
            for (var error : result.errors()) {
                AssetEntry entry = new AssetEntry(error);
                entry.persist();
            }

            for (var success : result.successes()) {
                AssetEntry entry = new AssetEntry(success.result());
                artifactsCache.commitProcessed(entry.identifier);
                entry.persist();
            }
        }
        AssetEntry.flush();
    }
}
