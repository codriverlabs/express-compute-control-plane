package ai.codriverlabs.ecp.api.credential;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AssumeRoleResponse(
        @JsonProperty("credentials") Credentials credentials,
        @JsonProperty("assumedRoleUser") AssumedRoleUser assumedRoleUser,
        @JsonProperty("podIdentityAssociation") PodIdentityAssociation podIdentityAssociation,
        @JsonProperty("subject") Subject subject,
        @JsonProperty("audience") String audience) {

    public record Credentials(
            @JsonProperty("accessKeyId") String accessKeyId,
            @JsonProperty("secretAccessKey") String secretAccessKey,
            @JsonProperty("sessionToken") String sessionToken,
            @JsonProperty("expiration") long expiration) {
    }

    public record AssumedRoleUser(
            @JsonProperty("arn") String arn,
            @JsonProperty("assumeRoleId") String assumeRoleId) {
    }

    public record PodIdentityAssociation(
            @JsonProperty("associationArn") String associationArn,
            @JsonProperty("associationId") String associationId) {
    }

    public record Subject(
            @JsonProperty("namespace") String namespace,
            @JsonProperty("serviceAccount") String serviceAccount) {
    }
}
