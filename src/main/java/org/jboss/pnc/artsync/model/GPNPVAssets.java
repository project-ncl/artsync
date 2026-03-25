package org.jboss.pnc.artsync.model;

import java.util.List;

public final class GPNPVAssets extends VersionAssets<GPAsset> {

    // '<<domain>>|<<deploy-path>>|<<build-id>>'
    private final String versionedIdentifier;

    // '<<build-id>>|<<domain>>'
    private final String namespaceProject;

    public GPNPVAssets(List<GPAsset> assets) {
        super(assets);
        this.versionedIdentifier = verifyAssets(assets);
        this.namespaceProject = assets.getFirst().getPackageVersionString();
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

    public String projectIdentifier() {
        return namespaceProject;
    }

    @Override
    public String prettyPrint() {
        return "";
    }
}
