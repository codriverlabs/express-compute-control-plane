package ai.codriverlabs.ecp.api.tenant;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Contract for tenant (cluster) provisioning and registration.
 * Authentication: AWS SigV4 (service=lambda) via Lambda Function URL.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface TenantApi {

    @POST
    @Path("/tenants")
    Response createTenant(CreateTenantRequest request);

    @GET
    @Path("/tenants/{tenantId}")
    Response describeTenant(@PathParam("tenantId") String tenantId);

    @DELETE
    @Path("/tenants/{tenantId}")
    Response deleteTenant(@PathParam("tenantId") String tenantId);

    @POST
    @Path("/tenants/{tenantId}/stop")
    Response stopTenant(@PathParam("tenantId") String tenantId);

    @POST
    @Path("/tenants/{tenantId}/resume")
    Response resumeTenant(@PathParam("tenantId") String tenantId);

    @POST
    @Path("/clusters")
    Response registerCluster(RegisterClusterRequest request);
}
