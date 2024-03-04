package org.jboss.pnc.artsync.model;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import org.commonjava.atlas.npm.ident.ref.NpmPackageRef;

import java.util.List;

@Getter
public final class NpmNVAssets extends VersionAssets<NpmAsset> {
    @JsonIgnore
    private final NpmPackageRef packageRef;

    public NpmNVAssets(List<NpmAsset> assets) {
        super(assets);
        this.packageRef = verifyAssets(assets);
    }

    private NpmPackageRef verifyAssets(List<NpmAsset> assets) {
        NpmPackageRef identifier = assets.getFirst().getNpmIdentifier();
        boolean someAssetFromDifferentNV = assets.stream().anyMatch(asset -> !identifier.equals(asset.getNpmIdentifier()));
        if (someAssetFromDifferentNV) {
            throw new IllegalArgumentException("Asset doesn't match N:V.");
        }

        return identifier;
    }

    @Override
    @JsonGetter
    public String versionIdentifier() {
        return packageRef.toString();
    }

    @Override
    public String prettyPrint() {
        return packageRef.getVersionString();
    }
}
