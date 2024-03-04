package org.jboss.pnc.artsync.rest;

import io.quarkus.panache.common.Page;
import jakarta.ws.rs.QueryParam;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.Objects;

@NoArgsConstructor
public final class Pagination {
    @QueryParam(value = "pIdx")
    public int pageIndex = 0;
    @QueryParam(value = "pSize")
    public int pageSize = 0;

    public Page toPage() {
        return new Page(pageIndex, pageSize);
    }

    //region Verbosness
    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (Pagination) obj;
        return this.pageIndex == that.pageIndex &&
            this.pageSize == that.pageSize;
    }

    @Override
    public int hashCode() {
        return Objects.hash(pageIndex, pageSize);
    }

    @Override
    public String toString() {
        return "Pagination[" +
            "pageIndex=" + pageIndex + ", " +
            "pageSize=" + pageSize + ']';
    }
    //endregion

}
