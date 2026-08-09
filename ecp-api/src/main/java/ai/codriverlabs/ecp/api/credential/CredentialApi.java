package ai.codriverlabs.ecp.api.credential;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Contract for the credential exchange service.
 * Called by the in-cluster auth-proxy. Wire-compatible with the EKS Pod Identity Agent.
 *
 * <p>Authentication: proxy sends a Bearer token in the Authorization header
 * (cluster's service account token). The request body contains the pod's projected SA token.
 */
@Path("/clusters")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface CredentialApi {

    @POST
    @Path("/{clusterName}/assets")
    Response assumeRoleForPodIdentity(
            @PathParam("clusterName") String clusterName,
            @HeaderParam("Authorization") String proxyAuthorization,
            AssumeRoleRequest request);
}
