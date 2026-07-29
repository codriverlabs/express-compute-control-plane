# Fix: Missing Ownership Check on Tenant Stop/Resume

## Problem

`POST /tenants/{id}/stop` and `POST /tenants/{id}/resume` do not verify that the caller owns the tenant. Any authenticated user with `execute-api:Invoke` permission can stop or resume another user's tenant.

All other tenant operations (`GET`, `DELETE`) already enforce ownership via:

```java
if (callerArn != null && item.ownerArn() != null && !callerArn.equals(item.ownerArn()))
    return error(404, "NotFoundException", "Tenant not found: " + id);
```

## Fix

Add the same ownership check to `stopTenant()` and `resumeTenant()` in `TenantResource.java`:

1. Extract `callerArn` from the request context (already available via `CallerIdentityFilter`)
2. Load the tenant item
3. Compare `callerArn` against `item.ownerArn()`
4. Return 404 if mismatch (do not reveal existence)

## Scope

- **File**: `ecp-tenant-service/src/main/java/ai/codriverlabs/ecp/tenant/resource/TenantResource.java`
- **Methods**: `stopTenant()`, `resumeTenant()`
- **Tests**: Add unit tests for ownership rejection on stop/resume

## Security Impact

Without this fix, a malicious or misconfigured caller can disrupt other users' workloads by stopping their tenant instances. This is a pre-GA blocker.
