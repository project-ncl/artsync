package org.jboss.pnc.artsync.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.pnc.enums.RepositoryType;
import org.jboss.pnc.artsync.GrouperManager;
import org.jboss.pnc.artsync.UploadCronJob;
import org.jboss.pnc.artsync.model.hibernate.AssetEntry;
import org.jboss.pnc.artsync.model.hibernate.BuildStat;
import org.jboss.pnc.artsync.model.hibernate.Category;
import org.jboss.pnc.artsync.model.hibernate.Paged;
import org.jboss.pnc.artsync.rest.api.BuildAPI;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

@ApplicationScoped
public class BuildAPIImpl implements BuildAPI {

    @Inject
    GrouperManager manager;

    @Inject
    ManagedExecutor executor;

    @Inject
    UploadCronJob job;

    @Override
    public Paged<BuildStat> getAll(Pagination page, String since) {
        ZonedDateTime sinceParsed = null;
        if (since != null) {
            sinceParsed = ZonedDateTime.parse(since);
        }

        return BuildStat.getAll(page.toPage(), sinceParsed);
    }

    @Override
    public BuildStat getSpecific(long id) {
        return BuildStat.findById(id);
    }

    @Override
    public Paged<AssetEntry> getAssets(long id, Pagination page, String identifier, RepositoryType type, boolean errors, Category category, String errorType) {
        return AssetEntry.getAllFiltered(page.toPage(), identifier, type, errors, null, category, errorType, id, null, null);
    }

    @Override
    public BuildStat getLatest() {
        return BuildStat.getLatest();
    }

    @Override
    public Paged<AssetEntry> getLatestAssets(Pagination page, String identifier, RepositoryType type, boolean errors, Category category, String errorType) {
        BuildStat latest = BuildStat.getLatest();
        if (latest == null) {
            return Paged.emptyPage(page.toPage());
        }

        return AssetEntry.getAllFiltered(page.toPage(), identifier, type, errors, null, category, errorType, latest.id, null, null);
    }

    @Override
    public Paged<BuildStat> getByBuildId(String id, Pagination page) {
        return BuildStat.getByBuildID(page.toPage(), id);
    }

    @Override
    public BuildStat getLatestByBuildId(String id, Pagination page) {
        Collection<BuildStat> stats = BuildStat.getByBuildID(page.toPage(), id).getContents();

        return stats.stream().max(Comparator.comparing((build) -> build.id)).orElse(null);
    }

    @Override
    public Response uploadSpecific(String id) {
        if (job.canTrigger()) {
            executor.runAsync(() -> manager.uploadBuildsIds(List.of(id)));
            return Response.accepted().build();
        }

        return Response
            .status(Response.Status.BAD_REQUEST.getStatusCode(), "Cannot upload concurrently.")
            .build();
    }

    @Override
    public Paged<AssetEntry> getByBuildAssets(String id, Pagination page, String identifier, RepositoryType type, boolean errors, Category category, String errorType) {
        return AssetEntry.getAllFiltered(page.toPage(), identifier, type, errors, null, category, errorType, null, id, null);
    }
}
