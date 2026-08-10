package ai.codriverlabs.ecp.api.tenant;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Contract for the unified cluster lifecycle API (tenant-service).
 * Authentication: AWS SigV4 (service=lambda) via Lambda Function URL.
 *
 * <p>The server infers managed vs self-managed mode from the request body:
 * <ul>
 *   <li>jwks + issuer present → self-managed (register existing cluster)</li>
 *   <li>No jwks → managed (provision EC2 + EKS-D cluster)</li>
 * </ul>
 */
@Path("/clusters")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface ClusterLifecycleApi {

    @POST
    Response createCluster(CreateClusterRequest request);

    @GET
    @Path("/{name}")
    Response getCluster(@PathParam("name") String name);

    @DELETE
    @Path("/{name}")
    Response deleteCluster(@PathParam("name") String name);

    @POST
    @Path("/{name}/stop")
    Response stopCluster(@PathParam("name") String name);

    @POST
    @Path("/{name}/resume")
    Response resumeCluster(@PathParam("name") String name);
}
