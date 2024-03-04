package org.jboss.pnc.artsync;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.ScheduledExecution;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.health.Readiness;

@Singleton
public class CacheNotInitialized implements Scheduled.SkipPredicate {

    @Inject
    @Readiness
    ProcessedArtifactsCache cache;

    @Override
    public boolean test(ScheduledExecution execution) {
        return switch (cache.call().getStatus()) {
            //do not skip
            case UP -> false;

            //skip
            case DOWN -> true;
        };
    }
}
