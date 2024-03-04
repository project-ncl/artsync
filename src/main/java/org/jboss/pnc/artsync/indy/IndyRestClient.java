package org.jboss.pnc.artsync.indy;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import org.commonjava.indy.folo.dto.TrackedContentDTO;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "indy-rest")
@Produces("application/json")
@Path("/api/folo/admin")
public interface IndyRestClient {

    @Path("/{buildContentId}/record")
    @GET
    TrackedContentDTO getTrackingReport(@PathParam("buildContentId") String buildContentId);
}
