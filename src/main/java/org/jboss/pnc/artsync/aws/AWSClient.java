package org.jboss.pnc.artsync.aws;

import io.github.resilience4j.core.functions.Either;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenException;
import io.quarkus.virtual.threads.VirtualThreads;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.queue.CircularFifoQueue;
import org.apache.http.NoHttpResponseException;
import org.apache.http.client.HttpResponseException;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.ConnectionPoolTimeoutException;
import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.deployment.DeployRequest;
import org.eclipse.aether.deployment.DeployResult;
import org.eclipse.aether.deployment.DeploymentException;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.transfer.AbstractTransferListener;
import org.eclipse.aether.transfer.ArtifactTransferException;
import org.eclipse.aether.transfer.TransferEvent;
import org.eclipse.aether.transfer.TransferResource;
import org.eclipse.aether.util.artifact.SubArtifact;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.pnc.artsync.concurrency.ConstrainedExecutor;
import org.jboss.pnc.artsync.model.Asset;
import org.jboss.pnc.artsync.model.AssetUpload;
import org.jboss.pnc.artsync.model.GPAsset;
import org.jboss.pnc.artsync.model.Label;
import org.jboss.pnc.artsync.model.MavenAsset;
import org.jboss.pnc.artsync.model.MvnGAVAssets;
import org.jboss.pnc.artsync.model.NpmAsset;
import org.jboss.pnc.artsync.model.NpmNVAssets;
import org.jboss.pnc.artsync.model.Results;
import org.jboss.pnc.artsync.model.UploadResult;
import org.jboss.pnc.artsync.model.UploadResult.Error.AWSError;
import org.jboss.pnc.artsync.model.UploadResult.Error.GenericError;
import org.jboss.pnc.artsync.model.UploadResult.Success;
import org.jboss.pnc.artsync.model.VersionAssets;
import org.jboss.pnc.artsync.pnc.Result;
import org.jboss.resteasy.reactive.common.NotImplementedYet;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.codeartifact.CodeartifactAsyncClient;
import software.amazon.awssdk.services.codeartifact.model.AssetSummary;
import software.amazon.awssdk.services.codeartifact.model.CodeartifactException;
import software.amazon.awssdk.services.codeartifact.model.CreateRepositoryRequest;
import software.amazon.awssdk.services.codeartifact.model.GetAuthorizationTokenRequest;
import software.amazon.awssdk.services.codeartifact.model.GetAuthorizationTokenResponse;
import software.amazon.awssdk.services.codeartifact.model.GetRepositoryEndpointRequest;
import software.amazon.awssdk.services.codeartifact.model.GetRepositoryEndpointResponse;
import software.amazon.awssdk.services.codeartifact.model.ListPackageVersionAssetsRequest;
import software.amazon.awssdk.services.codeartifact.model.ListPackageVersionAssetsResponse;
import software.amazon.awssdk.services.codeartifact.model.ListRepositoriesInDomainRequest;
import software.amazon.awssdk.services.codeartifact.model.PackageFormat;
import software.amazon.awssdk.services.codeartifact.model.RepositorySummary;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.toMap;

@ApplicationScoped
@Slf4j
public class AWSClient {

    private static final int CONTEXT_SIZE = 20;

    private final CodeartifactAsyncClient nativeClient;

    private final AWSApplicationConfig config;

    private final ConstrainedExecutor executor;

    private final ExecutorService regularExecutor;
    private final ManagedExecutor regularerExecutor;

    private final AWSTokenMaintainer tokenService;

    private final Semaphore processSemaphore;

    private final Pattern MVN_UPLOAD = Pattern.compile("^\\[INFO] Uploaded to .+: (?<deployUrl>\\S+) \\(.+ at .+\\)$");
    private final Pattern MVN_ERROR = Pattern.compile("^\\[ERROR] Failed to execute goal org\\.apache\\.maven\\.plugins:maven-deploy-plugin:3\\.1\\.1:deploy-file.+ status code: (?<status>.+), reason phrase: (?<reason>.+) -> .*$");
    private final Pattern NPM_UPLOAD = Pattern.compile("\\+ (?<uploadArtifact>.+)");
    private final Pattern NPM_ERROR = Pattern.compile("^npm ERR! code (?<status>.+)$");
    private final Pattern NPM_NOT_FOUND = Pattern.compile("^npm ERR! 404 Not Found - GET (?<notFoundUrl>.+)$");
    private final BootstrapMavenContext mavenContext;
    private final IListenListener artifactEventListener;

