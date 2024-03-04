package org.jboss.pnc.artsync.model;

import org.jboss.pnc.artsync.model.hibernate.Category;

public sealed interface UploadResult<T> permits UploadResult.Error, UploadResult.Success {
    record Success<T>(T result) implements UploadResult<T> {}
    sealed interface Error<T> extends UploadResult<T> permits Error.AWSError, Error.GenericError, Error.IndyError {
        T context();

        default Category category() {
            return Category.fromError(this);
        }

        sealed interface IndyError<T> extends Error<T> permits IndyError.NotFound, IndyError.SSLError, IndyError.ServerError {
            record ServerError<T>(T context, String lines) implements IndyError<T> {}
            record NotFound<T>(T context, String uri) implements IndyError<T> {}
            record SSLError<T>(T context, String message) implements IndyError<T> {}
        }

        sealed interface AWSError<T> extends Error<T>
            permits AWSError.Conflict, AWSError.ConnectionError, AWSError.InvalidToken, AWSError.QuotaExceeded,
                AWSError.RateLimitExceeded, AWSError.ServerError {

            record InvalidToken<T>(T context) implements AWSError<T> {}
            record Conflict<T>(T context, String deployedUrl, String awsRepoUrl, java.time.ZonedDateTime uploadTime) implements AWSError<T> {}
            record RateLimitExceeded<T>(T context) implements AWSError<T> {}
            record QuotaExceeded<T>(T context) implements AWSError<T> {}
            record ServerError<T>(T context, String lines) implements AWSError<T> {}
            record ConnectionError<T>(T context, String message) implements AWSError<T> {}
        }

        sealed interface GenericError<T> extends Error<T>
            permits GenericError.CorruptedData, GenericError.Invalidated, GenericError.MissingRepositoryMapping,
                GenericError.MissingUpload, GenericError.Skipped, GenericError.Timeout, GenericError.UncaughtException,
                GenericError.UnknownError {

            record Timeout<T>(T context) implements GenericError<T>{}
            record MissingRepositoryMapping<T>(T context, String missingRepository) implements GenericError<T>{}
            record CorruptedData<T>(T context, String lines) implements GenericError<T>{}
            record UnknownError<T>(T context, String lines) implements GenericError<T>{}
            record UncaughtException<T>(T context, Throwable exception) implements GenericError<T>{}
            record MissingUpload<T>(T context, String lines) implements GenericError<T>{}
            record Skipped<T>(T context) implements GenericError<T>{}
            record Invalidated<T>(T context, String deployedUrl, String awsRepoUrl, java.time.ZonedDateTime uploadTime) implements GenericError<T>{}
        }

        /**
         * return nice class name
         * e.g GenericError.InvalidData, AWSError.Conflict
         * @return nice class name
         */
        default String niceClassName(){
            String strip = Error.class.getCanonicalName();
            return this.getClass()
                .getCanonicalName()
                .replace(strip + ".", "");
        };
    }
}
