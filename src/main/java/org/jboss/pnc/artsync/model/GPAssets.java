package org.jboss.pnc.artsync.model;

import java.util.List;

public final class GPAssets extends VersionAssets<GPAsset> {

    private final String versionedIdentifier;

    public GPAssets(List<GPAsset> assets) {
        super(assets);
        this.versionedIdentifier = verifyAssets(assets);
    }

    private String verifyAssets(List<GPAsset> assets) {
        String identifier = assets.getFirst().getIdentifier();
        boolean someAssetFromDifferentPV = assets.stream().anyMatch(asset -> !identifier.equals(asset.getIdentifier()));
        if (someAssetFromDifferentPV) {
            throw new IllegalArgumentException("Assets don't have equal project and version.");
        }
        return identifier;
    }

    @Override
    public String versionIdentifier() {
        return versionedIdentifier;
    }

    @Override
    public String prettyPrint() {
        return "";
    }
}