    public AWSClient(CodeartifactAsyncClient nativeClient,
                     AWSApplicationConfig config,
                     @VirtualThreads ExecutorService delegate,
                     ScheduledExecutorService scheduler,
                     ManagedExecutor regularerExecutor,
                     AWSTokenMaintainer tokenService) throws BootstrapMavenException {
        this.nativeClient = nativeClient;
        this.config = config;
        this.regularerExecutor = regularerExecutor;
        this.tokenService = tokenService;
        this.executor = new ConstrainedExecutor(regularerExecutor, scheduler, config, AWSClient::getIsRateLimited, null, AWSClient::shouldRetry);
        this.regularExecutor = delegate;
        this.processSemaphore = new Semaphore(config.subprocessConcurrencyLimit(), true);
        this.mavenContext = new BootstrapMavenContext(BootstrapMavenContext.config()
            .setCurrentProject((String) null)
            .setWorkspaceDiscovery(false)
            .setWorkspaceModuleParentHierarchy(false));
        //init
        mavenContext.getRepositorySystem();
        mavenContext.getRepositorySystemSession();
        DefaultRepositorySystemSession session = (DefaultRepositorySystemSession) mavenContext.getRepositorySystemSession();
        artifactEventListener = new IListenListener();

        if (config.dryRun()) {
            session.setTransferListener(new AbstractTransferListener() {});
            session.setRepositoryListener(new AbstractRepositoryListener() {});
        } else {
            session.setRepositoryListener(artifactEventListener);
            session.setTransferListener(artifactEventListener);
        }
    }

    private static boolean getIsRateLimited(Either<? extends Throwable, ?> either) {
        if (either.isRight() && either.get() instanceof Results res) {
            boolean toReturn = res.haveErrors() && res.errors().stream().anyMatch(err -> err instanceof AWSError.RateLimitExceeded);
            if (toReturn) log.warn("Rate limited out of object {}", res);
            return toReturn;
        }
        return false;
    }

    private static Set<Class<? extends UploadResult.Error>> retryOn =
        Set.of(AWSError.RateLimitExceeded.class, AWSError.ConnectionError.class);

    private static boolean shouldRetry(Object result) {
        boolean toReturn = result instanceof Results<?> res
            && res.haveErrors()
            && res.errors().stream().anyMatch(err -> retryOn.contains(err.getClass()));
        if (toReturn) log.warn("Retrying AWS requests for object {}.", result);
        return toReturn;
    }

    public CompletableFuture<Result<Boolean>> repositoriesExists(Collection<String> repositories) {
        return executor.supplyAsync(1, () -> nativeClient.listRepositoriesInDomain(ListRepositoriesInDomainRequest
                .builder()
                .domain(config.domain())
                .domainOwner(config.owner())
                .maxResults(1000)
                .build()).join())
            .thenApply((listResponse) -> {
                List<String> list = listResponse.repositories().stream().map(RepositorySummary::name).toList();
                return new HashSet<>(list).containsAll(repositories);
            })
            .handle(this::clientHandler);
    }

    public CompletableFuture<Result<String>> getRepositoryEndpoint(String repository, PackageFormat tool) {
        return executor.supplyAsync(1, () -> nativeClient.getRepositoryEndpoint(GetRepositoryEndpointRequest
                .builder()
                .domain(config.domain())
                .domainOwner(config.owner())
                .repository(repository)
                .format(tool)
                .build()).join())
            .thenApply(GetRepositoryEndpointResponse::repositoryEndpoint)
            .handle(this::clientHandler);
    }

    public CompletableFuture<Result<GetAuthorizationTokenResponse>> getTemporaryToken(Long durationSeconds) {
        return executor.supplyAsync(1, () -> nativeClient.getAuthorizationToken(
            GetAuthorizationTokenRequest.builder()
                .domain(config.domain())
                .domainOwner(config.owner())
                .durationSeconds(durationSeconds)
                .build()).join())
            .handle(this::clientHandler);
    }
    public CompletableFuture<Result<List<AssetSummary>>> getPackageAssets(String repository, Asset asset) {
        return switch (asset) {
            case MavenAsset mvn -> getPackageAssets(
                repository,
                mvn.getMvnIdentifier().getGroupId(),
                mvn.getMvnIdentifier().getArtifactId(),
                mvn.getMvnIdentifier().getVersionString(),
                PackageFormat.MAVEN);
            case NpmAsset npm -> getPackageAssets(
                repository,
                npm.getScope(),
                npm.getUnscopedName(),
                npm.getNpmIdentifier().getVersionString(),
                PackageFormat.NPM);
            case GPAsset gp -> throw new NotImplementedYet();
        };
    }

    private CompletableFuture<Result<List<AssetSummary>>> getPackageAssets(String repository,
                                                                                         String namespace,
                                                                                         String packageValue,
                                                                                         String packageVersion,
                                                                                         PackageFormat format) {
        return executor.supplyAsync(1, () -> nativeClient.listPackageVersionAssets(
                ListPackageVersionAssetsRequest.builder()
                    .domain(config.domain())
                    .domainOwner(config.owner())
                    .repository(repository)
                    .maxResults(1000)
                    .namespace(namespace)
                    .packageValue(packageValue)
                    .packageVersion(packageVersion)
                    .format(format)
                    .build()).join())
            .thenApply(ListPackageVersionAssetsResponse::assets)
            .handle(this::clientHandler);
    }

    public CompletableFuture<Result<List<AssetSummary>>> getPackageAssets(String repository,
                                                                          VersionAssets<Asset> packageVersion) {
        return getPackageAssets(repository, packageVersion.assets().getFirst());
    }

