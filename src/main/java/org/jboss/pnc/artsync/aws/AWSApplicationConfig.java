package org.jboss.pnc.artsync.aws;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithParentName;
import org.jboss.pnc.artsync.config.ServiceApplicationConfig;

import java.time.Duration;

@ConfigMapping(prefix = "aws")
public interface AWSApplicationConfig extends ServiceApplicationConfig {

    // region key and credentials provider
    @WithParentName
    AwsConfig aws();

    // FIXME not currently used
    SdkConfig sdkConfig();

    HttpClientConfig http();

    // will get rounded up to SECONDS
    Duration tokenDuration();

    String domain();

    String owner();

    @WithDefault("false")
    Boolean dryRun();

    @WithDefault("20")
    int subprocessConcurrencyLimit();

    interface AwsConfig extends io.quarkus.amazon.common.runtime.AwsConfig {}
    interface SdkConfig extends io.quarkus.amazon.common.runtime.SdkConfig {}
    interface HttpClientConfig extends io.quarkus.amazon.common.runtime.AsyncHttpClientConfig {}
}
