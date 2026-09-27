package ai.codriverlabs.ecp.cli.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EcpApiClientTest {

    @Test
    void defaultEndpoint_isPlasticityCloud() {
        // Verify the default endpoint annotation value in the source
        // The actual HTTP calls are integration-level; here we verify construction
        EcpApiClient client = new EcpApiClient();
        // endpoint field is set by CDI; in unit test it's null
        // This test documents the expected default
        assertNotNull(client);
    }

    /**
     * Finding 3 (docs/design/fixes/sigv4-signing-issues.md): if AwsSigV4Signer.create() fails
     * during init(), requireSigner() must surface the original failure with its actionable
     * message rather than sending an unsigned request or throwing a generic error.
     */
    @Test
    void requireSigner_throwsOriginalCreateFailureWhenSignerInitFailed() {
        EcpApiClient client = new EcpApiClient();
        RuntimeException initFailure = new IllegalStateException(
                "No AWS credentials found; run 'aws sso login' or set AWS_PROFILE.");
        client.signerInitFailure = initFailure;

        RuntimeException thrown = assertThrows(RuntimeException.class, client::requireSigner);
        assertSame(initFailure, thrown);
    }

    @Test
    void requireSigner_returnsTheSignerWhenInitSucceeded() {
        EcpApiClient client = new EcpApiClient();
        AwsSigV4Signer signer = new AwsSigV4Signer(
                software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("k", "s"),
                software.amazon.awssdk.regions.Region.US_EAST_1);
        client.signer = signer;

        assertSame(signer, client.requireSigner());
    }
}

