package org.jboss.pnc.artsync.rest.api;

import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.jboss.pnc.artsync.model.hibernate.AssetEntry;
import org.jboss.pnc.artsync.model.hibernate.BuildStat;
import org.jboss.pnc.artsync.model.hibernate.Category;
import org.jboss.pnc.artsync.model.hibernate.Job;
import org.jboss.pnc.artsync.model.hibernate.Paged;
import org.jboss.pnc.artsync.rest.Pagination;
import org.jboss.pnc.enums.RepositoryType;

@Path("/jobs")
@Produces(value = "application/json")
@Consumes(value = "*/*")
public interface JobAPI {

    @GET
    Paged<Job> getAll(@QueryParam("since") String since,
                      @BeanParam Pagination page);

    @GET
    @Path("/{id}")
    Job getSpecific(@PathParam(value = "id") long id);

    @GET
    @Path("/{id}/builds")
    Paged<BuildStat> getBuilds(@PathParam(value = "id") long id,
                               @BeanParam Pagination page);

    @GET
    @Path("/{id}/assets")
    Paged<AssetEntry> getAssets(@PathParam(value = "id") long id,
                                @BeanParam Pagination page,
                                @QueryParam("identifier") String identifier,
                                @QueryParam("type") RepositoryType type,
                                @QueryParam("errors") boolean errors,
                                @QueryParam("error-category") Category category,
                                @QueryParam("error-type") String errorType);

    @GET
    @Path("/latest")
    Job getLatest();

    @GET
    @Path("/latest/assets")
    Paged<AssetEntry> getLatestAssets(@BeanParam Pagination page,
                                      @QueryParam("identifier") String identifier,
                                      @QueryParam("type") RepositoryType type,
                                      @QueryParam("errors") boolean errors,
                                      @QueryParam("error-category") Category category,
                                      @QueryParam("error-type") String errorType);

    @GET
    @Path("/latest/builds")
    Paged<BuildStat> getLatestBuilds(@BeanParam Pagination page);

    @POST
    @Path("/override-timestamp")
    Job overrideTimestamp(@QueryParam("timestamp") String timestamp);

    @POST
    @Path("/cron/pause")
    Response pause();

    @POST
    @Path("/cron/resume")
    Response resume();

    @POST
    @Path("/cron/trigger")
    Response manualTrigger();



}
