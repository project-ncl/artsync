package org.jboss.pnc.artsync.model;

import lombok.Getter;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;
import org.commonjava.atlas.maven.ident.ref.SimpleArtifactRef;
import org.jboss.pnc.enums.RepositoryType;
import org.jboss.pnc.artsync.model.hibernate.BuildStat;
import org.jboss.pnc.dto.TargetRepository;

import java.net.URI;
import java.util.Set;

@Getter
@SuperBuilder(toBuilder = true)
@Slf4j
public final class MavenAsset extends Asset {

    private final SimpleArtifactRef mvnIdentifier = computeMvn(getIdentifier());
    private final Label label = computeLabel(mvnIdentifier);
    public static final Set<String> uncommonTypes = Set.of("exe", "xsd", "xjb", "yml", "properties", "json", "zip",
        "tar.gz", "ear", "war", "txt", "tar.bz2", "xml", "signature", "kar", "empty", "html", "pdf", "cfg", "rar",
        "xml.gz", "yaml", "wsdl", "jdocbook-style", "js", "key", "so", "target");


    public MavenAsset(String identifier, String artifactId, String filename, long size, String md5, String sha1, String sha256, URI downloadURI, TargetRepository sourceRepository, String originBuildID, BuildStat processingBuildID) {
        super(identifier, artifactId, RepositoryType.MAVEN, filename, size, md5, sha1, sha256, downloadURI, sourceRepository, originBuildID, processingBuildID);
    }

    public static SimpleArtifactRef computeMvn(String identifier) {
        String[] parts = identifier.split(":");
        return switch (Integer.valueOf(parts.length)) {
            // 3 parts should be illegal?
//            case Integer i when i.equals(3) -> new SimpleArtifactRef(parts[0], parts[1], parts[2], null, null);
            case Integer i when i.equals(4) -> new SimpleArtifactRef(parts[0], parts[1], parts[3], parts[2], null);
            case Integer i when i.equals(5) -> new SimpleArtifactRef(parts[0], parts[1], parts[3], parts[2], parts[4]);
            default -> throw new IllegalArgumentException("Illegal identifier format: " + identifier);
        };
    }
    private Label computeLabel(SimpleArtifactRef mvnIdentifier) {
        return switch (mvnIdentifier.getType()) {
            case "pom" -> Label.TOP_POM;
            case "jar" -> switch (mvnIdentifier.getClassifier()) {
                    case "sources" -> Label.SOURCES;
                    case "javadoc" -> Label.JAVADOC;
                    case null -> Label.TOP_JAR;
                    default -> Label.JAR;
                };
            case null -> throw new IllegalArgumentException("Maven Type cannot be null");
            case String s when uncommonTypes.contains(s)
                -> Label.OTHER;
            default ->  {
                log.error("New Classifier '{}' encountered. Will this cause an issue? Full identifier: '{}'",
                    mvnIdentifier.getType(),
                    getIdentifier());
                yield Label.OTHER;
            }
        };
    }


    public String generateDeployUrlFrom(String awsRepositoryUrl) {
        String path = getDownloadURI().getPath();
        return awsRepositoryUrl + path.substring(path.indexOf(mvnIdentifier.getArtifactId()));
    }

    @Override
    public String getPackageVersionString() {
        return mvnIdentifier.asProjectVersionRef().toString();
    }

    @Override
    public String getPackageString() {
        return mvnIdentifier.asProjectRef().toString();
    }
}
