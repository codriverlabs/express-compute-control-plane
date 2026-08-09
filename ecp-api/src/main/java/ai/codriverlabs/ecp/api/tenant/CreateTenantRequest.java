package ai.codriverlabs.ecp.api.tenant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateTenantRequest(
        @JsonProperty("clusterName") String clusterName,
        @JsonProperty("managed") boolean managed,
        @JsonProperty("arch") String arch,
        @JsonProperty("ec2PricingModel") String ec2PricingModel,
        @JsonProperty("k8sVersion") String k8sVersion,
        @JsonProperty("diskSizeGb") Integer diskSizeGb,
        @JsonProperty("assignElasticIp") Boolean assignElasticIp,
        @JsonProperty("sshCidr") String sshCidr) {
}