    public CompletableFuture<Result<String>> createRepository(String repository) {
        return executor.supplyAsync(1, () -> nativeClient.createRepository(CreateRepositoryRequest.builder()
                .domain(config.domain())
                .domainOwner(config.owner())
                .repository(repository)
                .description("Created by Artsync service during backup process.")
                .build()).join())
            .thenApply(response -> response.repository().name())
            .handle(this::clientHandler);
    }

    <T> Result<T> clientHandler(T result, Throwable error) {
        if (result != null) {
            return new Result.Success<>(result);
        } else {
            return switch (error) {
                //TODO moah categorization
                case CompletionException e ->
                    switch (e.getCause()) {
                        // server connection established
                        case CodeartifactException caException -> new Result.Error.ServerError.UnknownError(null, caException.getMessage());
                        // internal rest client error
                        case SdkClientException proc -> new Result.Error.UncaughtException(proc);
                        case null -> new Result.Error.UncaughtException(e);
                        default -> new Result.Error.UncaughtException(e.getCause());
                    };
                case null -> throw new UnsupportedOperationException();
                default -> new Result.Error.UncaughtException(error);
            };
        }
    }

    public CompletableFuture<Results<MavenAsset>> uploadProjectMvn2(MvnGAVAssets gav,
                                                                Path assetDir,
                                                                String awsRepoURL,
                                                                String repositoryId,
                                                                String settingsXmlPath) {
        var results = new Results<MavenAsset>();

        Authentication aws = new AuthenticationBuilder().addUsername("aws").addPassword(tokenService.getToken()).build();

        RemoteRepository awsRepo = new RemoteRepository.Builder(repositoryId, "default", awsRepoURL)
            .setAuthentication(aws)
            .setRepositoryManager(true)
            .build();

        DeployRequest request = new DeployRequest();

        request.setRepository(awsRepo);

        addAssets(request, assetDir, gav);

        if (config.dryRun()) {
            return mockResult(gav, awsRepoURL, repositoryId, request, results);
        }

        gav.assets().forEach(ass -> artifactEventListener.register(assetDir.resolve(ass.getFilename()).toFile(), ass, results));

        return executor.supplyAsync(gav.assets().size() + 4,
            () -> startAndHandle(gav, awsRepoURL, repositoryId, request, results))
            .whenComplete((result, thr) -> gav.assets().forEach(ass -> artifactEventListener.unregister(assetDir.resolve(ass.getFilename()).toFile())))
            .thenApply((result) -> verifyResult(result, gav.assets(), new CircularFifoQueue<>(), awsRepoURL, repositoryId));
    }

    private static CompletableFuture<Results<MavenAsset>> mockResult(MvnGAVAssets gav, String awsRepoURL, String repositoryId, DeployRequest request, Results<MavenAsset> results) {
        log.info("Would deploy with this request: {}", request);
        gav.assets().forEach(ass -> {
            AssetUpload<MavenAsset> upload = new AssetUpload<>(ass, awsRepoURL + "?", repositoryId, ZonedDateTime.now());
            results.addSuccess(new Success<>(upload));
        });
        return CompletableFuture.completedFuture(results);
    }

    private Results<MavenAsset> startAndHandle(MvnGAVAssets gav, String awsRepoURL, String repositoryId, DeployRequest request, Results<MavenAsset> results) {
        try {
            DeployResult deploy = mavenContext.getRepositorySystem().deploy(mavenContext.getRepositorySystemSession(), request);
            return results;
        } catch (Exception e) {
            return handleException(e, results, gav.assets(), repositoryId, awsRepoURL);
        }
    }

    private Results<MavenAsset> handleException(Exception e,
                                                Results<MavenAsset> results,
                                                List<MavenAsset> assets,
                                                String repositoryId,
                                                String awsRepoUrl) {
        Set<MavenAsset> noResult = new HashSet<>(assets);
        results.assets().forEach(noResult::remove);

        Results<MavenAsset> toReturn;
        if (!noResult.isEmpty()) {
            toReturn = results;
        } else {
            // rewrite random successful asset just to mark everything as failure
            // (problem can be in deploying metadata when all artifacts were successfully deployed)
            MavenAsset scapegoat;
            if (!results.successes().isEmpty()) {
                var randomSuccess = results.successes().getFirst();
                scapegoat = randomSuccess.result().asset();

                var successes = new ArrayList<>(results.successes());
                successes.remove(randomSuccess);

                // Redo Results with Extra space
                toReturn = new Results<>();
                successes.forEach(toReturn::addSuccess);
                results.errors().forEach(toReturn::addError);
            } else {
                var randomError = results.errors().getFirst();
                scapegoat = randomError.context();

                var errors = new ArrayList<>(results.errors());
                errors.remove(randomError);

                // Redo Results with Extra space
                toReturn = new Results<>();
                results.successes().forEach(toReturn::addSuccess);
                errors.forEach(toReturn::addError);
            }

            noResult.add(scapegoat);
        }

        noResult.forEach(ass -> {
            toReturn.addError(switch (e.getCause()) {
                case null -> new GenericError.UncaughtException<>(ass, e);
                default -> parseException(e.getCause(), repositoryId, ass.generateDeployUrlFrom(awsRepoUrl), ass);
            });
        });

        return results;
    }

