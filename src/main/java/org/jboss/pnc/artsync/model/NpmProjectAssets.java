package org.jboss.pnc.artsync.model;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import org.commonjava.atlas.maven.ident.ref.ProjectRef;
import org.commonjava.atlas.npm.ident.ref.NpmPackageRef;
import org.commonjava.atlas.npm.ident.ref.NpmProjectRef;

import java.util.List;

@Getter
public final class NpmProjectAssets extends ProjectAssets<NpmNVAssets,NpmAsset> {
    @JsonIgnore
    private final NpmProjectRef projectRef;

    public NpmProjectAssets(List<NpmNVAssets> projectVersionAssets) {
        super(projectVersionAssets);
        this.projectRef = verifyVersions(projectVersionAssets);
    }

    private NpmProjectRef verifyVersions(List<NpmNVAssets> versions) {
        NpmProjectRef identifier = versions.getFirst().getPackageRef().asNpmProjectRef();
        boolean someAssetFromDifferentProject = versions.stream().anyMatch(version -> !identifier.equals(version.getPackageRef().asNpmProjectRef()));
        if (someAssetFromDifferentProject) {
            throw new IllegalArgumentException("Asset doesn't match Package name.");
        }
        return identifier;
    }

    @Override
    @JsonGetter
    public String projectIdentifier() {
        return projectRef.toString();
    }
}
