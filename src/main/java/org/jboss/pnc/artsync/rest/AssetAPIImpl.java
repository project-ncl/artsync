package org.jboss.pnc.artsync.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.jboss.pnc.artsync.model.hibernate.AssetEntry;
import org.jboss.pnc.artsync.model.hibernate.Category;
import org.jboss.pnc.artsync.model.hibernate.Paged;
import org.jboss.pnc.artsync.rest.api.AssetAPI;
import org.jboss.pnc.enums.RepositoryType;

import java.time.ZonedDateTime;

@ApplicationScoped
public class AssetAPIImpl implements AssetAPI {
    @Override
    public AssetEntry getSpecific(long id) {
        return AssetEntry.findById(id);
    }

    @Override
    public Paged<AssetEntry> getAll(Pagination page,
                                    String identifier,
                                    RepositoryType type,
                                    boolean errors,
                                    String since,
                                    Category category,
                                    String errorType) {
        ZonedDateTime sinceParsed = null;
        if (since != null) {
            sinceParsed = ZonedDateTime.parse(since);
        }

        return AssetEntry.getAllFiltered(page.toPage(),
            identifier,
            type,
            errors,
            sinceParsed,
            category,
            errorType,
            null,
            null,
            null);
    }

    @Override
    public Response markErrorFixed(long id) {
        if (AssetEntry.markFixed(id)) {
            return Response.ok().build();
        } else {
            throw new BadRequestException("Already marked or doesn't exist.");
        }
    }
}
