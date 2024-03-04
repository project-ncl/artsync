package org.jboss.pnc.artsync.pnc;

import org.jboss.pnc.artsync.pnc.Result.Error.ServerError.SystemError;
import org.jboss.pnc.artsync.pnc.Result.Success;

import java.util.Map;
import java.util.Objects;

public record ContextResultAgg<C, T>(Map<C, Success<T>> successes,
                                     Map<C, Error> errors) {
    public boolean hasError() {
        Objects.hashCode(new SystemError("asd"));
        return errors != null && !errors.isEmpty();
    }
}