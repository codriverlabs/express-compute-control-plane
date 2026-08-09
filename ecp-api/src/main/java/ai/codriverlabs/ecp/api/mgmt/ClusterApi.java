package ai.codriverlabs.ecp.api.mgmt;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Contract for cluster management.
 * Authentication: AWS SigV4 (service=execute-api) via API Gateway.
 */
@Path("/clusters")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface ClusterApi {

    @GET
    Response listClusters();

    @GET
    @Path("/{name}")
    Response describeCluster(@PathParam("name") String name);

    @PUT
    @Path("/{name}/jwks")
    Response updateJwks(@PathParam("name") String name, UpdateJwksRequest request);

    @DELETE
    @Path("/{name}")
    Response deregisterCluster(@PathParam("name") String name);
}
