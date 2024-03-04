package org.jboss.pnc.artsync.model;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import org.commonjava.atlas.maven.ident.ref.ProjectVersionRef;
import org.commonjava.atlas.maven.ident.ref.SimpleArtifactRef;
import org.commonjava.atlas.maven.ident.ref.SimpleProjectVersionRef;
import org.commonjava.atlas.maven.ident.ref.TypeAndClassifier;

import java.util.List;

@Getter
public final class MvnGAVAssets extends VersionAssets<MavenAsset>{

    @JsonIgnore
    private final ProjectVersionRef versionRef;

    public MvnGAVAssets(List<MavenAsset> assets) {
        super(assets);
        this.versionRef = verifyAssets(assets);
    }

    private ProjectVersionRef verifyAssets(List<MavenAsset> assets) {
        ProjectVersionRef identifier = assets.getFirst().getMvnIdentifier().asProjectVersionRef();
        boolean someAssetFromDifferentGAV = assets.stream().anyMatch(asset -> !identifier.equals(asset.getMvnIdentifier().asProjectVersionRef()));
        if (someAssetFromDifferentGAV) {
            throw new IllegalArgumentException("Asset doesn't match G:A:V.");
        }
        return identifier.asProjectVersionRef();
    }

    @Override
    @JsonGetter
    public String versionIdentifier() {
        return versionRef.toString();
    }

    @Override
    public String prettyPrint() {
        return versionRef.getVersionString() + " [" + assets().stream()
            .map(ass -> ass.getMvnIdentifier().getTypeAndClassifier().toString())
            .reduce((ass1, ass2) -> ass1 + ", " + ass2).orElse("MISSING") + "]";
    }


}
