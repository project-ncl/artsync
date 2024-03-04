package org.jboss.pnc.artsync.rest.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import lombok.Getter;
import org.commonjava.indy.folo.dto.TrackedContentEntryDTO;
import org.jboss.pnc.artsync.model.Asset;
import org.jboss.pnc.artsync.model.ProjectAssets;
import org.jboss.pnc.dto.Artifact;
import org.jboss.pnc.dto.Build;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Path("/test")
@Produces(value = "application/json")
public interface TestAPI {

    @Path("/builds")
    @GET
    Collection<Build> testBuildConcurrency();

    @Path("/builds/{build}/artifacts")
    @GET
    Collection<Artifact> testBuildArtifacts(@PathParam("build") String build);

    @Path("/builds/{build}/tracking-report")
    @GET
    List<TrackedContentEntryDTO> testIndyTracking(@PathParam("build") String build);

    @Path("/builds/{build}/collect-arts")
    @GET
    List<Asset> testArts(@PathParam("build") String build);

    @Path("/project-assets/builds")
    @GET
    List<ProjectAssets<?,?>> testMultipleBuilds(@QueryParam("ids") List<String> buildIds);

    @Path("/indy/download")
    @Consumes("application/json")
    @GET
    List<String> fetchFromIndy(@QueryParam("override-url") boolean override, Map<String, String> uriPathMap);

    @GET
    @Consumes("application/json")
    @Path("/upload/build/{buildId}")
    List<Asset> testSingleProjectUpload(@PathParam("buildId") String buildId, @QueryParam("numOfUploads") Integer numOfUploads);

    @GET
    @Path("/upload/build/{buildId}/full")
    List<Asset> testFullBuildUpload(@PathParam("buildId") String build);

    @Path("/process/tryout")
    @GET
    Response tryoutProcesses();

    @Path("/process/pin")
    @GET
    Response tryoutPinning();

    @POST
    @Path("/job/yo/resume")
    Response testResumeJob();

    @POST
    @Path("/job/yo/pause")
    Response testPauseJob();

    @POST
    @Path("/job/yo/trigger")
    Response manualJobTrigger();

}