    private static UploadResult.Error<MavenAsset> parseException(Throwable e, String repositoryId, String deployedUrl, MavenAsset ass) {
        return switch (e) {
            case HttpResponseException res -> handleStatusCode(repositoryId, deployedUrl, ass, res);
            case ArtifactTransferException res -> parseException(e.getCause(), repositoryId, deployedUrl, ass);
            case ConnectionPoolTimeoutException poolTimeout -> new GenericError.UnknownError<>(ass, "CONNECTION POOL TIMEOUT");
            case ConnectTimeoutException timeout -> new GenericError.Timeout<>(ass);
            case NoHttpResponseException noResponse -> new AWSError.ConnectionError<>(ass, noResponse.toString());
            default -> new GenericError.UncaughtException<>(ass, e.getCause());
        };
    }

    private static UploadResult.Error<MavenAsset> handleStatusCode(String repositoryId, String deployedUrl, MavenAsset ass, HttpResponseException res) {
        return switch (res.getStatusCode()) {
            case 400 -> new GenericError.CorruptedData<>(ass, res.getReasonPhrase());
            case 402 -> new AWSError.QuotaExceeded<>(ass);
            // should happen just on metadata
            case 404 -> new UploadResult.Error.IndyError.NotFound<>(ass, res.getReasonPhrase());
            case 409 -> new AWSError.Conflict<>(ass, deployedUrl, repositoryId, ZonedDateTime.now());
            case 429 -> new AWSError.RateLimitExceeded<>(ass);
            case 500 -> new AWSError.ServerError<>(ass, res.getReasonPhrase());
            default -> new GenericError.UnknownError<>(ass, res.getReasonPhrase());
        };
    }

    private void addAssets(DeployRequest request, Path assetDir, MvnGAVAssets gav) {
        List<MavenAsset> toUpload = new ArrayList<>(gav.assets());
        MavenAsset main = toUpload.stream().filter(asset -> asset.getLabel() == Label.TOP_JAR).findFirst().orElse(toUpload.getLast());
        var gavRef = main.getMvnIdentifier();
        Artifact artifact = new DefaultArtifact(
            gavRef.getGroupId(),
            gavRef.getArtifactId(),
            gavRef.getClassifier(),
            gavRef.getType(),
            gavRef.getVersionString())
            .setFile(assetDir.resolve(main.getFilename()).toFile());
        toUpload.remove(main);
        request.addArtifact(artifact);

        for (MavenAsset subAsset : toUpload) {
            var ref = subAsset.getMvnIdentifier();
            request.addArtifact(
                new SubArtifact(
                    artifact,
                    ref.getClassifier(),
                    ref.getType(),
                    assetDir.resolve(subAsset.getFilename()).toFile()));
        }
    }

    private static class IListenListener extends AbstractMavenListener {

        public ConcurrentHashMap<File, MavenAsset> toAssetMap = new ConcurrentHashMap<>(1000);
        public ConcurrentHashMap<File, Results<MavenAsset>> toResultsMap = new ConcurrentHashMap<>(1000);

        void register(File file, MavenAsset asset, Results<MavenAsset> results) {
            toAssetMap.put(file, asset);
            toResultsMap.put(file, results);
        }

        void unregister(File file) {
            toAssetMap.remove(file);
            toResultsMap.remove(file);
        }

        @Override
        public void artifactDeployed(RepositoryEvent event) {
            log.trace(event.toString());
        }

        @Override
        public void metadataDeployed(RepositoryEvent event) {
            log.trace(event.toString());
        }

        @Override
        public void transferFailed(TransferEvent event) {
            TransferResource resource = event.getResource();

            if (resource.getResourceName().contains("maven-metadata")){
                // handled later in handleException
                return;
            }

            var asset = toAssetMap.get(resource.getFile());
            var result = toResultsMap.get(resource.getFile());
            if (asset == null) {
                log.error("Unknown upload encountered. Event: {} Failure: {}", event, event.getException());
                return;
            }

            result.addError(parseException(event.getException(),
                resource.getRepositoryId(),
                resource.getRepositoryUrl() + resource.getResourceName(),
                asset));
        }

