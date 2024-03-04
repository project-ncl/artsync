package org.jboss.pnc.artsync.model;

import lombok.Getter;
import lombok.experimental.SuperBuilder;
import org.commonjava.atlas.npm.ident.ref.NpmPackageRef;
import org.jboss.pnc.enums.RepositoryType;
import org.jboss.pnc.artsync.model.hibernate.BuildStat;
import org.jboss.pnc.dto.TargetRepository;

import java.net.URI;

@Getter
@SuperBuilder(toBuilder = true)
public final class NpmAsset extends Asset {
    private final NpmPackageRef npmIdentifier = NpmPackageRef.parse(getIdentifier());

    public NpmAsset(String identifier, String artifactId, String filename, long size, String md5, String sha1, String sha256, URI downloadURI, TargetRepository sourceRepository, String originBuildID, BuildStat processingBuildID) {
        super(identifier, artifactId, RepositoryType.NPM, filename, size, md5, sha1, sha256, downloadURI, sourceRepository, originBuildID, processingBuildID);
    }

    @Override
    public String getPackageVersionString() {
        // INIT INTERNAL VERSION BECAUSE IT'S STUPIDLY IMPLEMENTED
        npmIdentifier.getVersion();
        return npmIdentifier.toString();
    }

    @Override
    public String getPackageString() {
        return npmIdentifier.asNpmProjectRef().toString();
    }

    public String generateDeployUrlFrom(String awsRepositoryUrl) {
        String path = getDownloadURI().getPath();
        return awsRepositoryUrl + path.substring(path.indexOf(npmIdentifier.getName()));
    }

    public String getScope() {
        String[] scopedName = scopedName();
        if (scopedName.length == 2) {
            String scope = scopedName[0];
            // remove initial '@' from scope
            return scope.startsWith("@") ? scope.substring(1) : scope;
        }
        return null;
    }

    public String getUnscopedName() {
        String[] scopedName = scopedName();
        if (scopedName.length == 2) {
            return scopedName[1];
        }
        return scopedName[0];
    }

    private String[] scopedName() {
        return npmIdentifier.getName().split("/", 2);
    }
}
