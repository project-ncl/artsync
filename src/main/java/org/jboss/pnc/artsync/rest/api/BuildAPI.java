package org.jboss.pnc.artsync.rest.api;

import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.jboss.pnc.artsync.model.hibernate.AssetEntry;
import org.jboss.pnc.artsync.model.hibernate.BuildStat;
import org.jboss.pnc.artsync.model.hibernate.Category;
import org.jboss.pnc.artsync.model.hibernate.Paged;
import org.jboss.pnc.artsync.rest.Pagination;
import org.jboss.pnc.enums.RepositoryType;

@Path("/builds")
@Produces(value = "application/json")
public interface BuildAPI {

    @GET
    Paged<BuildStat> getAll(@BeanParam Pagination page,
                            @QueryParam("since") String since);

    @GET
    @Path("/{id}")
    BuildStat getSpecific(@PathParam(value = "id") long id);

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
    BuildStat getLatest();

    @GET
    @Path("/latest/assets")
    Paged<AssetEntry> getLatestAssets(@BeanParam Pagination page,
                                      @QueryParam("identifier") String identifier,
                                      @QueryParam("type") RepositoryType type,
                                      @QueryParam("errors") boolean errors,
                                      @QueryParam("error-category") Category category,
                                      @QueryParam("error-type") String errorType);

    @GET
    @Path("/by-build/{id}")
    Paged<BuildStat> getByBuildId(@PathParam(value = "id") String id,
                                  @BeanParam Pagination page);

    @GET
    @Path("/by-build/{id}/latest")
    BuildStat getLatestByBuildId(@PathParam(value = "id") String id,
                                  @BeanParam Pagination page);

    @GET
    @Path("/by-build/{id}/upload")
    Response uploadSpecific(@PathParam(value = "id") String id);

    @GET
    @Path("/by-build/{id}/assets")
    Paged<AssetEntry> getByBuildAssets(@PathParam(value = "id") String id,
                                       @BeanParam Pagination page,
                                       @QueryParam("identifier") String identifier,
                                       @QueryParam("type") RepositoryType type,
                                       @QueryParam("errors") boolean errors,
                                       @QueryParam("error-category") Category category,
                                       @QueryParam("error-type") String errorType);


}