        @Override
        public void transferSucceeded(TransferEvent event) {
            TransferResource resource = event.getResource();

            // ignore non-upload events
            if (event.getRequestType() != TransferEvent.RequestType.PUT
                || resource.getResourceName().contains("maven-metadata")) {
                return;
            }

            var asset = toAssetMap.get(resource.getFile());
            var result = toResultsMap.get(resource.getFile());
            if (asset == null) {
                log.error("Unknown upload encountered. Event: {} Failure: {}", event, event.getException());
                return;
            }

            result.addSuccess(new Success<>(new AssetUpload<>(asset,
                resource.getRepositoryUrl() + resource.getResourceName(),
                resource.getRepositoryId(),
                ZonedDateTime.now())));
        }
    }
    /**
     *  {plugin} = org.apache.maven.plugins:maven-deploy-plugin:3.1.1:deploy-file
     *  mvn {plugin}
     *           -s {settings.xml path}
     *           -DrepositoryId={get-from-config-mapping}
     *           -Durl={repository-url}
     *           -Dfile={TOP-JAR}
     *           -DpomFile={TOP-POM}
     *           -DgeneratePom=false
     *           -Dsources={SOURCES}
     *           -Djavadocs={JAVADOCS}
     *           -DretryFailedDeploymentCount=3
     *  OTHER sfuff: -Dclassifiers={list}
     *               -Dtypes={list}
     *               -Dfiles={list}
     *
     *  set env CODEARTIFACT_AUTH_TOKEN
     *
     *  always have -Dfile, if no TOP_JAR, put TOP_POM...
     *   It's guaranteed to have atlease one TOP_POM
     *
     *  NO TOP-POM ==
     *           -DgroupId={g}
     *           -DartifactId={a}
     *           -Dversion={v}
     *           -Dpackaging=?!?
     *
     * @param gav gav to upload
     * @param assetDir
     * @param awsRepoURL
     * @param repositoryId
     * @param settingsXmlPath
     * @return
     */
    public CompletableFuture<Results<MavenAsset>> uploadProject(MvnGAVAssets gav,
                                                                  Path assetDir,
                                                                  String awsRepoURL,
                                                                  String repositoryId,
                                                                  String settingsXmlPath) {


        var results = new Results<MavenAsset>();

        var mvnCommand = generateMavenCommand(gav, awsRepoURL, repositoryId, settingsXmlPath);
        Map<String, Supplier<String>> env = Map.of("CODEARTIFACT_AUTH_TOKEN", tokenService::getToken);
        if (config.dryRun()) {
            return printCommand(mvnCommand, gav, assetDir, env, results, repositoryId, awsRepoURL);
        }

        log.info("Uploading Maven project: {}. Assets size {}. Assets {}.", gav.versionIdentifier(), gav.assets().size(), gav.prettyPrint());

        // LINE BUFFER OF CONTEXT_SIZE LENGTH; ON OVERFLOW OLDEST LINE GETS EVICTED; USED FOR SAVING CONTEXT IN ERRORS
        Queue<String> lineBuffer = new CircularFifoQueue<>(CONTEXT_SIZE);

        CompletableFuture<Integer> mvnProcess = processHandle(mvnCommand,
            (line) -> detectFinish(line, results, gav.assets(), awsRepoURL, repositoryId, lineBuffer),
            log::error,
            env,
            assetDir,
            gav.assets().size());


        return mvnProcess
            .handle((status, throwable) -> handleResult(results, gav.assets(), status, throwable, lineBuffer))
            .thenApply((result) -> verifyResult(result, gav.assets(), lineBuffer, awsRepoURL, repositoryId));
    }

    private <T extends Asset> Results<T> verifyResult(Results<T> finalResult,
                                                      List<T> assets,
                                                      Queue<String> lineBuffer,
                                                      String awsRepositoryUrl,
                                                      String repositoryId) {
        List<AssetSummary> awsAssets = null;
        for (var asset : assets) {
            if (!finalResult.contains(asset)) {
                log.warn("Asset {} is not in results. Trying listing AWS assets as fallback.", asset.getIdentifier());
                if (awsAssets == null){
                    Result<List<AssetSummary>> res = getPackageAssets(repositoryId, asset).join();
                    awsAssets = switch (res) {
                        case Result.Success(List<AssetSummary> list) -> list;
                        case Result.Error err -> {
                            log.error("Error getting Asset Summary from AWS: {}", err);
                            // avoid retries for every asset
                            yield List.of();
                        }
                    };
                }
                List<AssetSummary> finalAwsAssets = awsAssets;
                switch (asset) {
                    case NpmAsset npm
                        when finalAwsAssets.stream().map(AssetSummary::name).anyMatch(pack -> pack.equals("package.tgz"))
                        -> finalResult.addSuccess(new Success<>(new AssetUpload<>(asset, npm.generateDeployUrlFrom(awsRepositoryUrl), repositoryId, ZonedDateTime.now())));
                    case MavenAsset mvn
                        when finalAwsAssets.stream().map(AssetSummary::name).anyMatch(pack -> pack.equals(mvn.getFilename()))
                        -> finalResult.addSuccess(new Success<>(new AssetUpload<>(asset, mvn.generateDeployUrlFrom(awsRepositoryUrl), repositoryId, ZonedDateTime.now())));
                    case GPAsset gp -> throw new NotImplementedYet();
                    default -> finalResult.addError(new GenericError.MissingUpload<>(asset, joinBuffer(lineBuffer)));
                }
            }
        }
        return finalResult;
    }

