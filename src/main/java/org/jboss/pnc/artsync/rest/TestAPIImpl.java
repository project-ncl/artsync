package org.jboss.pnc.artsync.rest;

import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.commonjava.indy.folo.dto.TrackedContentEntryDTO;
import org.jboss.pnc.artsync.BuildArtifactCollector;
import org.jboss.pnc.artsync.GrouperManager;
import org.jboss.pnc.artsync.UploadCronJob;
import org.jboss.pnc.artsync.aws.AWSService;
import org.jboss.pnc.artsync.indy.IndyService;
import org.jboss.pnc.artsync.model.Asset;
import org.jboss.pnc.artsync.model.ProjectAssets;
import org.jboss.pnc.artsync.model.Results;
import org.jboss.pnc.artsync.model.hibernate.BuildStat;
import org.jboss.pnc.artsync.pnc.PncService;
import org.jboss.pnc.artsync.pnc.ResultAgg;
import org.jboss.pnc.artsync.rest.api.TestAPI;
import org.jboss.pnc.dto.Artifact;
import org.jboss.pnc.dto.Build;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

@ApplicationScoped
@Blocking
public class TestAPIImpl implements TestAPI {

    private static final Logger LOG = LoggerFactory.getLogger(TestAPIImpl.class);

    @Inject
    PncService service;

    @Inject
    IndyService indy;

    @Inject
    AWSService aws;

    @Inject
    BuildArtifactCollector collector;

    @Inject
    GrouperManager grouper;

    @Inject
    UploadCronJob job;

    @Override
    public Collection<Build> testBuildConcurrency() {
        ResultAgg<Build> builds = service.getBuilds(ZonedDateTime.now().minus(Duration.ofDays(365 * 2)), Integer.MAX_VALUE);
        for (var error : builds.errors()) {
            LOG.error("Errors: " + error);
        }
        return builds.successes();
    }

    @Override
    public Collection<Artifact> testBuildArtifacts(String buildID) {
        ResultAgg<Artifact> artifacts = service.getArtifacts(new BuildStat(buildID));
        for (var error : artifacts.errors()) {
            LOG.error("Errors: " + error);
        }
        return artifacts.successes();
    }

    @Override
    public List<TrackedContentEntryDTO> testIndyTracking(String build) {
        ResultAgg<TrackedContentEntryDTO> report = indy.getTrackingReport(build);
        for (var error : report.errors()) {
            LOG.error("Errors: " + error);
            throw new NotFoundException("It's missing... I am sorry");
        }
        return report.successes();
    }

    @Override
    public List<Asset> testArts(String build) {
        return collector.collectAssets(new BuildStat(build));
    }

    @Override
    public List<ProjectAssets<?, ?>> testMultipleBuilds(List<String> buildIds) {
        List<ProjectAssets<?, ?>> projectAssets = grouper.analyzeBuilds(buildIds.stream().map(BuildStat::new).toList());
        return projectAssets;
    }

    @Override
    public List<String> fetchFromIndy(boolean override, Map<String, String> uriPathMap) {
        ResultAgg<File> agg = indy.downloadByPath(uriPathMap.entrySet().stream()
            .collect(Collectors.toMap(entry -> entry.getKey(),
                    entry -> Path.of(entry.getValue()).toFile()
                )
            ), override).join();
        for (var error : agg.errors()) {
            LOG.error("Error: " + error);
            throw new BadRequestException(error.toString());
        }

        return agg.successes().stream().map(File::getName).toList();
    }

    @Override
    public List<Asset> testSingleProjectUpload(String buildId, Integer numOfUploads) {
        List<ProjectAssets<?, ?>> projects = grouper.analyzeBuilds(List.of(new BuildStat(buildId)));
        List<ProjectAssets<?, ?>> assets = new ArrayList<>();
        if (numOfUploads == null || numOfUploads.equals(1)) {
            var asset = projects.get(new Random().nextInt(projects.size() - 1));
            var asset2 = projects.stream().filter(proj -> proj.getProjectVersionAssets().size() > 1).findAny().orElse(asset);
            assets.add(asset2);
        } else {
            assets.addAll(projects.stream().limit(numOfUploads).toList());
        }

        for (var asset : assets) {
            var agg = grouper.uploadAssets(asset).join();
            agg.stream().filter(Results::haveErrors).forEach(res -> {
                for (var error : res.errors()) {
                    LOG.error("Error {}", error.toString());
                }
            });
        }

        return List.of();
    }

    @Override
    public List<Asset> testFullBuildUpload(String build) {
        grouper.uploadBuildsIds(List.of(build));

        return List.of();
    }

    @Override
    public Response tryoutProcesses() {
        aws.testProcesses().join();

        return Response.ok("\"lol\":\"lul\"").build();
    }

    @Override
    public Response tryoutPinning() {
        aws.testPinning().join();

        return Response.ok("\"lol\":\"lul\"").build();
    }

    @Override
    public Response testResumeJob() {
        job.resume();
        return Response.ok().build();
    }

    @Override
    public Response testPauseJob() {
        job.pause();
        return Response.ok().build();
    }

    @Override
    public Response manualJobTrigger() {
        if (job.canTrigger()) {
            job.processBuildsJob();
        }
        return Response.ok().build();
    }
}
