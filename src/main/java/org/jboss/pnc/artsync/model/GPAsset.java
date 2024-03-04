package org.jboss.pnc.artsync.model;


import lombok.Getter;
import lombok.experimental.SuperBuilder;
import org.jboss.pnc.enums.RepositoryType;
import org.jboss.pnc.artsync.model.hibernate.BuildStat;
import org.jboss.pnc.dto.TargetRepository;

import java.net.URI;

@Getter
@SuperBuilder(toBuilder = true)
public final class GPAsset extends Asset {

    public GPAsset(String identifier, String artifactId, String filename, long size, String md5, String sha1, String sha256, URI downloadURI, TargetRepository sourceRepository, String originBuildID, BuildStat processingBuildID) {
        super(identifier, artifactId, RepositoryType.GENERIC_PROXY, filename, size, md5, sha1, sha256, downloadURI, sourceRepository, originBuildID, processingBuildID);
    }

    @Override
    public String getPackageVersionString() {
        return getIdentifier() + ":" + getSourceRepository().getRepositoryPath();
    }

    @Override
    public String getPackageString() {
        return getIdentifier();
    }
}