    private <T extends Asset> Results<T> handleResult(Results<T> results,
                                                        List<T> assets,
                                                        Integer processStatus,
                                                        Throwable throwable,
                                                        Queue<String> lineBuffer) {
        if (throwable != null) {
            assets.forEach(ass -> results.addError(new GenericError.UncaughtException<>(ass, throwable)));
        }
        if (processStatus != null && processStatus != 0) {
            if (!results.haveErrors()) {
                List<T> noerrs = new ArrayList<>(assets);
                noerrs.removeAll(results.errors().stream().map(UploadResult.Error::context).toList());

                noerrs.forEach(ass -> results.addError(new GenericError.UnknownError<>(ass, joinBuffer(lineBuffer))));
            }
        }

        return results;
    }

    public CompletableFuture<Results<NpmAsset>> uploadProject(NpmNVAssets nv,
                                                              Path assetDir,
                                                              String awsRepoURL,
                                                              String repositoryId,
                                                              String npmrcPath) {
        var results = new Results<NpmAsset>();

        var npmCommand = generateNpmCommand(nv, npmrcPath);
        Map<String, Supplier<String>> env = Map.of("CODEARTIFACT_AUTH_TOKEN", tokenService::getToken);
        if (config.dryRun()) {
            return printCommand(npmCommand, nv, assetDir, env, results, repositoryId, awsRepoURL);
        }

        log.info("Uploading NPM project: {}. Assets size {}.", nv.versionIdentifier(), nv.assets());

        // LINE BUFFER OF CONTEXT_SIZE LENGTH; ON OVERFLOW OLDEST LINE GETS EVICTED; USED FOR SAVING CONTEXT IN ERRORS
        Queue<String> lineBuffer = new CircularFifoQueue<>(CONTEXT_SIZE);
        CompletableFuture<Integer> npmProcess = processHandle(npmCommand,
            line -> detectSuccessNPM(line, results, nv.assets(), repositoryId, awsRepoURL),
            line -> detectError(line, results, nv.assets(), lineBuffer, repositoryId, awsRepoURL),
            env,
            assetDir,
            nv.assets().size());

        return npmProcess
            .handle((status, throwable) -> handleResult(results, nv.assets(), status, throwable, lineBuffer))
            .thenApply((result) -> verifyResult(result, nv.assets(), lineBuffer, awsRepoURL, repositoryId));
    }
    private void detectSuccessNPM(String line, Results<NpmAsset> agg, List<NpmAsset> assets, String repositoryId, String awsRepoURL) {
        Matcher succ = NPM_UPLOAD.matcher(line);
        if (succ.matches()) {
            log.info("Success: " + line);
            String pack = succ.group("uploadArtifact");

            assets.forEach(ass -> agg.addSuccess(new Success<>(new AssetUpload<>(ass, ass.generateDeployUrlFrom(awsRepoURL), repositoryId, ZonedDateTime.now()))));
        }
    }

    private void detectError(String line, Results<NpmAsset> agg, List<NpmAsset> assets, Queue<String> contextBuffer, String repositoryId, String awsRepoUrl) {
        contextBuffer.add(line);

        if (notFoundError(line, agg, assets))
            return;

        if (httpStatusError(line, agg, assets, contextBuffer, repositoryId, awsRepoUrl))
            return;
    }

    private boolean notFoundError(String line, Results<NpmAsset> agg, List<NpmAsset> assets) {
        Matcher error = NPM_NOT_FOUND.matcher(line);
        if (error.matches()) {
            log.info("Indy missing Asset: " + line);
            String indyUrl = error.group("notFoundUrl");
            assets.forEach(ass -> agg.addError(new UploadResult.Error.IndyError.NotFound<>(ass, indyUrl)));

            return true;
        }
        return false;
    }

    private boolean httpStatusError(String line, Results<NpmAsset> agg, List<NpmAsset> assets, Queue<String> contextBuffer, String repositoryId, String awsRepoUrl) {
        Matcher error = NPM_ERROR.matcher(line);
        if (error.matches()) {
            log.info("Error: " + line);
            String statusCode = error.group("status");

            // Has its own pattern
            if (statusCode.equals("E404")) {
                return false;
            }

            assets.forEach(ass -> agg.addError(switch (statusCode) {
                case "E400" -> new GenericError.CorruptedData<>(ass, joinBuffer(contextBuffer));
                case "E402" -> new AWSError.QuotaExceeded<>(ass);
                case "ENEEDAUTH" -> new AWSError.InvalidToken<>(ass);
                // Has its own pattern
                case "E409" -> new AWSError.Conflict<>(ass, awsRepoUrl, repositoryId, ZonedDateTime.now());
                case "E429" -> new AWSError.RateLimitExceeded<>(ass);
                case "E500" -> new AWSError.ServerError<>(ass, joinBuffer(contextBuffer));
                default -> new GenericError.UnknownError<>(ass, joinBuffer(contextBuffer));
            }));
            return true;
        }
        return false;
    }

    private static String joinBuffer(Queue<String> buffer) {
        return buffer.stream().reduce((line1, line2) -> line1 + '\n' + line2).orElse("EMPTY BUFFER");
    }

