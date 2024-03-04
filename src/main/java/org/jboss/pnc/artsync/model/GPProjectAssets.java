package org.jboss.pnc.artsync.model;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;

import java.util.List;

@Getter
public final class GPProjectAssets extends ProjectAssets<GPAssets, GPAsset> {
    @JsonIgnore
    private final String projectIdentifier;

    public GPProjectAssets(List<GPAssets> projectVersionAssets) {
        super(projectVersionAssets);
        this.projectIdentifier = verifyAssets(projectVersionAssets);
    }

    private String verifyAssets(List<GPAssets> assets) {
        String identifier = assets.getFirst().assets().getFirst().getPackageString();
        boolean someAssetFromDifferentProject = assets.stream().anyMatch(asset -> !identifier.equals(asset.assets().getFirst().getPackageString()));
        if (someAssetFromDifferentProject) {
            throw new IllegalArgumentException("Asset doesn't match G:A:V.");
        }
        return identifier;
    }

    @Override
    @JsonGetter
    public String projectIdentifier() {
        return projectIdentifier;
    }
}
