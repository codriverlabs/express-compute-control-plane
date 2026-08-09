package ai.codriverlabs.ecp.api.mgmt;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Contract for workload identity association management.
 * Authentication: AWS SigV4 (service=execute-api) via API Gateway.
 * The GET endpoints also support in-cluster webhook auth (Bearer SA token).
 */
@Path("/clusters/{clusterName}/workload-identities")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface AssociationApi {

    @POST
    Response createAssociation(
            @PathParam("clusterName") String clusterName,
            CreateAssociationRequest request);

    @GET
    Response listAssociations(
            @PathParam("clusterName") String clusterName,
            @QueryParam("namespace") String namespace,
            @QueryParam("serviceAccount") String serviceAccount);

    @GET
    @Path("/{associationId}")
    Response describeAssociation(
            @PathParam("clusterName") String clusterName,
            @PathParam("associationId") String associationId);

    @DELETE
    @Path("/{associationId}")
    Response deleteAssociation(
            @PathParam("clusterName") String clusterName,
            @PathParam("associationId") String associationId);
}
