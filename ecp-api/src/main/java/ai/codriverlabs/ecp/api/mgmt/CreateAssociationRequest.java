package ai.codriverlabs.ecp.api.mgmt;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateAssociationRequest(
        @JsonProperty("namespace") String namespace,
        @JsonProperty("serviceAccount") String serviceAccount,
        @JsonProperty("roleArn") String roleArn) {
}
