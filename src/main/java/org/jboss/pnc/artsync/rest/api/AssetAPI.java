package org.jboss.pnc.artsync.rest.api;

import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.jboss.pnc.artsync.model.hibernate.AssetEntry;
import org.jboss.pnc.artsync.model.hibernate.Category;
import org.jboss.pnc.artsync.model.hibernate.Paged;
import org.jboss.pnc.artsync.rest.Pagination;
import org.jboss.pnc.enums.RepositoryType;

@Path("/assets")
@Produces(value = "application/json")
public interface AssetAPI {

    @GET
    @Path("/{id}")
    AssetEntry getSpecific(@PathParam(value = "id") long id);

    @GET
    Paged<AssetEntry> getAll(@BeanParam Pagination page,
                             @QueryParam("identifier") String identifier,
                             @QueryParam("type") RepositoryType type,
                             @QueryParam("errors") boolean errors,
                             @QueryParam("since-created") String since,
                             @QueryParam("error-category") Category category,
                             @QueryParam("error-type") String errorType);

    @POST
    @Path("/{id}/mark-fixed")
    Response markErrorFixed(@PathParam(value = "id") long id);
}
