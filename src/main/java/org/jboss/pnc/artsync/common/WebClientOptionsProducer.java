package org.jboss.pnc.artsync.common;

import io.quarkus.rest.client.reactive.runtime.context.HttpClientOptionsContextResolver;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientOptionsConverter;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class WebClientOptionsProducer {

    @ConfigProperty(name = "quarkus.tls.trust-all", defaultValue = "false")
    Boolean trustAll;

    @Produces
    @ApplicationScoped
    WebClientOptions wco() {
        HttpClientOptions options = new HttpClientOptions();
        options.setTrustAll(trustAll);

        return new WebClientOptions(options);
    }
}
