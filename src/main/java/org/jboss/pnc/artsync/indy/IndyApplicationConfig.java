package org.jboss.pnc.artsync.indy;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;
import org.jboss.pnc.artsync.config.ServiceApplicationConfig;

import java.net.URI;

@ConfigMapping(prefix = "indy")
public interface IndyApplicationConfig extends ServiceApplicationConfig {

    @WithName("endpoint-uri")
    URI indyURI();
}