    private static <T extends Asset> CompletableFuture<Results<T>> printCommand(List<String> command,
                                                                                VersionAssets<T> projectVersion,
                                                                                Path assetDir,
                                                                                Map<String, Supplier<String>> env,
                                                                                Results<T> results,
                                                                                String repositoryId,
                                                                                String awsRepoURL) {
        log.info("""
            Would call:
              Command: {}
              Directory: {}
              Token: {}
              Assets: {}
            """,
            command.stream().reduce((str, str2) -> str + " " + str2).get(),
            assetDir.toString(),
            env.get("CODEARTIFACT_AUTH_TOKEN").get(),
            projectVersion.prettyPrint());
        projectVersion.assets().forEach(ass -> {
            AssetUpload<T> upload = new AssetUpload<>(ass, awsRepoURL + "?", repositoryId, ZonedDateTime.now());
            results.addSuccess(new Success<>(upload));
        });

        return CompletableFuture.completedFuture(results);
    }

    private List<String> generateNpmCommand(NpmNVAssets nv, String npmrcPath) {
        List<String> command = new ArrayList<>(List.of("npm"));

        addNpmrc(command, npmrcPath);

        addOthers(command);

        addAssets(command, nv);
        return command;
    }

    private void addNpmrc(List<String> command, String npmrcPath) {
        command.add("--userconfig="+npmrcPath);
    }

    private void addAssets(List<String> command, NpmNVAssets nv) {
        if (nv.assets().size() != 1) {
            // should be just 1 tar:gz per package version
            log.error("Encountered NPM N:V with more than one asset. THIS CAN RESULT IN MISSING UPLOADS. NV:" + nv);
        }
        command.addAll(List.of("publish", nv.assets().getFirst().getDownloadURI().toString()));
    }

    private void addOthers(List<String> command) {
        command.add("--no-color");
    }

    private void detectFinish(String line, Results<MavenAsset> agg, List<MavenAsset> assets, String awsRepoURL, String repositoryId, Queue<String> contextBuffer) {
        contextBuffer.add(line);
        if(detectSuccess(line, agg, assets, awsRepoURL, repositoryId, contextBuffer)) {
            return;
        }

        if(detectFailure(line, agg, assets, contextBuffer)) {
            return;
        }
    }

    private boolean detectFailure(String line, Results<MavenAsset> agg, List<MavenAsset> assets, Queue<String> contextBuffer) {
        Matcher error = MVN_ERROR.matcher(line);
        if (!error.matches()) {
            return false;
        }

        String statusCode = error.group("status");
        String reason = error.group("reason");

        // FIXME contextualize errors more
        assets.forEach(ass -> agg.addError(
            new AWSError.ServerError<>(ass,
                "ERROR: Asset: " + ass.getFilename() + " Status: " + statusCode + " Reason: " + reason +
                    "\n Lines:\n"
                    + joinBuffer(contextBuffer))));
        return true;
    }

    private boolean detectSuccess(String line, Results<MavenAsset> agg, List<MavenAsset> assets, String awsRepoURL, String repositoryId, Queue<String> contextBuffer) {
        Matcher upload = MVN_UPLOAD.matcher(line);
        if (!upload.matches()) {
            return false;
        }

        String deployUrl = upload.group("deployUrl");
        if (!deployUrl.startsWith(awsRepoURL)) {
            log.error("Success Line matched but aws url differs.\n" + line);
        }
        String[] split = deployUrl.split("/");
        String filename = split[split.length-1];
        if (!filename.equals("maven-metadata.xml")) {
            Optional<MavenAsset> first = assets.stream().filter(ass -> ass.getFilename().equals(filename)).findFirst();
            if (first.isPresent()) {
                boolean added = agg.addSuccess(new Success<>(new AssetUpload<>(first.get(), deployUrl, repositoryId, ZonedDateTime.now())));
                if (!added)
                    log.warn("Asset {} is probably a duplicate, log-lines {}", first.get().getMvnIdentifier(), joinBuffer(contextBuffer));
                log.trace("MVN: found success for {}", first.get().getFilename());
                return true;
            }
            // FIXME improve ERROR maybe?
            log.error("MVN: Success found but {} did not match asset.", filename);
            return false;
        }
        return false;
    }

    private List<String> generateMavenCommand(MvnGAVAssets gav, String awsRepoURL, String repositoryId, String settingsXmlPath) {
        List<String> command = new ArrayList<>(List.of("mvn"));

        addSettings(command, settingsXmlPath);

        addPlugin(command);

        addOther(command);

        addRepoConfiguration(command, awsRepoURL, repositoryId);

        addAssets(command, gav);

        return command;
    }

    private void addPlugin(List<String> command) {
        command.add("org.apache.maven.plugins:maven-deploy-plugin:3.1.1:deploy-file");
    }

