package org.jboss.pnc.artsync.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import org.jboss.pnc.api.enums.BuildType;
import org.jboss.pnc.artsync.model.hibernate.BuildStat;
import org.jboss.pnc.dto.TargetRepository;
import org.jboss.pnc.enums.RepositoryType;

import java.net.URI;
import java.util.Objects;

@AllArgsConstructor
@Getter
@SuperBuilder(toBuilder = true)
public abstract sealed class Asset permits MavenAsset, NpmAsset, GPAsset {
    private final String identifier;
    private final String artifactID;
    private final RepositoryType type;
    private final String filename;
    private final long size;
    private final String md5;
    private final String sha1;
    private final String sha256;
    private final URI downloadURI;
    private final TargetRepository sourceRepository;
    private final String originBuildID;
    private final BuildStat processingBuildID;

    public abstract String getPackageVersionString();
    public abstract String getPackageString();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Asset asset)) return false;
        return Objects.equals(identifier, asset.identifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identifier);
    }

    @Override
    public String toString() {
        return getPackageVersionString();
    }
}
