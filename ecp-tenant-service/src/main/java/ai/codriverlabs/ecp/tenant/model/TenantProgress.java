package ai.codriverlabs.ecp.tenant.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * SSE event payload for GET /tenants/{id}/stream.
 * sshPrivateKey is populated only when state == "ready".
 */
@RegisterForReflection
public record TenantProgress(
    String state,
    String phase,
    int progress,
    String publicIp,
    long elapsed,
    String error,
    String sshPrivateKey
) {}
