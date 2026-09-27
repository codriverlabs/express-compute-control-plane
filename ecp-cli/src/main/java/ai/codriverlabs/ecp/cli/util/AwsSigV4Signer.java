package ai.codriverlabs.ecp.cli.util;

import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;

/**
 * AWS SigV4 signer backed by an {@link AwsCredentialsProvider}.
 * Handles env vars, ~/.aws/credentials, EC2 instance profile (IMDS), ECS, SSO, etc.
 *
 * <p>Credentials are resolved on every {@code sign(...)} call, not cached at
 * {@code create()} time. {@link DefaultCredentialsProvider} already caches internally and
 * refreshes ahead of expiry, so this is a field read on the common path, not a fresh STS
 * round trip. Caching the resolved {@code AwsCredentials} instead of the provider would work
 * for a short-lived CLI invocation but silently sign with expired credentials after roughly
 * an hour in any longer-lived process (e.g. an MCP server) built on this class.
 */
public class AwsSigV4Signer {

    private final AwsCredentialsProvider credentialsProvider;
    private final Region region;
    private final Aws4Signer signer = Aws4Signer.create();

    AwsSigV4Signer(AwsCredentialsProvider credentialsProvider, Region region) {
        this.credentialsProvider = credentialsProvider;
        this.region = region;
    }

    /** Test-only convenience constructor around a fixed set of credentials. */
    AwsSigV4Signer(AwsCredentials credentials, Region region) {
        this(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(credentials),
                region);
    }

    /**
     * Creates a signer backed by the standard AWS SDK default credentials chain.
     *
     * @throws IllegalStateException if no credentials can be resolved from the default chain.
     *     Previously this returned {@code null} on any failure, which led every call site to
     *     silently send unsigned requests; that produced an opaque 403 that read like an IAM
     *     permissions problem rather than "no credentials on this machine."
     */
    public static AwsSigV4Signer create(String region) {
        AwsCredentialsProvider provider = DefaultCredentialsProvider.builder()
                .reuseLastProviderEnabled(true)
                .build();
        try {
            // Resolve once up front so misconfiguration is reported at create() time, with the
            // same actionable message every call site already surfaces via its existing
            // try/catch — not as a signature failure deep inside an HTTP call.
            provider.resolveCredentials();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "No AWS credentials found; run 'aws sso login' or set AWS_PROFILE.", e);
        }
        return new AwsSigV4Signer(provider, Region.of(region));
    }

    public void sign(HttpRequest.Builder builder, String method, URI uri,
                     String body, String service) {
        sign(builder, method, uri, body, service, "application/json", java.util.Map.of());
    }

    public void sign(HttpRequest.Builder builder, String method, URI uri,
                     String body, String service, String contentType) {
        sign(builder, method, uri, body, service, contentType, java.util.Map.of());
    }

    public void sign(HttpRequest.Builder builder, String method, URI uri,
                     String body, String service, String contentType,
                     java.util.Map<String, String> extraHeaders) {
        byte[] payload = body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];

        var sdkRequestBuilder = SdkHttpFullRequest.builder()
            .method(SdkHttpMethod.fromValue(method))
            .uri(uri)
            .putHeader("Content-Type", contentType);
        extraHeaders.forEach(sdkRequestBuilder::putHeader);
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                String[] kv = pair.split("=", 2);
                sdkRequestBuilder.putRawQueryParameter(kv[0], kv.length > 1 ? kv[1] : "");
            }
        }
        if (payload.length > 0) {
            sdkRequestBuilder.contentStreamProvider(() -> new ByteArrayInputStream(payload));
        }

        var signed = signer.sign(sdkRequestBuilder.build(),
            Aws4SignerParams.builder()
                .awsCredentials(credentialsProvider.resolveCredentials())
                .signingRegion(region)
                .signingName(service)
                .build());

        signed.headers().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("Host")) {
                values.forEach(value -> builder.setHeader(name, value));
            }
        });
    }
}
