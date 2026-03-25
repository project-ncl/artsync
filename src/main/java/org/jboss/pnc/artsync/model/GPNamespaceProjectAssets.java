package org.jboss.pnc.artsync.model;

import com.fasterxml.jackson.annotation.JsonGetter;
import lombok.Getter;

import java.util.List;

@Getter
public final class GPNamespaceProjectAssets extends ProjectAssets<GPNPVAssets, GPAsset> {

    private final String namespaceProjectPath;

    public GPNamespaceProjectAssets(List<GPNPVAssets> projectVersionAssets) {
        super(projectVersionAssets);
        this.namespaceProjectPath = verifyAssets(projectVersionAssets);
    }

    private String verifyAssets(List<GPNPVAssets> assets) {
        String namespaceProjectPath = assets.getFirst().projectIdentifier();
        boolean someAssetFromDifferentProject = assets.stream().anyMatch(ass -> !namespaceProjectPath.equals(ass.projectIdentifier()));
        if (someAssetFromDifferentProject) {
            throw new IllegalArgumentException("Asset doesn't match namespace and project (build + domain).");
        }
        return namespaceProjectPath;
    }

    @Override
    @JsonGetter
    public String projectIdentifier() {
        return namespaceProjectPath;
    }

    @Override
    public String toString() {
        return super.toString() + ", files: [" + getProjectVersionAssets().stream()
                .map(asss -> asss.assets().getFirst())
                .map(Asset::getFilename)
                .reduce((ass1, ass2) -> ass1 + "," + ass2)
                .orElse("NO-FILENAME")
                + "]";
    }
}
