package ai.codriverlabs.ecp.api.tenant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Unified create-cluster request. Mode is inferred server-side:
 * <ul>
 *   <li>jwks + issuer present → self-managed registration</li>
 *   <li>No jwks → managed provisioning (EC2 + EKS-D)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateClusterRequest(
        @JsonProperty("clusterName") String clusterName,
        // Managed mode fields
        @JsonProperty("arch") String arch,
        @JsonProperty("ec2PricingModel") String ec2PricingModel,
        @JsonProperty("k8sVersion") String k8sVersion,
        @JsonProperty("assignElasticIp") Boolean assignElasticIp,
        @JsonProperty("diskSizeGb") Integer diskSizeGb,
        @JsonProperty("sshCidr") String sshCidr,
        // Self-managed mode fields (presence triggers self-managed)
        @JsonProperty("jwks") String jwks,
        @JsonProperty("issuer") String issuer,
        // Cluster type (MANAGED, SELF_MANAGED, EKS_NATIVE, ECS)
        @JsonProperty("clusterType") String clusterType,
        // Provider-specific metadata
        @JsonProperty("eksClusterName") String eksClusterName,
        @JsonProperty("ecsClusterName") String ecsClusterName,
        @JsonProperty("clusterArn") String clusterArn,
        @JsonProperty("region") String region,
        @JsonProperty("taskRoleArn") String taskRoleArn) {
}
