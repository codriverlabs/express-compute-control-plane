package ai.codriverlabs.ecp.api.credential;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AssumeRoleRequest(
        @JsonProperty("token") String token) {
}
