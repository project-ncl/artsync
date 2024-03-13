package org.jboss.pnc.artsync;

import io.quarkus.virtual.threads.VirtualThreads;
import jakarta.enterprise.context.ApplicationScoped;
import org.commonjava.atlas.maven.ident.ref.ArtifactRef;
import org.commonjava.atlas.maven.ident.ref.SimpleArtifactRef;
import org.commonjava.atlas.maven.ident.util.ArtifactPathInfo;
import org.commonjava.atlas.npm.ident.ref.NpmPackageRef;
import org.commonjava.atlas.npm.ident.util.NpmPackagePathInfo;
import org.commonjava.indy.folo.dto.TrackedContentEntryDTO;
import org.jboss.pnc.artsync.indy.IndyService;
import org.jboss.pnc.artsync.model.Asset;
import org.jboss.pnc.artsync.model.GPAsset;
import org.jboss.pnc.artsync.model.MavenAsset;
import org.jboss.pnc.artsync.model.NpmAsset;
import org.jboss.pnc.artsync.model.hibernate.BuildStat;
import org.jboss.pnc.artsync.pnc.PncService;
import org.jboss.pnc.dto.Artifact;
import org.jboss.pnc.dto.Build;
import org.jboss.pnc.enums.RepositoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static org.commonjava.indy.model.core.GenericPackageTypeDescriptor.GENERIC_PKG_KEY;
import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;
import static org.commonjava.indy.pkg.npm.model.NPMPackageTypeDescriptor.NPM_PKG_KEY;

@ApplicationScoped
public class BuildArtifactCollector {
    private static final Logger LOG = LoggerFactory.getLogger(PncService.class);
    private final PncService pnc;
    private final IndyService indy;
    private final ExecutorService executor;

    public BuildArtifactCollector(PncService pnc, IndyService indy, @VirtualThreads ExecutorService executor) {
        this.pnc = pnc;
        this.indy = indy;
        this.executor = executor;
    }

    public List<Asset> collectAssets(BuildStat build) {
        var pncArts = pnc.getArtifacts(build);
        if (pncArts.hasErrors()) {
            // TODO very bad stuff, technically should crash everything after
            LOG.error(pncArts.errors().toString());
            throw new RuntimeException();
        }

        var trackingReport = indy.getTrackingReport(build.getBuildID());
        if (trackingReport.hasErrors()) {
            // TODO not that critical but still bad
            LOG.error(trackingReport.errors().toString());
            throw new RuntimeException();
        }

        Map<String, ArtTuple> tuples = groupByIdentifier(pncArts.successes(), trackingReport.successes(), build);

        // NOTE: trust INDY filename more than the one in DB if present
        return convertToAssets(tuples);
    }

    public CompletableFuture<List<Asset>> collectAssetsAsync(BuildStat build) {
        return CompletableFuture.supplyAsync(() -> collectAssets(build), executor);
    }

    private List<Asset> convertToAssets(Map<String, ArtTuple> tuples) {
        List<Asset> uploads = new ArrayList<>(tuples.size());
        for (var artifact : tuples.values()) {
            // Avoid GProxy artifacts that were promoted to shared-imports
            if ((artifact.pnc().getIdentifier().startsWith("http://")
                || artifact.pnc().getIdentifier().startsWith("https://"))
                && artifact.pnc().getIdentifier().contains("|")
                && artifact.pnc().getTargetRepository().getRepositoryType() != RepositoryType.GENERIC_PROXY){
                // TODO recreate as GPROXY artifact
                LOG.error("GPROXY ARTIFACT acting as MAVEN id: {}, identifier: {}", artifact.pnc().getId(),
                    artifact.pnc().getIdentifier());
                continue;
            }

            uploads.add(convertToAsset(artifact));
        }
        return uploads;
    }

    private Asset convertToAsset(ArtTuple artifact) {
        return switch (artifact.pnc.getTargetRepository().getRepositoryType()) {
            case MAVEN -> convertToMavenAsses(artifact);
            case NPM -> convertToNpmAsses(artifact);
            case GENERIC_PROXY -> convertToGPAsses(artifact);
            case COCOA_POD, DISTRIBUTION_ARCHIVE -> throw new UnsupportedOperationException("Unknown repository type");
        };
    }

