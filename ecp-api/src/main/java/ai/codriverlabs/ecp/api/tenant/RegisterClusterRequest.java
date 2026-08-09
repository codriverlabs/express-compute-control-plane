package ai.codriverlabs.ecp.api.tenant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request to register a cluster with the Express Compute control plane.
 * Used by both Community (EKS-D, k3s, microk8s) and PRO (EKS_NATIVE, ECS) registration flows.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterClusterRequest(
        @JsonProperty("clusterName") String clusterName,
        @JsonProperty("clusterType") String clusterType,
        @JsonProperty("issuer") String issuer,
        @JsonProperty("jwks") String jwks,
        @JsonProperty("region") String region,
        @JsonProperty("eksClusterName") String eksClusterName,
        @JsonProperty("ecsClusterName") String ecsClusterName,
        @JsonProperty("clusterArn") String clusterArn,
        @JsonProperty("k8sVersion") String k8sVersion,
        @JsonProperty("taskRoleArn") String taskRoleArn) {
}
