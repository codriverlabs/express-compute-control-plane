package ai.codriverlabs.ecp.api.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link SigV4ClientRequestFilter} through a real JAX-RS client runtime (Jersey) and a
 * real HTTP server, instead of the hand-written {@code ClientRequestContext} double used in
 * {@link SigV4ClientRequestFilterTest}.
 *
 * <p>{@link SigV4ClientRequestFilterTest} proves the filter's own logic is internally
 * consistent. It does not prove that a <em>real</em> {@code MessageBodyWriter} writes the
 * {@code byte[]} entity {@link SigV4ClientRequestFilter#filter} substitutes in exactly as-is,
 * with no re-wrapping or re-serialization -- which is the specific JAX-RS ordering subtlety
 * Finding 1 was about in the first place. This test closes that gap by asserting the bytes a
 * real server actually receives on the wire are byte-for-byte identical to what the filter
 * signed.
 */
class SigV4ClientRequestFilterJaxRsIntegrationTest {

    private static final String TEST_ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String TEST_SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

    record SamplePojo(String clusterName, int replicaCount) {
    }

    private HttpServer server;
    private final BlockingQueue<CapturedRequest> captured = new ArrayBlockingQueue<>(4);

    private record CapturedRequest(byte[] body, String authorizationHeader, String contentType) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/clusters", exchange -> {
            byte[] body = readAll(exchange.getRequestBody());
            captured.add(new CapturedRequest(body,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type")));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toByteArray();
    }

    private String baseUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private SigV4ClientRequestFilter testFilter() {
        AwsCredentialsProvider provider = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(TEST_ACCESS_KEY, TEST_SECRET_KEY));
        return new SigV4ClientRequestFilter(provider, "us-east-1", "execute-api", new ObjectMapper());
    }

    @Test
    void realJaxRsClientSendsExactlyTheBytesTheFilterSigned() throws Exception {
        SamplePojo entity = new SamplePojo("test-cluster", 3);
        Client client = ClientBuilder.newClient().register(testFilter());

        Response response = client.target(baseUri() + "/clusters")
                .request(MediaType.APPLICATION_JSON)
                .post(Entity.entity(entity, MediaType.APPLICATION_JSON));
        response.close();
        client.close();

        CapturedRequest request = captured.poll(5, TimeUnit.SECONDS);
        assertNotNull(request, "server did not receive a request within the timeout");

        byte[] expectedBytes = new ObjectMapper().writeValueAsBytes(entity);
        assertArrayEquals(expectedBytes, request.body(),
                "the bytes a real server receives must be byte-for-byte identical to what "
                        + "SigV4ClientRequestFilter serialized and signed -- this is the exact "
                        + "JAX-RS ordering subtlety Finding 1 was about");
        assertNotNull(request.authorizationHeader());
        assertTrue(request.authorizationHeader().startsWith("AWS4-HMAC-SHA256"));
    }

    @Test
    void realJaxRsClientSendsNoBodyForAGetRequest() throws Exception {
        Client client = ClientBuilder.newClient().register(testFilter());

        Response response = client.target(baseUri() + "/clusters")
                .request(MediaType.APPLICATION_JSON)
                .get();
        response.close();
        client.close();

        CapturedRequest request = captured.poll(5, TimeUnit.SECONDS);
        assertNotNull(request, "server did not receive a request within the timeout");

        assertEquals(0, request.body().length);
        assertNotNull(request.authorizationHeader());
    }
}