    private void addAssets(List<String> command, MvnGAVAssets gav) {
        List<MavenAsset> assets = new ArrayList<>();
        List<String> files = new ArrayList<>();
        List<String> classifiers = new ArrayList<>();
        List<String> types = new ArrayList<>();
        boolean visitedTopJar = false;
        for (var asset : gav.assets()) {
            final String filename = asset.getFilename();
            switch (asset.getLabel()) {
                case SOURCES -> command.add("-Dsources="+ filename);
                case JAVADOC -> command.add("-Djavadoc="+ filename);
                case TOP_JAR -> {
                    visitedTopJar = true;
                    addGavOnCommandLine(command, asset);
                }
                case TOP_POM -> command.add("-DpomFile=" + filename);
                case JAR,OTHER -> {
                    assets.add(asset);
                    files.add(filename);
                    classifiers.add(asset.getMvnIdentifier().getClassifier() == null ? "" : asset.getMvnIdentifier().getClassifier());
                    types.add(asset.getMvnIdentifier().getType());
                }
            }
        }
        if (!visitedTopJar) {
            MavenAsset scapegoat = gav.assets().getLast();;
            if (!files.isEmpty()) {
                scapegoat = assets.removeLast();
                files.removeLast();
                classifiers.removeLast();
                types.removeLast();
            }

            addGavOnCommandLine(command, scapegoat);
        }

        if (!files.isEmpty()) {
            command.add("-Dfiles="+String.join(",", files));
            command.add("-Dclassifiers="+String.join(",", classifiers));
            command.add("-Dtypes="+String.join(",", types));
        }
    }

    /**
     * At least one -Dfile must be present, if we do not have POM file, then we must also specify G:A:T:V:C
     * @param command
     * @param asset
     */
    private static void addGavOnCommandLine(List<String> command, MavenAsset asset) {
        var gav = asset.getMvnIdentifier();
        command.add(MessageFormat.format("-Dfile={0}", asset.getFilename()));
        command.add(MessageFormat.format("-DgroupId={0}", gav.getGroupId()));
        command.add(MessageFormat.format("-DartifactId={0}", gav.getArtifactId()));
        command.add(MessageFormat.format("-Dpackaging={0}", gav.getType()));
        command.add(MessageFormat.format("-Dversion={0}", gav.getVersionString()));
        if (gav.getClassifier() != null)
            command.add(MessageFormat.format("-Dclassifier={0}", gav.getClassifier()));
    }

    private void addOther(List<String> command) {
        command.addAll(List.of(
            "-DgeneratePom=false",
            "-DretryFailedDeploymentCount=3",
            "--batch-mode"));
    }

    private void addRepoConfiguration(List<String> command, String awsRepoURL, String repositoryId) {
        command.addAll(List.of(
            "-Durl="+awsRepoURL,
            "-DrepositoryId="+repositoryId,
            "-Paws"));
    }

    private void addSettings(List<String> command, String settingsXmlPath) {
        command.add("--settings=" + settingsXmlPath);
    }

    private CompletableFuture<Integer> processHandle(List<String> command,
                                                     Consumer<String> stdoutLineConsumer,
                                                     Consumer<String> stderrLineConsumer,
                                                     Map<String, Supplier<String>> extraEnvVars,
                                                     Path workingDirectory,
                                                     int permits) {
        try {
            processSemaphore.acquire();
            return processHandleInternal(command,
                stdoutLineConsumer,
                stderrLineConsumer,
                extraEnvVars,
                workingDirectory,
                permits).whenComplete((ign, thr) -> processSemaphore.release());
        } catch (InterruptedException e) {
            processSemaphore.release();
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<Integer> processHandleInternal(
        List<String> command,
        Consumer<String> stdoutLineConsumer,
        Consumer<String> stderrLineConsumer,
        Map<String, Supplier<String>> extraEnvVars,
        Path workingDirectory,
        int permits) {
        ProcessBuilder builder = new ProcessBuilder(command);

        if (workingDirectory != null) {
            if (!Files.isDirectory(workingDirectory)) {
                throw new IllegalArgumentException("Specified path is not a directory or doesn't exist");
            }
            builder.directory(workingDirectory.toFile());
        }

        Map<String, String> resolvedEnvs = extraEnvVars.entrySet()
            .stream()
            .collect(toMap(Map.Entry::getKey, entry -> entry.getValue().get()));

        builder.environment().putAll(resolvedEnvs);

        var futures = new CopyOnWriteArrayList<CompletableFuture>();

        var processStartFuture = executor.supplyAsync(permits, () -> {
            try {
                Process start = builder.start();
                log.info("Starting process.");
                var stdoutReader = CompletableFuture.runAsync(() -> {
                    try (var stdout = start.inputReader()) {
                        String line;
                        while ((line = stdout.readLine()) != null) {
                            stdoutLineConsumer.accept(line);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, regularExecutor);

                var stderrReader = CompletableFuture.runAsync(() -> {
                    try (var stderr = start.errorReader()) {
                        String line;
                        while ((line = stderr.readLine()) != null) {
                            stderrLineConsumer.accept(line);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, regularExecutor);

                futures.add(stdoutReader);
                futures.add(stderrReader);

                return start.waitFor();
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        futures.add(processStartFuture);

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenCombine(processStartFuture, (ign, status) -> status);
    }

    public CompletableFuture<Void> ultraPin() {
        var pinner = executor.runAsync(1, () -> {
            synchronized (new Object()) {
                try {
                    log.info("Pinning");
                    Thread.sleep(1000);
                    log.info("Unpinning");
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        return pinner;
    }
}
