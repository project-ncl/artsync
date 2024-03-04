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
