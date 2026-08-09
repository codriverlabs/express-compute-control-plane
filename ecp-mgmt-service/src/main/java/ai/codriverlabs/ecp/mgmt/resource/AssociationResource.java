package ai.codriverlabs.ecp.mgmt.resource;

import ai.codriverlabs.ecp.api.mgmt.AssociationApi;
import ai.codriverlabs.ecp.api.mgmt.CreateAssociationRequest;
import ai.codriverlabs.ecp.mgmt.service.DynamoDbAssociationService;
import ai.codriverlabs.ecp.mgmt.service.DynamoDbClusterService;
import ai.codriverlabs.ecp.mgmt.spi.WorkloadIdentityRouter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

@Path("/clusters/{clusterName}/workload-identities")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AssociationResource implements AssociationApi {

    private static final Logger LOG = Logger.getLogger(AssociationResource.class);

    @Inject DynamoDbAssociationService associationService;
    @Inject DynamoDbClusterService clusterService;
    @Inject WorkloadIdentityRouter router;
    @Context ContainerRequestContext ctx;

    @POST
    @Override
    public Response createAssociation(@PathParam("clusterName") String clusterName,
                                       CreateAssociationRequest request) {
        try {
            if (!verifyClusterOwnership(clusterName))
                return error(404, "NotFoundException", "Cluster not found: " + clusterName);
            if (request == null) return error(400, "InvalidParameterException", "Request body is required");
            Map<String, String> result = router.resolve(clusterName).createAssociation(
                clusterName, request.namespace(), request.serviceAccount(), request.roleArn());
            return Response.status(201).entity(result).build();
        } catch (IllegalArgumentException e) {
            return error(400, "InvalidParameterException", e.getMessage());
        } catch (IllegalStateException e) {
            return error(409, "ConflictException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Create association error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    @GET
    @Override
    public Response listAssociations(@PathParam("clusterName") String clusterName,
                                      @QueryParam("namespace") String namespace,
                                      @QueryParam("serviceAccount") String serviceAccount) {
        try {
            if (!verifyClusterOwnership(clusterName))
                return error(404, "NotFoundException", "Cluster not found: " + clusterName);
            List<Map<String, String>> result = router.resolve(clusterName).listAssociations(
                clusterName, namespace, serviceAccount);
            return Response.ok(Map.of("associations", result)).build();
        } catch (Exception e) {
            LOG.errorf("List associations error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    @GET
    @Path("/{associationId}")
    @Override
    public Response describeAssociation(@PathParam("clusterName") String clusterName,
                                         @PathParam("associationId") String associationId) {
        try {
            if (!verifyClusterOwnership(clusterName))
                return error(404, "NotFoundException", "Cluster not found: " + clusterName);
            Map<String, String> result = router.resolve(clusterName).describeAssociation(clusterName, associationId);
            if (result == null) return error(404, "NotFoundException", "Association not found: " + associationId);
            return Response.ok(result).build();
        } catch (Exception e) {
            LOG.errorf("Describe association error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    @DELETE
    @Path("/{associationId}")
    @Override
    public Response deleteAssociation(@PathParam("clusterName") String clusterName,
                                       @PathParam("associationId") String associationId) {
        try {
            if (!verifyClusterOwnership(clusterName))
                return error(404, "NotFoundException", "Cluster not found: " + clusterName);
            router.resolve(clusterName).deleteAssociation(clusterName, associationId);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return error(404, "NotFoundException", e.getMessage());
        } catch (Exception e) {
            LOG.errorf("Delete association error: %s", e.getMessage());
            return error(500, "InternalServerException", "Internal server error");
        }
    }

    private boolean verifyClusterOwnership(String clusterName) {
        String callerArn = (String) ctx.getProperty("callerArn");
        if (callerArn == null) return true;
        try {
            String ownerArn = clusterService.getOwnerArn(clusterName);
            return ownerArn == null || callerArn.equals(ownerArn);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Response error(int status, String code, String message) {
        return Response.status(status).entity(Map.of("__type", code, "message", message)).build();
    }
}
