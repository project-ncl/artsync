package org.jboss.pnc.artsync.config;

import io.smallrye.config.WithDefault;

import java.time.Duration;

public interface RetryApplicationConfig {

    @WithDefault("false")
    boolean enabled();

    @WithDefault("10")
    int maxAttempts();

    @WithDefault("false")
    boolean exponentialBackoff();

    @WithDefault("PT0.500s")
    Duration interval();
}
