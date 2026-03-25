package org.jboss.pnc.artsync.model;


import lombok.Getter;
import lombok.experimental.SuperBuilder;
import org.jboss.pnc.enums.RepositoryType;
import org.jboss.pnc.artsync.model.hibernate.BuildStat;
import org.jboss.pnc.dto.TargetRepository;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.MessageFormat;
import java.util.Base64;

import static java.text.MessageFormat.format;

@Getter
@SuperBuilder(toBuilder = true)
public final class GPAsset extends Asset {

    public static final int AWS_CHARACTER_LIMIT = 255;
    public static final String AWS_API_DOWNLOAD_TEMPLATE = "/v1/package/version/asset?format=generic&domain={0}&repository={1}&namespace={2}&package={3}&version={4}&asset={5}";
    // Namespace == BUILD ID
    private final String namespace = getProcessingBuildID().buildID;

    // Package Name == Origin DOMAIN
    private final String packageName = getDomain();

    // Package Version == Path in Base64 (or plain if character limit of 255 reached)
    private final String packageVersion = computePackageVersion();

    public GPAsset(String pncIdentifier, String artifactId, String filename, long size, String md5, String sha1, String sha256, URI downloadURI, URI originURI, TargetRepository sourceRepository, String originBuildID, BuildStat processingBuildID) {
        super(computeIdentifier(pncIdentifier, processingBuildID, originURI, sha256), artifactId, RepositoryType.GENERIC_PROXY, filename, size, md5, sha1, handleSha256(pncIdentifier, sha256), downloadURI, originURI, sourceRepository, originBuildID, processingBuildID);
    }

    public static String handleSha256(String identifier, String sha256) {
        return switch (sha256) {
            // DB artifact is missing SHA -> fallback to identifier
            case String sha when sha.isBlank() -> parseShaFromIdentifier(identifier);
            case null -> parseShaFromIdentifier(identifier);

            // 99.99% case everything is fine
            default -> sha256;
        };
    }

    private static String parseShaFromIdentifier(String identifier) {
        return switch (identifier) {
            case String ident when ident.contains("|") -> ident.split("\\|")[1];
            case null, default -> "";
        };
    }

    @Override
    public String getPackageVersionString() {
        return getNamespace() + "|" + getPackageName() + "|" + getPackageVersion();
    }

    @Override
    public String getPackageString() {
        return getPackageVersionString();
    }

    /**
     * Identifier must be unique per each GP artifact.
     *
     * It's important because 'identifier' is used in processed cache. If GP artifact is in cache with the same
     * 'identifier', it would be skipped on next build's GP artifact if matched. We have tons of the same GP artifacts
     * with the same 'identifier', therefore we need to add information from which build it was produced.
     *
     * Most memory effective way for tries (implementation of processed cache) is to append buildID at the end instead
     * of the beginning.
     */
    public static String computeIdentifier(String pncIdentifier, BuildStat processingBuildID, URI originUrl, String sha256) {
        return switch (pncIdentifier) {
            // FORMAT== <<domain+path>>|<<sha256>>|<<build-id>>
            // first 2 parts are in the native identifier
            case String s when s.contains("|") -> pncIdentifier+"|"+processingBuildID.getBuildID();

            // 1. Some GProxy artifacts have maven-like identifiers by mistake, fallback to the originUrl
            // 2. There's also a case with no checksums, which suuuucks
            case String s when !s.contains("|") -> sha256 != null
                    ? originUrl.toString() + '|' + sha256 + '|' + processingBuildID.getBuildID()
                    : originUrl.toString() + "|NULL|"+processingBuildID.getBuildID();
            case null -> throw new IllegalArgumentException("PNC Generic Proxy identifier is null");
            default -> throw new IllegalStateException("Unreachable");
        };
    }

    private String getDomain() {
        return parseUrl().getHost();
    }

    private URI parseUrl() {
        String[] idenParts = getIdentifier().split("\\|", 2);
        try {
            return new URI(idenParts[0]);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Generic proxy identifier doesn't contain domain: " + getIdentifier(), e);
        }
    }

    private String computePackageVersion() {
        URI uri = parseUrl();

        // Can be either already in Base64 or plain path
        String fullPath = uri.getRawPath();

        if (fullPath.startsWith("/")) {
            // WASN'T IN BASE64
            String base64path = Base64.getEncoder().encodeToString(fullPath.getBytes()).replace("=","");

            String plainPath = fullPath;
            return handleCharacterLimit(base64path, plainPath);
        }

        // handle BASE64 Paths and super edge case for no starting "/"
        try {
            // WAS IN BASE64
            String plainPath = new String(Base64.getDecoder().decode(fullPath));

            String base64path = fullPath;
            return handleCharacterLimit(base64path, plainPath);
        } catch (IllegalArgumentException e) {
            // WASN'T IN BASE64
            String base64path = Base64.getEncoder().encodeToString(fullPath.getBytes()).replace("=","");

            String plainPath = fullPath;
            return handleCharacterLimit(base64path, plainPath);
        }
    }

    private static String handleCharacterLimit(String base64path, String plainPath) {
        // AWS has limit of 255 characters
        if (base64path.length() <= AWS_CHARACTER_LIMIT) {
            return base64path;
        }

        // fallback to the plain path if its length is less than character limit
        if (plainPath.length() <= AWS_CHARACTER_LIMIT) {
            return plainPath;
        }

        // trim the path :(
        return plainPath.substring(0, AWS_CHARACTER_LIMIT);
    }

    public String generateDeployUrlFrom(String awsRepositoryUrl, String domain, String repositoryId) {
        String pathToReplace = URI.create(awsRepositoryUrl).getRawPath();
        String awsDownloadEndpoint = format(AWS_API_DOWNLOAD_TEMPLATE, domain, repositoryId, namespace, packageName, packageVersion, getFilename());
        int idx = awsRepositoryUrl.lastIndexOf(pathToReplace);

        return awsRepositoryUrl.substring(0, idx).concat(awsDownloadEndpoint);
    }
}
