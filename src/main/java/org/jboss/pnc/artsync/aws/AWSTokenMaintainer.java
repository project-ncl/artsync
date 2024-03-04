package org.jboss.pnc.artsync.aws;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.pnc.artsync.pnc.Result;

import java.time.Instant;
import java.util.concurrent.Semaphore;

@ApplicationScoped
public class AWSTokenMaintainer {

    private final Semaphore lock = new Semaphore(1, true);

    private String token = null;

    private Instant expirationTimestamp = null;
    
    private final Long tokenDuration;
    
    private final AWSClient client;

    private final static double preemptiveRatioFactor =  1d / 3;

    public AWSTokenMaintainer(AWSClient client, AWSApplicationConfig config) {
        this.client = client;
        this.tokenDuration = config.tokenDuration().getSeconds();
    }

    public String getToken() {
        if (shouldRegenerateToken()) {
            regenerateToken();
        }
        return token;
    }

    private void regenerateToken() {
        try {
            lock.acquire();

            // recheck for threads which were waiting in critical section before token was generated
            if (!shouldRegenerateToken()) {
                return;
            }

            var temporaryToken = client.getTemporaryToken(tokenDuration).join();

            switch (temporaryToken) {
                case Result.Success(var res) -> {
                    token = res.authorizationToken();
                    expirationTimestamp = res.expiration();
                }
                case Result.Error e -> {
                    throw new IllegalStateException("Cannot retrieve AWS Temp Token. Error:" + e);
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            lock.release();
        }



    }

    private boolean shouldRegenerateToken() {
        return token == null || expiredOrCloseToExpiration();
    }

    private boolean expiredOrCloseToExpiration() {
        long tSecondsDifference = (expirationTimestamp.toEpochMilli() - Instant.now().toEpochMilli())/1000;
        return ((double) tSecondsDifference / tokenDuration) < preemptiveRatioFactor;
    }
}
