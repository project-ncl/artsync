package org.jboss.pnc.artsync.model;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.ws.rs.GET;
import lombok.Getter;
import org.commonjava.atlas.maven.ident.ref.ProjectRef;

import java.util.List;

@Getter
public final class MvnGAAssets extends ProjectAssets<MvnGAVAssets, MavenAsset> {
    @JsonIgnore
    private final ProjectRef projectRef;

    public MvnGAAssets(List<MvnGAVAssets> versions) {
        super(versions);
        this.projectRef = verifyVersions(versions);
    }

    private ProjectRef verifyVersions(List<MvnGAVAssets> versions) {
        ProjectRef identifier = versions.getFirst().getVersionRef().asProjectRef();
        boolean someAssetFromDifferentGA = versions.stream().anyMatch(version -> !identifier.equals(version.getVersionRef().asProjectRef()));
        if (someAssetFromDifferentGA) {
            throw new IllegalArgumentException("Asset doesn't match G:A.");
        }
        return identifier;
    }

    @Override
    @JsonGetter
    public String projectIdentifier() {
        return projectRef.toString();
    }

    @Override
    public String toString() {
        return super.toString() + " versions: " + getProjectVersionAssets().stream()
            .map(MvnGAVAssets::prettyPrint)
            .reduce((ass1, ass2) -> ass1 + ", " + ass2).orElse("none");
    }
}