    private <C extends Asset,B extends Asset.AssetBuilder<C, B>> Asset.AssetBuilder<C, B> fillCommon(Asset.AssetBuilder<C, B> builder, ArtTuple artifact) {
        String filename = parseFilename(artifact);
        URI downloadURI = parseDownloadURI(artifact, filename);
        Build build = artifact.pnc().getBuild();
        return builder
            .size(artifact.pnc().getSize())
            .artifactID(artifact.pnc().getId())
            .filename(filename)
            .type(artifact.pnc().getTargetRepository().getRepositoryType())
            .identifier(artifact.pnc().getIdentifier())
            .md5(artifact.pnc().getMd5())
            .sha1(artifact.pnc().getSha1())
            .sha256(artifact.pnc().getSha256())
            .sourceRepository(artifact.pnc().getTargetRepository())
            .originBuildID(build != null ? build.getId() : null)
            .processingBuildID(artifact.processingBuildId())
            .downloadURI(downloadURI);
    }

    private static URI parseDownloadURI(ArtTuple artifact, String filename) {
        String publicUrl = artifact.pnc().getPublicUrl();
        if (publicUrl.contains(filename)) {
            return URI.create(publicUrl);
        }
        LOG.warn("Public URL does not equal indy deployed filename.\n url: {} file: {}", publicUrl, filename);
        if (artifact.indy().isPresent()) {
            // TODO? restructure download URI
//            String indyUrl = null;
            LOG.warn("OH SHIT, this shouldn't different be null {}, {}", filename, publicUrl);
            return null;
        }

        LOG.warn("OH SHIT, this shouldn't be different {}, {}", filename, publicUrl);
        return URI.create(publicUrl);
        //
    }

    private MavenAsset convertToMavenAsses(ArtTuple artifact) {
        MavenAsset mavenAsset = fillCommon(MavenAsset.builder(), artifact).build();

        if (!(
            Set.of("jar", "pom").contains(mavenAsset.getMvnIdentifier().getType())
            || MavenAsset.uncommonTypes.contains(mavenAsset.getMvnIdentifier().getType()))) {
            mavenAsset = reparseWithDeployPath(artifact, mavenAsset);
        }

        return mavenAsset;
    }

    private static MavenAsset reparseWithDeployPath(ArtTuple artifact, MavenAsset mavenAsset) {
        ArtifactRef pncIdent = MavenAsset.computeMvn(artifact.pnc().getIdentifier());
        ArtifactPathInfo pathInfo = ArtifactPathInfo.parse(artifact.pnc().getDeployPath());

        // reparse ident and use if it's better
        // do not override if the version does not have any numbers (artifacts without file-types are unparsable)
        if (pathInfo != null) {
            ArtifactRef deployIdent = pathInfo.getArtifact();
            if (deployIdent != null
                    && !deployIdent.equals(pncIdent)
                    && deployIdent.getVersionString().chars().anyMatch(Character::isDigit)) {
                LOG.warn("PNC/INDY Identifier mismatch. PNC: {} vs INDY: {}", pncIdent.toString(), deployIdent.toString());
                mavenAsset = mavenAsset.toBuilder().identifier(deployIdent.toString()).build();
            }
        }
        return mavenAsset;
    }

    private NpmAsset convertToNpmAsses(ArtTuple artifact) {
        return fillCommon(NpmAsset.builder(), artifact).build();
    }
    private GPAsset convertToGPAsses(ArtTuple artifact) {
        return fillCommon(GPAsset.builder(), artifact).build();
    }

    /**
     * Prefer Indy filenames because that's the actual path where the file resides. This avoids sometimes incorrect
     * filenames in PNC DB.
     *
     * If we didn't match Indy data, fallback to PNC DB
     *
     * @param artTuple pnc+indy art data
     * @return filename of what to upload
     */
    private String parseFilename(ArtTuple artTuple) {
        if (artTuple.indy().isPresent()) {
            String path = artTuple.indy().get().getPath();
            int filenameSlash = path.lastIndexOf('/');
            return path.substring(filenameSlash + 1);
        }

        return artTuple.pnc().getFilename();
    }


