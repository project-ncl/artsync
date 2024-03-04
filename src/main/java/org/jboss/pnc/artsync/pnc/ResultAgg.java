package org.jboss.pnc.artsync.pnc;

import java.util.List;

public record ResultAgg<T>(List<T> successes,
                           List<Result.Error> errors) {
    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }
}