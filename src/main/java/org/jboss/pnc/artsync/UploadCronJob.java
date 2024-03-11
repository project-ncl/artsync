package org.jboss.pnc.artsync;

import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ApplicationNotRunning;
import io.quarkus.scheduler.Scheduler;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import lombok.extern.slf4j.Slf4j;
import org.jboss.pnc.artsync.config.ArtsyncConfig;
import org.jboss.pnc.artsync.model.hibernate.Job;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Slf4j
@ApplicationScoped
public class UploadCronJob {
    public static final String JOB_NAME = "Upload Job";

    private final Scheduler scheduler;

    private final GrouperManager manager;

    private final ArtsyncConfig.CronConfig config;

    private boolean running = false;

    @Startup
    void pauseIfConfigured() {
        if (config.startPaused()) {
            pause();
        }
    }

    public UploadCronJob(Scheduler scheduler, GrouperManager manager, ArtsyncConfig appConfig) {
        this.scheduler = scheduler;
        this.manager = manager;
        this.config = appConfig.cron();
    }

    @Scheduled(identity = JOB_NAME,
        cron = "${artsync.cron.schedule}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
        skipExecutionIf = CacheNotInitialized.class)
    @Blocking
    public void processBuildsJob() {
        try {
            running = true;

            log.info("Starting an Upload Job");

            ZonedDateTime since = Job.getLastFinishedJob()
                .map(Job::getLastProcessed)
                .orElse(ZonedDateTime.ofInstant(Instant.EPOCH, ZoneId.systemDefault()));
            manager.processBuilds(since, config.jobSize(), config.batchSize());

            log.info("Upload Job finished");
        } finally {
            running = false;
        }

    }

    public void resume() {
        scheduler.resume(JOB_NAME);
    }

    public void pause() {
        scheduler.pause(JOB_NAME);
    }

    public boolean canTrigger() {
        return !running;
    }

    void observeSkip(@Observes io.quarkus.scheduler.SkippedExecution event) {
        log.info("Upload job is taking too long. Skipping execution.");
    }

    void observePause(@Observes io.quarkus.scheduler.ScheduledJobPaused paused) {
        log.warn("Next execution of Job {} was paused.", paused.getTrigger().getId());
    }
    void observePause(@Observes io.quarkus.scheduler.ScheduledJobResumed resumed) {
        log.warn("Job {} was resumed. Last trigger at {}",
            resumed.getTrigger().getId(),
            resumed.getTrigger().getPreviousFireTime().atZone(ZoneId.systemDefault()));
    }
}
