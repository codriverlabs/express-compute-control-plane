package ai.codriverlabs.ecp.api.client;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * JAX-RS ClientRequestFilter that signs outgoing requests with AWS SigV4.
 * Use with Quarkus REST Client via {@code @RegisterProvider(SigV4ClientRequestFilter.class)}.
 *
 * <p>Configuration: set system properties or environment variables:
 * <ul>
 *   <li>{@code ecp.sigv4.region} — AWS region (default: AWS_REGION env or us-east-1)</li>
 *   <li>{@code ecp.sigv4.service} — signing service name (default: execute-api)</li>
 * </ul>
 *
 * <p>Credentials are resolved via the standard AWS SDK default chain
 * (env vars, ~/.aws/credentials, IMDS, ECS container credentials, SSO).
 */
public class SigV4ClientRequestFilter implements ClientRequestFilter {

    private final Aws4Signer signer = Aws4Signer.create();
    private final AwsCredentialsProvider credentialsProvider;
    private final Region region;
    private final String service;

    public SigV4ClientRequestFilter() {
        this.credentialsProvider = DefaultCredentialsProvider.builder()
                .reuseLastProviderEnabled(true)
                .build();
        String regionStr = System.getProperty("ecp.sigv4.region",
                System.getenv("AWS_REGION") != null ? System.getenv("AWS_REGION") : "us-east-1");
        this.region = Region.of(regionStr);
        this.service = System.getProperty("ecp.sigv4.service", "execute-api");
    }

    public SigV4ClientRequestFilter(String region, String service) {
        this.credentialsProvider = DefaultCredentialsProvider.builder()
                .reuseLastProviderEnabled(true)
                .build();
        this.region = Region.of(region);
        this.service = service;
    }

    @Override
    public void filter(ClientRequestContext requestContext) {
        try {
            AwsCredentials credentials = credentialsProvider.resolveCredentials();
            URI uri = requestContext.getUri();
            String method = requestContext.getMethod();

            byte[] payload = extractBody(requestContext);

            var sdkRequestBuilder = SdkHttpFullRequest.builder()
                    .method(SdkHttpMethod.fromValue(method))
                    .uri(uri)
                    .putHeader("Content-Type", "application/json");

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
                            .awsCredentials(credentials)
                            .signingRegion(region)
                            .signingName(service)
                            .build());

            signed.headers().forEach((name, values) -> {
                if (!name.equalsIgnoreCase("Host")) {
                    values.forEach(value -> requestContext.getHeaders().putSingle(name, value));
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign request with SigV4: " + e.getMessage(), e);
        }
    }

    private byte[] extractBody(ClientRequestContext requestContext) {
        if (!requestContext.hasEntity()) return new byte[0];
        Object entity = requestContext.getEntity();
        if (entity instanceof String s) return s.getBytes(StandardCharsets.UTF_8);
        if (entity instanceof byte[] b) return b;
        return new byte[0];
    }
}
