package org.jboss.pnc.artsync.model;

import lombok.Getter;

import java.util.List;

@Getter
public abstract sealed class ProjectAssets<VER extends VersionAssets<ASS>, ASS extends Asset>
    permits MvnGAAssets, NpmProjectAssets, GPProjectAssets {

    private final List<VER> projectVersionAssets;

    protected ProjectAssets(List<VER> projectVersionAssets) {
        this.projectVersionAssets = projectVersionAssets;
    }

    public abstract String projectIdentifier();

    @Override
    public String toString() {
        return projectIdentifier() + " assets: " + projectVersionAssets.size();
    }
}
