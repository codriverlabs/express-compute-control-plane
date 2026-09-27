package ai.codriverlabs.ecp.api.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.regions.Region;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * JAX-RS ClientRequestFilter that signs outgoing requests with AWS SigV4.
 * Use with Quarkus REST Client via {@code @RegisterProvider(SigV4ClientRequestFilter.class)}.
 *
 * <p>Configuration: set system properties or environment variables:
 * <ul>
 *   <li>{@code ecp.sigv4.region} — AWS region (default: AWS_REGION env; no fallback beyond
 *       that — see below)</li>
 *   <li>{@code ecp.sigv4.service} — signing service name (default: execute-api)</li>
 * </ul>
 *
 * <p>Credentials are resolved via the standard AWS SDK default chain
 * (env vars, ~/.aws/credentials, IMDS, ECS container credentials, SSO) and re-resolved on every
 * request, since {@link DefaultCredentialsProvider} caches internally and refreshes ahead of
 * expiry — this filter must not cache a resolved credential snapshot itself.
 *
 * <p>Entities that are not already {@code String} or {@code byte[]} are serialized with Jackson
 * and the resulting bytes replace the request entity, so the bytes hashed into the signature are
 * guaranteed to be the exact bytes sent on the wire.
 */
public class SigV4ClientRequestFilter implements ClientRequestFilter {

    private static final AwsV4HttpSigner SIGNER = AwsV4HttpSigner.create();

    private final AwsCredentialsProvider credentialsProvider;
    private final Region region;
    private final String service;
    private final ObjectMapper objectMapper;

    public SigV4ClientRequestFilter() {
        this(resolveRegion(), System.getProperty("ecp.sigv4.service", "execute-api"));
    }

    public SigV4ClientRequestFilter(String region, String service) {
        this(region, service, new ObjectMapper());
    }

    /**
     * @param objectMapper must be configured identically to the JAX-RS provider's mapper —
     *                      if the two disagree on null handling or property naming, the bytes
     *                      hashed into the signature diverge from the bytes actually sent.
     */
    public SigV4ClientRequestFilter(String region, String service, ObjectMapper objectMapper) {
        this(DefaultCredentialsProvider.builder().reuseLastProviderEnabled(true).build(),
                region, service, objectMapper);
    }

    /**
     * Package-visible constructor for tests that must not depend on the machine's real
     * credential chain.
     */
    SigV4ClientRequestFilter(AwsCredentialsProvider credentialsProvider, String region,
                              String service, ObjectMapper objectMapper) {
        this.credentialsProvider = credentialsProvider;
        this.region = Region.of(region);
        this.service = service;
        this.objectMapper = objectMapper;
    }

    private static String resolveRegion() {
        String configured = System.getProperty("ecp.sigv4.region", System.getenv("AWS_REGION"));
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "No AWS region configured for SigV4 signing. Set the 'ecp.sigv4.region' "
                            + "system property or the AWS_REGION environment variable.");
        }
        return configured;
    }

    @Override
    public void filter(ClientRequestContext requestContext) {
        try {
            URI uri = requestContext.getUri();
            String method = requestContext.getMethod();

            byte[] payload = materializeEntity(requestContext);

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

            var signedRequestBuilder = software.amazon.awssdk.http.auth.spi.signer.SignRequest
                    .builder(credentialsProvider.resolveCredentials())
                    .request(sdkRequestBuilder.build())
                    .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, service)
                    .putProperty(AwsV4HttpSigner.REGION_NAME, region.id());

            if (payload.length > 0) {
                signedRequestBuilder.payload(() -> new ByteArrayInputStream(payload));
            }

            var signed = SIGNER.sign(signedRequestBuilder.build());

            signed.request().headers().forEach((name, values) -> {
                if (!name.equalsIgnoreCase("Host")) {
                    values.forEach(value -> requestContext.getHeaders().putSingle(name, value));
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign request with SigV4: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the exact bytes to sign. For entities that are not already {@code String} or
     * {@code byte[]}, serializes the entity and replaces it on the request context so the
     * signed bytes and the bytes the {@code MessageBodyWriter} sends can never diverge.
     */
    private byte[] materializeEntity(ClientRequestContext requestContext) {
        if (!requestContext.hasEntity()) {
            return new byte[0];
        }
        Object entity = requestContext.getEntity();
        if (entity instanceof byte[] bytes) {
            return bytes;
        }
        if (entity instanceof String string) {
            return string.getBytes(StandardCharsets.UTF_8);
        }
        try {
            byte[] serialized = objectMapper.writeValueAsBytes(entity);
            requestContext.setEntity(serialized);
            return serialized;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize the request entity of type "
                    + entity.getClass().getName() + " for SigV4 signing", e);
        }
    }
}
