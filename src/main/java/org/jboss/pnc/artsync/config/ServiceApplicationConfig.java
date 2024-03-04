package org.jboss.pnc.artsync.config;

public interface ServiceApplicationConfig {

    String serviceName();

    RateLimitApplicationConfig rateLimit();

    RetryApplicationConfig retry();
}