    private Map<String, ArtTuple> groupByIdentifier(List<Artifact> pncArts,
                                                    List<TrackedContentEntryDTO> trackingReport,
                                                    BuildStat processingBuildId) {
        Map<String, Artifact> artifactMap = new HashMap<>();
        Map<String, TrackedContentEntryDTO> entryDTOMap = new HashMap<>();

        pncArts.forEach(art -> {
            char lastChar = art.getIdentifier().charAt(art.getIdentifier().length()-1);
            // detect identifier errors like quarkus json issues
            if (art.getIdentifier().contains(".json")
                    && Character.isDigit(lastChar)
                    && ArtifactPathInfo.parse(art.getDeployPath()) != null) {
                ArtifactPathInfo gav = ArtifactPathInfo.parse(art.getDeployPath());
                String identifier = gav.getArtifact().toString();
                artifactMap.put(identifier, art.toBuilder().identifier(identifier).build());
            } else {
                artifactMap.put(art.getIdentifier(), art);
            }
        });
        trackingReport.forEach(art -> {
            var prev = entryDTOMap.put(computeIdentifier(art), art);
            if (prev != null) {
                switch (prev.getStoreKey().getName()) {
                    // workaround duplicate upload entries and leave the 'build-' version of the duplicates
                    case String s when s.contains("build-") -> entryDTOMap.put(computeIdentifier(prev), prev);
                    case String s when s.contains("pnc-builds") -> {}
                    default -> LOG.warn("Unknown duplicate in tracking report found: " + prev);
                }
            }
        });
//        Set<String> difference = new HashSet<>(entryDTOMap.keySet());
//        difference.removeAll(artifactMap.keySet());
//
//        Set<String> difference2 = new HashSet<>(artifactMap.keySet());
//        difference2.removeAll(entryDTOMap.keySet());
//
//        LOG.info(difference.toString());
        return artifactMap.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
            entry -> new ArtTuple(entry.getValue(), Optional.ofNullable(entryDTOMap.get(entry.getKey())), processingBuildId)));
    }

    private record ArtTuple(Artifact pnc, Optional<TrackedContentEntryDTO> indy, BuildStat processingBuildId) {};

    /**
     * Computes identifier string for an artifact. If the download path is valid for a package-type specific artifact it
     * creates the identifier accordingly.
     *
     * SHAMELESSLY COPIED FROM REPO-DRIVER
     *
     * @param transfer the download or upload that we want to generate identifier for
     * @return generated identifier
     */
    private String computeIdentifier(final TrackedContentEntryDTO transfer) {
        String identifier = null;
        String MAVEN_SUBSTITUTE_EXTENSION = ".empty";

        switch (transfer.getStoreKey().getPackageType()) {
            case MAVEN_PKG_KEY:
                ArtifactPathInfo pathInfo = ArtifactPathInfo.parse(transfer.getPath());

                if (pathInfo == null) {
                    // NCL-7238: handle cases where url has no file extension. we add the extension
                    // MAVEN_SUBSTITUTE_EXTENSION and see if that helps to parse the pathInfo. Otherwise this causes
                    // nasty artifact duplicates
                    pathInfo = ArtifactPathInfo.parse(transfer.getPath() + MAVEN_SUBSTITUTE_EXTENSION);
                }
                if (pathInfo != null) {
                    ArtifactRef aref = new SimpleArtifactRef(
                        pathInfo.getProjectId(),
                        pathInfo.getType(),
                        pathInfo.getClassifier());
                    identifier = aref.toString();
                }
                break;

            case NPM_PKG_KEY:
                NpmPackagePathInfo npmPathInfo = NpmPackagePathInfo.parse(transfer.getPath());
                if (npmPathInfo != null) {
                    NpmPackageRef packageRef = new NpmPackageRef(npmPathInfo.getName(), npmPathInfo.getVersion());
                    identifier = packageRef.toString();
                }
                break;

            case GENERIC_PKG_KEY:
                // handle generic downloads along with other invalid download paths for other package types
                break;

            default:
                // do not do anything by default
                LOG.warn(
                    "Package type {} is not handled by Indy repository session.",
                    transfer.getStoreKey().getPackageType());
                break;
        }

        if (identifier == null) {
            identifier = computeGenericIdentifier(
                transfer.getOriginUrl(),
                transfer.getLocalUrl(),
                transfer.getSha256());
        }

        return identifier;
    }

    private String computeGenericIdentifier(String originUrl, String localUrl, String sha256) {
        String identifier = originUrl;
        if (identifier == null) {
            // this is from/to a hosted repository, either the build repo or something like that.
            identifier = localUrl;
        }
        identifier += '|' + sha256;
        return identifier;
    }


}
