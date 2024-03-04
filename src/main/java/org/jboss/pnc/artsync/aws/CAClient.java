package org.jboss.pnc.artsync.aws;

import io.netty.channel.EventLoopGroup;
import io.quarkus.amazon.common.runtime.AmazonClientCommonRecorder;
import io.quarkus.amazon.common.runtime.AmazonClientNettyTransportRecorder;
import io.quarkus.amazon.common.runtime.SdkBuildTimeConfig;
import io.quarkus.netty.MainEventLoopGroup;
import io.quarkus.runtime.RuntimeValue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Produces;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.services.codeartifact.CodeartifactAsyncClient;
import software.amazon.awssdk.services.codeartifact.CodeartifactAsyncClientBuilder;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

@ApplicationScoped
public class CAClient {

    private final AWSApplicationConfig config;

    private final ScheduledExecutorService scheduledExecutorService;

    private final EventLoopGroup eventLoop;


    public CAClient(AWSApplicationConfig config,
                    ScheduledExecutorService scheduledExecutorService,
                    @MainEventLoopGroup EventLoopGroup eventLoop) {
        this.config = config;
        this.scheduledExecutorService = scheduledExecutorService;
        this.eventLoop = eventLoop;
    }

    private static SdkBuildTimeConfig mockBuildTimeConfig() {
        return new SdkBuildTimeConfig() {
            @Override
            public Optional<List<String>> interceptors() {
                return Optional.empty();
            }

            @Override
            public Optional<Boolean> telemetry() {
                return Optional.empty();
            }
        };
    }

    @Produces
    @ApplicationScoped
    public CodeartifactAsyncClient createClient() {
        CodeartifactAsyncClientBuilder builder = CodeartifactAsyncClient.builder();
        NettyNioAsyncHttpClient.Builder httpConfig = NettyNioAsyncHttpClient.builder();

        // configure
        new AmazonClientCommonRecorder()
            .configure(new RuntimeValue<>(builder),
                new RuntimeValue<>(config.aws()),
                new RuntimeValue<>(config.sdkConfig()),
                CAClient::mockBuildTimeConfig,
                scheduledExecutorService,
            "codeartifact");

        // configure integration with internal netty
        var nettyConfigurer = new AmazonClientNettyTransportRecorder();
        nettyConfigurer.configureAsync("codeartifact", new RuntimeValue<>(config.http()));
        nettyConfigurer.configureNettyAsync(new RuntimeValue<>(httpConfig),
                () -> this.eventLoop,
                new RuntimeValue<>(config.http()));

        return builder.build();
    }
}
