package org.jboss.pnc.artsync.aws;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.pnc.artsync.config.ArtsyncConfig;
import org.jboss.pnc.artsync.model.Asset;
import org.jboss.pnc.artsync.model.AssetUpload;
import org.jboss.pnc.artsync.model.GPAssets;
import org.jboss.pnc.artsync.model.MavenAsset;
import org.jboss.pnc.artsync.model.MvnGAVAssets;
import org.jboss.pnc.artsync.model.NpmAsset;
import org.jboss.pnc.artsync.model.NpmNVAssets;
import org.jboss.pnc.artsync.model.Results;
import org.jboss.pnc.artsync.model.UploadResult;
import org.jboss.pnc.artsync.model.UploadResult.Error.GenericError;
import org.jboss.pnc.artsync.model.UploadResult.Success;
import org.jboss.pnc.artsync.model.VersionAssets;
import org.jboss.pnc.artsync.pnc.Result;
import org.jboss.resteasy.reactive.common.NotImplementedYet;
import software.amazon.awssdk.services.codeartifact.model.PackageFormat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

import static org.jboss.pnc.artsync.config.RepositoryMapping.parseIndyRepository;
import static software.amazon.awssdk.services.codeartifact.model.PackageFormat.MAVEN;
import static software.amazon.awssdk.services.codeartifact.model.PackageFormat.NPM;

@ApplicationScoped
public class AWSService {

    private final AWSClient client;

    private final SettingsGenerator settingsProvider;

    private final ArtsyncConfig config;

    public AWSService(AWSClient client, SettingsGenerator settingsProvider, ArtsyncConfig config) {
        this.client = client;
        this.settingsProvider = settingsProvider;
        this.config = config;
    }

    public Result<Boolean> validateRepositories(Collection<String> repositories) {
        var future = client.repositoriesExists(repositories);

        return future.join();
    }

    public Result<String> getRepositoryEndpoint(String repository, PackageFormat format) {
        var future = client.getRepositoryEndpoint(repository, format);

        return future.join();
    }

    public CompletableFuture<Void> testProcesses() {
        var all = new ArrayList<CompletableFuture<Void>>();
        for (int i = 0; i < 100; i++) {
//            all.add(client.uploadMaven(i));
        }

        return CompletableFuture.allOf(all.toArray(new CompletableFuture[0]));
    }

    public CompletableFuture<Void> testPinning() {
        var all = new ArrayList<CompletableFuture<Void>>();
        for (int i = 0; i < 10; i++) {
            all.add(client.ultraPin());
        }

        return CompletableFuture.allOf(all.toArray(new CompletableFuture[0]));
    }

    public CompletableFuture<? extends Results<? extends Asset>> uploadVersion(VersionAssets<? extends Asset> versionAssets,
                                                                                 Path assetDir) {
        return (switch (versionAssets) {
            case MvnGAVAssets gav -> uploadMavenGAV(gav, assetDir);
            case NpmNVAssets nv -> uploadNpmNV(nv, assetDir);
            // TODO?
            case GPAssets gp -> throw new NotImplementedYet();
        }).thenApply(this::invalidateOnErrors);
    }

    private <T extends Asset> Results<T> invalidateOnErrors(Results<T> results) {
        if (!results.haveCriticalErrors()) {
            return results;
        }

        Results<T> invalidatedResults = new Results<>();
        // INVALIDATE successes because operation failed
        results.successes().forEach(suc ->
                invalidatedResults.addError(
                    new GenericError.Invalidated<>(
                        suc.result().asset(),
                        suc.result().deployedUrl(),
                        suc.result().awsRepository(),
                        suc.result().uploadTime())));
        results.errors().forEach(invalidatedResults::addError);

        return invalidatedResults;
    }

    private CompletableFuture<Results<NpmAsset>> uploadNpmNV(NpmNVAssets nv, Path assetDir) {
        String awsRepo = config.repositoryMapping().mapToAws(nv.getSourceRepository());
        if (awsRepo == null) {
            var result = new Results<NpmAsset>();
            nv.assets().forEach(ass -> result.addError(new GenericError.MissingRepositoryMapping<>(ass, parseIndyRepository(nv.getSourceRepository()))));

            return CompletableFuture.completedFuture(result);
        }

        return client.uploadProject(nv,
            assetDir,
            settingsProvider.getRepoUrl(awsRepo, NPM),
            awsRepo,
            settingsProvider.getSettings(awsRepo, NPM).toPath().toString());
    }

    public CompletableFuture<Results<MavenAsset>> uploadMavenGAV(MvnGAVAssets gav, Path assetDir) {
        String awsRepo = config.repositoryMapping().mapToAws(gav.getSourceRepository());
        if (awsRepo == null) {
            var result = new Results<MavenAsset>();
            gav.assets().forEach(ass -> result.addError(new GenericError.MissingRepositoryMapping<>(ass, parseIndyRepository(gav.getSourceRepository()))));

            return CompletableFuture.completedFuture(result);
        }

        return client.uploadProjectMvn2(gav,
            assetDir,
            settingsProvider.getRepoUrl(awsRepo, MAVEN),
            awsRepo,
            settingsProvider.getSettings(awsRepo, MAVEN).toPath().toString());
    }


}
