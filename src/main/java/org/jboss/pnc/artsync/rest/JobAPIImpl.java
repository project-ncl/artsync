package org.jboss.pnc.artsync.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.jboss.pnc.enums.RepositoryType;
import org.jboss.pnc.artsync.UploadCronJob;
import org.jboss.pnc.artsync.model.hibernate.AssetEntry;
import org.jboss.pnc.artsync.model.hibernate.BuildStat;
import org.jboss.pnc.artsync.model.hibernate.Category;
import org.jboss.pnc.artsync.model.hibernate.Job;
import org.jboss.pnc.artsync.model.hibernate.Paged;
import org.jboss.pnc.artsync.rest.api.JobAPI;

import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class JobAPIImpl implements JobAPI {

    @Inject
    UploadCronJob job;

    @Override
    public Paged<Job> getAll(String since, Pagination page) {
        ZonedDateTime sinceParsed = null;
        if (since != null) {
            sinceParsed = ZonedDateTime.parse(since);
        }

        return Job.getSince(page.toPage(), sinceParsed);
    }

    @Override
    public Job getSpecific(long id) {
        return Job.findById(id);
    }

    @Override
    public Paged<BuildStat> getBuilds(long id, Pagination page) {
        return BuildStat.getByJobID(page.toPage(), id);
    }

    @Override
    public Paged<AssetEntry> getAssets(long id,
                                       Pagination page,
                                       String identifier,
                                       RepositoryType type,
                                       boolean errors,
                                       Category category,
                                       String errorType) {
        return AssetEntry.getAllFiltered(page.toPage(),
            identifier,
            type,
            errors,
            null,
            category,
            errorType,
            null,
            null,
            id);
    }

    @Override
    public Job getLatest() {
        return Job.getLatest();
    }

    @Override
    public Paged<AssetEntry> getLatestAssets(Pagination page,
                                             String identifier,
                                             RepositoryType type,
                                             boolean errors,
                                             Category category,
                                             String errorType) {
        Job latest = Job.getLatest();
        if (latest != null) {
            return AssetEntry.getAllFiltered(page.toPage(), identifier, type, errors, null, category, errorType, null, null, latest.id);
        }
        return Paged.emptyPage(page.toPage());
    }

    @Override
    public Paged<BuildStat> getLatestBuilds(Pagination page) {
        Job latest = Job.getLatest();
        if (latest != null) {
            return BuildStat.getByJobID(page.toPage(), latest.id);
        }
        return Paged.emptyPage(page.toPage());
    }

    @Override
    @Transactional
    public Job overrideTimestamp(String timestamp) {
        ZonedDateTime sinceParsed = null;
        if (timestamp != null) {
            sinceParsed = ZonedDateTime.parse(timestamp);
        }

        Job job = new Job();
        job.startTime = ZonedDateTime.now();
        job.lastProcessed = sinceParsed;
        job.endTime = ZonedDateTime.now();
        job.persist();

        return job;
    }

    @Override
    public Response pause() {
        job.pause();
        return Response.ok().build();
    }

    @Override
    public Response resume() {
        job.resume();
        return Response.ok().build();
    }

    @Override
    public Response manualTrigger() {
        if (job.canTrigger()) {
            CompletableFuture.runAsync(() -> job.processBuildsJob());
            return Response.accepted().build();
        }
        throw new BadRequestException("Cannot start job while one is in progress.");
    }
}
