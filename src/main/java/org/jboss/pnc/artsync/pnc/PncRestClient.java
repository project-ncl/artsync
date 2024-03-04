package org.jboss.pnc.artsync.pnc;

import io.quarkus.rest.client.reactive.ClientExceptionMapper;
import io.quarkus.rest.client.reactive.ClientQueryParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.pnc.dto.Artifact;
import org.jboss.pnc.dto.Build;
import org.jboss.pnc.dto.response.Page;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletionStage;

@RegisterRestClient(configKey = "pnc-rest")
@Produces("application/json")
@Path("/pnc-rest/v2/")
@ClientQueryParam(name = "pageSize", value = "${pnc.page-size}")
public interface PncRestClient {

    @GET
    @Path("/builds")
    @ClientQueryParam(name = "sort", value = "asc=endTime")
    Page<Build> getBuilds(@QueryParam("pageIndex") int pageIndex,
                                          @QueryParam("q") String query);

    @GET
    @Path("/builds/{id}/artifacts/dependencies")
    Page<Artifact> getDependenciesPage(@PathParam("id") String buildId,
                                       @QueryParam("pageIndex") int pageIndex,
                                       @QueryParam("q") String query);

    @GET
    @Path("/builds/{id}/artifacts/built")
    Page<Artifact> getBuiltArtifacts(@PathParam("id") String buildId,
                                     @QueryParam("pageIndex") int pageIndex,
                                     @QueryParam("q") String query);

//    @ClientExceptionMapper
//    static RuntimeException exceptionMapping(Response response, Method method) {
//        return switch (response.getStatus()) {
//            case Integer i when (i >= 400) && (i <= 499) -> new RuntimeException();
//        }
//        return null;
//    }
}
