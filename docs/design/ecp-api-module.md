# ecp-api module — shared API contract

## Summary

New module containing JAX-RS interface definitions for all three ECP services.
These interfaces serve as the single source of truth for the API contract and are consumed by:

- **Server implementations** (`ecp-credential-service`, `ecp-mgmt-service`, `ecp-tenant-service`) — `implements` the interface
- **ecp-cli / ecp-pro-cli** — uses the interfaces + request/response records as typed DTOs
- **express-compute-pro-mcp** — uses the interfaces via Quarkus `@RegisterRestClient` with a SigV4 `ClientRequestFilter`

## Interfaces

| Interface | Path | Service | Auth |
|-----------|------|---------|------|
| `CredentialApi` | `POST /clusters/{name}/assets` | ecp-credential-service | Bearer (proxy SA token) |
| `ClusterApi` | `/clusters`, `/clusters/{name}`, `/clusters/{name}/jwks` | ecp-mgmt-service | SigV4 (execute-api) |
| `AssociationApi` | `/clusters/{name}/workload-identities` | ecp-mgmt-service | SigV4 (execute-api) |
| `TenantApi` | `/tenants`, `/clusters` | ecp-tenant-service | SigV4 (lambda) |

## Dependencies

```
ecp-api → ecp-model (for shared records like TokenClaims)
ecp-api → jakarta.ws.rs-api (provided scope)
ecp-api → jackson-annotations (provided scope)
```

## Consumer usage

### Server (e.g., ecp-mgmt-service)

```java
@ApplicationScoped
public class ClusterResource implements ClusterApi {
    @Override
    public Response listClusters() { ... }
}
```

### MCP server (Quarkus REST Client)

```java
@RegisterRestClient(configKey = "ecp-mgmt")
@RegisterProvider(SigV4ClientRequestFilter.class)
public interface ClusterApiClient extends ClusterApi {
}
```

### CLI (DTOs only — existing HttpClient + SigV4 stays)

```java
// Use typed records instead of raw ObjectNode:
var request = new CreateAssociationRequest(namespace, serviceAccount, roleArn);
apiClient.post("/clusters/" + name + "/workload-identities", mapper.writeValueAsString(request));
```
