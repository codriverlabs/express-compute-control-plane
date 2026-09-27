package ai.codriverlabs.ecp.api.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.io.OutputStream;
import java.net.URI;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link SigV4ClientRequestFilter}, pinned to the invariants described in
 * {@code docs/design/fixes/sigv4-signing-issues.md} (Finding 1): the bytes hashed into the
 * signature must be exactly the bytes sent, and a request with a body must not sign identically
 * to a request without one.
 */
class SigV4ClientRequestFilterTest {

    private static final String TEST_ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String TEST_SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final AwsCredentialsProvider TEST_CREDENTIALS_PROVIDER =
            StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(TEST_ACCESS_KEY, TEST_SECRET_KEY));

    private SigV4ClientRequestFilter filter;

    record SamplePojo(String name, int count) {
    }

    @BeforeEach
    void setUp() {
        filter = new SigV4ClientRequestFilter(TEST_CREDENTIALS_PROVIDER, "us-east-1",
                "execute-api", new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("ecp.sigv4.region");
    }

    private static String authorizationHeader(FakeClientRequestContext ctx) {
        Object value = ctx.getHeaders().getFirst("Authorization");
        return value == null ? null : value.toString();
    }

    @Test
    void producesADifferentSignatureForAPojoBodyThanForNoBody() {
        FakeClientRequestContext withBody =
                FakeClientRequestContext.post("https://api.example.com/clusters",
                        new SamplePojo("test-cluster", 3));
        FakeClientRequestContext withoutBody =
                FakeClientRequestContext.post("https://api.example.com/clusters", null);

        filter.filter(withBody);
        filter.filter(withoutBody);

        String withBodyAuth = authorizationHeader(withBody);
        String withoutBodyAuth = authorizationHeader(withoutBody);

        assertNotNull(withBodyAuth);
        assertNotNull(withoutBodyAuth);
        assertNotEquals(withBodyAuth, withoutBodyAuth,
                "signing a POJO body must not produce the same signature as signing no body at "
                        + "all -- this is exactly the empty-payload bug being regression-tested");
    }

    @Test
    void replacesThePojoEntityWithExactlyTheSerializedBytes() throws Exception {
        SamplePojo entity = new SamplePojo("test-cluster", 3);
        FakeClientRequestContext ctx =
                FakeClientRequestContext.post("https://api.example.com/clusters", entity);

        filter.filter(ctx);

        byte[] expected = new ObjectMapper().writeValueAsBytes(entity);
        assertTrue(ctx.getEntity() instanceof byte[]);
        assertEquals(new String(expected), new String((byte[]) ctx.getEntity()));
    }

    @Test
    void stringAndByteArrayEntitiesPassThroughUnchanged() {
        String body = "{\"already\":\"serialized\"}";
        FakeClientRequestContext ctx =
                FakeClientRequestContext.post("https://api.example.com/clusters", body);

        filter.filter(ctx);

        assertEquals(body, ctx.getEntity());
    }

    @Test
    void differentQueryStringsProduceDifferentSignatures() {
        FakeClientRequestContext a =
                FakeClientRequestContext.get("https://api.example.com/clusters?name=alpha");
        FakeClientRequestContext b =
                FakeClientRequestContext.get("https://api.example.com/clusters?name=beta");

        filter.filter(a);
        filter.filter(b);

        assertNotEquals(authorizationHeader(a), authorizationHeader(b));
    }

    @Test
    void missingRegionFailsFastNamingTheSetting() {
        System.clearProperty("ecp.sigv4.region");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("AWS_REGION") == null || System.getenv("AWS_REGION").isBlank(),
                "test requires AWS_REGION to be unset in the environment");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                SigV4ClientRequestFilter::new);
        assertTrue(ex.getMessage().contains("ecp.sigv4.region"));
        assertTrue(ex.getMessage().contains("AWS_REGION"));
    }

    @Test
    void hostHeaderFromTheSignerIsNotCopiedBackToTheRequest() {
        FakeClientRequestContext ctx = FakeClientRequestContext.get("https://api.example.com/clusters");

        filter.filter(ctx);

        assertNull(ctx.getHeaders().getFirst("Host"));
    }

    /**
     * Minimal fake of {@link ClientRequestContext} covering exactly the subset of methods
     * {@link SigV4ClientRequestFilter} calls. All other methods throw
     * {@link UnsupportedOperationException} so an accidental new dependency on the filter's
     * behavior surfaces immediately as a test failure rather than a silent no-op.
     */
    private static final class FakeClientRequestContext implements ClientRequestContext {

        private final URI uri;
        private final String method;
        private final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        private Object entity;

        private FakeClientRequestContext(URI uri, String method, Object entity) {
            this.uri = uri;
            this.method = method;
            this.entity = entity;
        }

        static FakeClientRequestContext get(String uri) {
            return new FakeClientRequestContext(URI.create(uri), "GET", null);
        }

        static FakeClientRequestContext post(String uri, Object entity) {
            return new FakeClientRequestContext(URI.create(uri), "POST", entity);
        }

        @Override
        public URI getUri() {
            return uri;
        }

        @Override
        public String getMethod() {
            return method;
        }

        @Override
        public MultivaluedMap<String, Object> getHeaders() {
            return headers;
        }

        @Override
        public boolean hasEntity() {
            return entity != null;
        }

        @Override
        public Object getEntity() {
            return entity;
        }

        @Override
        public void setEntity(Object entity) {
            this.entity = entity;
        }

        @Override
        public Object getProperty(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Collection<String> getPropertyNames() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setProperty(String name, Object object) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeProperty(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setUri(URI uri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setMethod(String method) {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.ws.rs.core.MultivaluedMap<String, String> getStringHeaders() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getHeaderString(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean containsHeaderString(String name, String valueSeparatorRegex,
                                             java.util.function.Predicate<String> valuePredicate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Date getDate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Locale getLanguage() {
            throw new UnsupportedOperationException();
        }

        @Override
        public MediaType getMediaType() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<MediaType> getAcceptableMediaTypes() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Locale> getAcceptableLanguages() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Cookie> getCookies() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Class<?> getEntityClass() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.lang.reflect.Type getEntityType() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setEntity(Object entity, java.lang.annotation.Annotation[] annotations,
                               MediaType mediaType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.lang.annotation.Annotation[] getEntityAnnotations() {
            throw new UnsupportedOperationException();
        }

        @Override
        public OutputStream getEntityStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setEntityStream(OutputStream outputStream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Client getClient() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Configuration getConfiguration() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abortWith(Response response) {
            throw new UnsupportedOperationException();
        }
    }
}
