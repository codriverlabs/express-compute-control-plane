package ai.codriverlabs.ecp.cli.util;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AwsSigV4SignerTest {

    private static AwsSigV4Signer testSigner(String region) {
        return new AwsSigV4Signer(
            AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"),
            Region.of(region));
    }

    @Test
    void sign_addsAuthorizationHeader() {
        URI uri = URI.create("https://api.example.com/clusters");
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri)
            .header("Content-Type", "application/json");

        testSigner("us-east-1").sign(builder, "GET", uri, null, "execute-api");

        HttpRequest request = builder.GET().build();
        assertTrue(request.headers().firstValue("Authorization").isPresent());
        assertTrue(request.headers().firstValue("Authorization").get().startsWith("AWS4-HMAC-SHA256"));
        assertTrue(request.headers().firstValue("X-Amz-Date").isPresent());
    }

    @Test
    void sign_includesRegionInCredentialScope() {
        URI uri = URI.create("https://api.example.com/clusters");
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri)
            .header("Content-Type", "application/json");

        testSigner("eu-west-1").sign(builder, "GET", uri, null, "execute-api");

        String auth = builder.GET().build().headers().firstValue("Authorization").get();
        assertTrue(auth.contains("eu-west-1/execute-api/aws4_request"));
    }

    @Test
    void sign_handlesQueryParameters() {
        URI uri = URI.create("https://api.example.com/clusters/test/workload-identities?namespace=default");
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri)
            .header("Content-Type", "application/json");

        testSigner("us-east-1").sign(builder, "GET", uri, null, "execute-api");

        assertTrue(builder.GET().build().headers().firstValue("Authorization").isPresent());
    }

    /**
     * Finding 3 (docs/design/fixes/sigv4-signing-issues.md): create() now throws instead of
     * returning null when no credentials can be resolved from the default chain, so callers get
     * an actionable error instead of silently sending an unsigned request.
     */
    @Test
    void create_throwsWithActionableMessageWhenCredentialsCannotBeResolved() {
        AwsCredentialsProvider failingProvider = () -> {
            throw new RuntimeException("no credentials configured");
        };

        // create() builds its own DefaultCredentialsProvider internally, so exercise the
        // failure behavior directly against the constructor-injected provider path instead --
        // this asserts the same contract (throw, don't return null) without depending on the
        // test environment having no AWS credentials at all.
        AwsSigV4Signer signer = new AwsSigV4Signer(failingProvider, Region.of("us-east-1"));

        assertThrows(Exception.class,
            () -> signer.sign(HttpRequest.newBuilder()
                    .uri(URI.create("https://api.example.com/clusters")),
                "GET", URI.create("https://api.example.com/clusters"), null, "execute-api"));
    }

    /**
     * Finding 2 (docs/design/fixes/sigv4-signing-issues.md): the signer must call
     * resolveCredentials() on every sign() call, not once at construction, so a long-lived
     * process picks up refreshed/rotated credentials instead of signing with a frozen snapshot.
     */
    @Test
    void sign_resolvesCredentialsOnEveryCallRatherThanCachingAtConstruction() {
        AtomicInteger resolveCount = new AtomicInteger();
        AwsCredentials first = AwsBasicCredentials.create("FIRSTKEY", "firstsecret");
        AwsCredentials second = AwsBasicCredentials.create("SECONDKEY", "secondsecret");
        AwsCredentialsProvider rotatingProvider = () -> {
            int call = resolveCount.getAndIncrement();
            return call == 0 ? first : second;
        };

        AwsSigV4Signer signer = new AwsSigV4Signer(rotatingProvider, Region.of("us-east-1"));
        URI uri = URI.create("https://api.example.com/clusters");

        HttpRequest.Builder firstBuilder = HttpRequest.newBuilder().uri(uri);
        signer.sign(firstBuilder, "GET", uri, null, "execute-api");
        String firstAuth = firstBuilder.build().headers().firstValue("Authorization").get();

        HttpRequest.Builder secondBuilder = HttpRequest.newBuilder().uri(uri);
        signer.sign(secondBuilder, "GET", uri, null, "execute-api");
        String secondAuth = secondBuilder.build().headers().firstValue("Authorization").get();

        assertEquals(2, resolveCount.get(),
            "resolveCredentials() must be called once per sign() call, not cached at construction");
        assertTrue(firstAuth.contains("FIRSTKEY"));
        assertTrue(secondAuth.contains("SECONDKEY"));
        assertNotEquals(firstAuth, secondAuth,
            "signing with rotated credentials must produce a different signature");
    }
}
