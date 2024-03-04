package org.jboss.pnc.artsync.model.hibernate;

import org.jboss.pnc.artsync.model.UploadResult;
import org.jboss.pnc.artsync.model.UploadResult.Error.AWSError;
import org.jboss.pnc.artsync.model.UploadResult.Error.GenericError;
import org.jboss.pnc.artsync.model.UploadResult.Error.IndyError;

public enum Category {
    RECOVER,
    IGNORE,
    MANUAL_INTERVENTION,
    UNRECOVERABLE;

    @SuppressWarnings("rawtypes")
    public static Category fromError(UploadResult.Error error) {
        return switch (error) {
            case AWSError.Conflict err -> IGNORE;
            case IndyError.NotFound err -> UNRECOVERABLE;
            case GenericError.CorruptedData err -> UNRECOVERABLE;
            case AWSError.ConnectionError err -> RECOVER;
            case AWSError.InvalidToken err -> RECOVER;
            case GenericError.Timeout err -> RECOVER;
            case AWSError.RateLimitExceeded err -> RECOVER;
            case AWSError.ServerError err -> RECOVER;
            case IndyError.SSLError err -> MANUAL_INTERVENTION;
            case AWSError.QuotaExceeded err -> MANUAL_INTERVENTION;
            case GenericError.Skipped err -> MANUAL_INTERVENTION;
            case GenericError.MissingUpload err -> MANUAL_INTERVENTION;
            case GenericError.UnknownError err -> MANUAL_INTERVENTION;
            case GenericError.MissingRepositoryMapping err -> MANUAL_INTERVENTION;
            case GenericError.UncaughtException err -> MANUAL_INTERVENTION;
            case GenericError.Invalidated err -> MANUAL_INTERVENTION;
            case IndyError.ServerError serverError -> MANUAL_INTERVENTION;
        };
    }
}
