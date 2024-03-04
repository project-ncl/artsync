package org.jboss.pnc.artsync.model;

import java.time.ZonedDateTime;

public record AssetUpload<T extends Asset>(T asset,
                                           String deployedUrl,
                                           String awsRepository,
                                           ZonedDateTime uploadTime) {
}
