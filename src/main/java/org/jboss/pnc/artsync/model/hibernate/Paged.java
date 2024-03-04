package org.jboss.pnc.artsync.model.hibernate;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Collection;
import java.util.List;

@AllArgsConstructor
@Getter
public class Paged<T extends PanacheEntity> {
    private final int pageIndex;

    private final int pageSize;

    private final int totalPages;

    private final long totalEntities;

    private final Collection<T> contents;

    public static <S extends PanacheEntity> Paged<S> paginate(Page page, PanacheQuery<S> query) {
        query.page(page);
        int totalPages = query.pageCount();
        long totalEntities = query.count();
        int pageIndex = page.index;
        int pageSize = page.size;

        return new Paged<>(pageIndex, pageSize, totalPages, totalEntities, query.list());
    }

    public static <S extends PanacheEntity> Paged<S> emptyPage(Page page) {
        return new Paged<>(page.index, page.size, 0, 0, List.of());
    }
}
