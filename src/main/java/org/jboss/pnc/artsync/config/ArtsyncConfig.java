package org.jboss.pnc.artsync.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import jakarta.persistence.criteria.CriteriaBuilder;

import java.nio.file.Path;

@ConfigMapping(prefix = "artsync")
public interface ArtsyncConfig {

    @WithName("artifacts")
    ArtifactConfig artifactConfigs();

    @WithName("repositories")
    RepositoryMapping repositoryMapping();

    Path downloadRootDir();

    @WithDefault("false")
    Boolean overrideIndyUrl();

    @WithDefault("true")
    Boolean cleanArtifacts();

    /**
     * How many project versions can be handled at the same time.
     * If left on Integer.MAX_VALUE and AWS is the bottleneck, this can download huge amount of artifacts from Indy.
     * Leave it restrained by default so that Indy DOWNLOADS, AWS UPLOADS, Filesystem DELETES are grouped together but
     * still concurrent.
     * @return limit of concurrently processed project versions (GAVs, NVs)
     */
    @WithDefault("2147483647") //Integer.MAX_VALUE for practically full concurrency
    int pipelineConcurrencyLimit();

    CronConfig cron();

    interface CronConfig {
        String schedule();
        int jobSize();
        int batchSize();

        @WithDefault("false")
        boolean startPaused();
    }
}
