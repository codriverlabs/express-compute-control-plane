package ai.codriverlabs.ecp.api.mgmt;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpdateJwksRequest(
        @JsonProperty("jwks") String jwks) {
}
