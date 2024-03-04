package org.jboss.pnc.artsync;

import com.googlecode.concurrenttrees.radix.ConcurrentRadixTree;
import com.googlecode.concurrenttrees.radix.node.concrete.DefaultCharArrayNodeFactory;
import com.googlecode.concurrenttrees.radix.node.concrete.voidvalue.VoidValue;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.pnc.artsync.model.hibernate.AssetEntry;
import org.jboss.pnc.artsync.model.hibernate.IdentifierView;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Readiness
@ApplicationScoped
public class ProcessedArtifactsCache implements HealthCheck {

    private final ConcurrentRadixTree<Object> cache = new ConcurrentRadixTree<>(new DefaultCharArrayNodeFactory());

    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicLong count = new AtomicLong(0);

    @Startup
    @Transactional
    public void initializeCache() {
        finished.set(false);
        long count;
        try {
            count = AssetEntry.countSuccessfulIdentifiers();
            this.count.set(count);
            if (count == 0) {
                return;
            }
        } catch (NoResultException e) {
            log.info("No identifiers found in DB.");
            return;
        }
        int pageSize;
        if (count < 50000) {
            pageSize = (int) count;
        } else if (count < 100000) {
            pageSize = (int) (count/10L);
        } else {
            pageSize = (int) (count/20L);
        }
        log.info("Processing {} identifiers with pageSize {}.", count, pageSize);
        PanacheQuery<IdentifierView> query = AssetEntry.getSuccesses();
        query.page(Page.ofSize(pageSize));

        var firstPage = query.list();
        firstPage.parallelStream().map(IdentifierView::getIdentifier).forEach(this::commitProcessed);
        while (query.hasNextPage()) {
            List<IdentifierView> page = query.nextPage().list();
            page.parallelStream().map(IdentifierView::getIdentifier).forEach(this::commitProcessed);
        }

        //finish
        finished.set(true);
    }

    public boolean shouldProcess(String identifier) {
        return cache.getValueForExactKey(identifier) == null;
    }

    public void commitProcessed(String identifier) {
        cache.putIfAbsent(identifier, VoidValue.SINGLETON);
    }

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder responseBuilder = HealthCheckResponse.named("Cache initialization");

        if (finished.get()) {
            responseBuilder.withData("Processed items", count.get());
            responseBuilder.up();
        } else {
            responseBuilder.down();
        }
        return responseBuilder.build();
    }
}
