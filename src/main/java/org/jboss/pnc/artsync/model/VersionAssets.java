package org.jboss.pnc.artsync.model;

import org.jboss.pnc.dto.TargetRepository;

import java.util.List;

public abstract sealed class VersionAssets<ASS extends Asset> permits MvnGAVAssets, NpmNVAssets, GPAssets {
    private final List<ASS> assets;

    public VersionAssets(List<ASS> assets) {
        if (assets == null || assets.isEmpty()) {
            throw new IllegalArgumentException("Assets are empty/null.");
        }
        this.assets = assets;
        verifyTargetRepositories(assets);
    }

    private void verifyTargetRepositories(List<ASS> assets) {
        var source = assets.getFirst().getSourceRepository();
        boolean equalSource = assets.stream()
            .map(Asset::getSourceRepository)
            .allMatch(assSource -> assSource.equals(source));
        if (!equalSource) {
            throw new IllegalArgumentException("Assets differ in Indy repositories.");
        }
    }

    public abstract String versionIdentifier();

    public List<ASS> assets() {
        return assets;
    }

    public abstract String prettyPrint();

    public TargetRepository getSourceRepository() {
        return assets.getFirst().getSourceRepository();
    }
}
