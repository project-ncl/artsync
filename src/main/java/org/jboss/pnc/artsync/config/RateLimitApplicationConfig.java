package org.jboss.pnc.artsync.config;

import java.time.Duration;

public interface RateLimitApplicationConfig {

    int rateOfRequests();

    /**
     * @link https://en.wikipedia.org/wiki/ISO_8601#Durations
     * @return
     */
    Duration timeConstraint();

    Duration requestTimeout();
}
