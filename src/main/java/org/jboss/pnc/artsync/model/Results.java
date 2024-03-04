package org.jboss.pnc.artsync.model;

import io.vertx.core.impl.ConcurrentHashSet;
import lombok.extern.slf4j.Slf4j;
import org.jboss.pnc.artsync.model.UploadResult.Success;
import org.jboss.pnc.artsync.model.hibernate.Category;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public record Results<E extends Asset>(List<Success<AssetUpload<E>>> successes,
                                       List<UploadResult.Error<E>> errors,
                                       Set<E> assets) {
    public boolean haveErrors() {
        return !errors().isEmpty();
    }

    public boolean haveCriticalErrors() {
        return !errors().isEmpty() && !errors.stream().allMatch(err -> err.category() == Category.IGNORE);
    }

    public Results() {
        this(new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(), new ConcurrentHashSet<>());
    }

    public boolean addSuccess(Success<AssetUpload<E>> success) {
        E asset = success.result().asset();

        if (!assets.add(asset)) {
            log.warn("Asset {} added to results twice.", asset.getIdentifier());

            var error = errors.stream().filter(err -> err.context().getIdentifier().equals(asset.getIdentifier())).findFirst();
            if (error.isEmpty()) {
                log.warn("Duplicate success for {} found. Ignoring: {}", success.result().asset().getIdentifier(), success);
                return false;
            }

            log.warn("Found success. Removing {} from errors. Previous error {}", error.get().context().getIdentifier(), error.get());
            errors.remove(error.get());
        }

        return successes.add(success);
    }

    public boolean addError(UploadResult.Error<E> error) {
        E asset = error.context();

        if (!assets.add(asset)) {
            log.warn("Asset {} added to results twice.", asset.getIdentifier());

            var success = successes.stream().filter(suc -> suc.result().asset().getIdentifier().equals(asset.getIdentifier())).findFirst();
            if (success.isPresent()) {
                log.warn("Success for {} found. Ignoring Error: {}", asset.getIdentifier(), error);
                return false;
            }
            log.warn("Duplicate error for {} found. Ignoring duplicate: {}.", asset.getIdentifier(), error);
        }

        return errors.add(error);
    }

    // force addSuccess for modification
    @Override
    public List<Success<AssetUpload<E>>> successes() {
        return Collections.unmodifiableList(successes);
    }

    // force addError for modification
    @Override
    public List<UploadResult.Error<E>> errors() {
        return Collections.unmodifiableList(errors);
    }

    @Override
    public Set<E> assets() {
        return Collections.unmodifiableSet(assets);
    }

    public boolean contains(E asset) {
        return assets.contains(asset);
    }
}
