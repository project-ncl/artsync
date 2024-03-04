package org.jboss.pnc.artsync.pnc;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import org.jboss.pnc.artsync.config.ServiceApplicationConfig;

import java.net.URI;

@ConfigMapping(prefix = "pnc")
public interface PncApplicationConfig extends ServiceApplicationConfig {

    @WithName("endpoint-uri")
    URI pncURI();

    @WithDefault("200")
    @WithName("page-size")
    int pageSize();
}
