# Fix: SigV4 Signing Issues in `ecp-api` Client Filter and `ecp-cli` Signer

## Status

**Implemented.** Findings 1–3 and Finding 4 (for `SigV4ClientRequestFilter`) are fixed, tested,
and verified end-to-end (unit tests, `mvn test` across all 11 modules, native `ecp-cli` build and
runtime smoke test). See [Outcome](#outcome) at the end of this document for what was actually
done, what was deliberately deferred, and why.

Reported while building an equivalent SigV4 client filter for a separate project
(`scaleout-build-maven-plugin`), which deliberately did not copy this implementation.
Source findings: `sigv4-signing-findings.md` / `sigv4-reference-design.md` (external report,
inspected against commit `f33b1f3`). Verified against current `main` on 2026-09-27 — all four
findings still reproduced exactly as described before this fix; nothing had been fixed yet.

None of this was a live outage. Finding 1 was dormant dead code; it would have become a breaking
bug the moment a long-lived client (the planned `docs/roadmap/ecp-mcp-server.md`) started using
`ecp-api`'s client-side classes.

## Problem

Four issues, in priority order (1 and 2 both block the MCP server; 1 is the only one that is an
outright correctness bug):

### Finding 1 — `SigV4ClientRequestFilter` signs an empty payload for POJO bodies (High)

`ecp-api/src/main/java/ai/codriverlabs/ecp/api/client/SigV4ClientRequestFilter.java`,
`extractBody()`:

```java
private byte[] extractBody(ClientRequestContext requestContext) {
    if (!requestContext.hasEntity()) return new byte[0];
    Object entity = requestContext.getEntity();
    if (entity instanceof String s) return s.getBytes(StandardCharsets.UTF_8);
    if (entity instanceof byte[] b) return b;
    return new byte[0];          // <-- any POJO lands here
}
```

SigV4 signs a SHA-256 hash of the request payload as part of the canonical request. The
signature is only valid if the bytes hashed at signing time are the bytes the server receives.

A JAX-RS `ClientRequestFilter` runs **before** the entity is serialized by the
`MessageBodyWriter`. At filter time, `getEntity()` returns the *object*, not its JSON. So for
any entity that is not already a `String` or `byte[]`, this signs a hash of zero bytes, while
the JAX-RS provider subsequently serializes the object and sends a real JSON body. The signed
bytes and the sent bytes diverge.

Result: `403 SignatureDoesNotMatch` (or `IncompleteSignature`) on every request with a body —
this includes all of `ecp-api`'s existing POJO request types: `CreateClusterRequest`,
`CreateAssociationRequest`, `UpdateJwksRequest`, `AssumeRoleRequest`.

**Why nothing is broken today:** the class is dead code. Verified on current `main`:
- No `@RegisterProvider(SigV4ClientRequestFilter.class)` anywhere in the repo.
- No references to `ecp.api.client` outside the file itself.
- `ecp-cli` does not depend on `ecp-api` at all — it has its own signer (Finding 2/3 below) and
  passes the same already-serialized `String` to both signing and `BodyPublishers.ofString(...)`,
  so its payload handling is correct by construction.
- `ecp-mgmt-service`, `ecp-tenant-service`, `ecp-credential-service` depend on `ecp-api` only as
  *servers* implementing the JAX-RS interfaces. Servers verify signatures; they do not sign
  outgoing requests.

**Why it matters now:** `docs/roadmap/ecp-mcp-server.md` plans an MCP server exposing
`create_tenant`, `register_cluster`, `create_association`, etc. as tools — a long-lived process
that is the natural first real consumer of `ecp-api`'s client filter, and every one of those
tool calls is a POST with a body. This is exactly the first case that turns the bug live.

### Finding 2 — `ecp-cli`'s signer resolves credentials once and caches them for the process lifetime (High for long-lived processes)

`ecp-cli/src/main/java/ai/codriverlabs/ecp/cli/util/AwsSigV4Signer.java`:

```java
private final AwsCredentials credentials;      // resolved value, not the provider

public static AwsSigV4Signer create(String region) {
    try {
        AwsCredentialsProvider provider = DefaultCredentialsProvider.builder()
                .reuseLastProviderEnabled(true)
                .build();
        AwsCredentials creds = provider.resolveCredentials();   // once, at construction
        return new AwsSigV4Signer(creds, Region.of(region));
    } catch (Exception e) {
        return null;
    }
}
```

The class javadoc is explicit and correct about the tradeoff for a CLI: *"Credentials are
resolved once at create() time. Per-call refresh is not needed for short-lived CLI sessions."*
That holds for the CLI today.

It does not hold for a long-lived process. SSO sessions, assumed-role sessions, and IMDS
credentials all expire — typically within an hour. A daemon or MCP server holding one
`AwsSigV4Signer` instance keeps signing with the credentials it resolved at startup and starts
returning `403` after roughly an hour of uptime, with nothing in the error pointing at credential
expiry.

**Recommended fix (per the source report): register the `AwsCredentialsProvider`, not an STS
client, and resolve credentials from it on every signing call.** `DefaultCredentialsProvider`
already caches internally and refreshes ahead of expiry, so calling `resolveCredentials()` per
request is a field read on the common path, not a fresh STS round trip. The change is: store the
provider (built once, with `reuseLastProviderEnabled(true)` so the chain is not re-probed every
call) instead of the resolved `AwsCredentials`, and call `provider.resolveCredentials()` inside
each `sign(...)` call rather than once at `create()` time.

### Finding 3 — unresolvable credentials cause silently unsigned requests (Medium)

`AwsSigV4Signer.create()` catches everything and returns `null`. Every call site in
`ecp-cli/.../util/EcpApiClient.java` guards with `if (signer != null)` (7 such guards), so when
credentials cannot be resolved, the request is sent **unsigned** instead of failing.

The failure then surfaces as an opaque `403 Forbidden` from AWS, which reads like an IAM
permissions problem. The actual cause — "no credentials on this machine" — is the one thing the
error does not say.

### Finding 4 — deprecated signer API (Low)

Both `SigV4ClientRequestFilter` and `AwsSigV4Signer` use
`software.amazon.awssdk.auth.signer.Aws4Signer` / `Aws4SignerParams`, which are deprecated in AWS
SDK for Java v2 (confirmed against the `auth-2.54.16.jar` class file's `Deprecated` attribute in
the source report). The current replacement is `AwsV4HttpSigner` in
`software.amazon.awssdk:http-auth-aws`. Note `SERVICE_SIGNING_NAME` lives on
`AwsV4FamilyHttpSigner` while `REGION_NAME` lives on `AwsV4HttpSigner` — different interfaces,
easy to mis-import. Low priority on its own; worth folding into the Finding 1 rewrite since that
code is already being touched.

### Two smaller notes carried over from the source report

- **Region default.** `SigV4ClientRequestFilter` falls back to `us-east-1` when neither
  `ecp.sigv4.region` nor `AWS_REGION` is set. A wrong-region signature fails with a signature
  error rather than a configuration error, which is harder to diagnose than a fail-fast message
  naming the missing setting.
- **`Authorization` header not excluded from the canonical headers.** The filter copies
  `requestContext.getStringHeaders()` into the request being signed and excludes only `Host`. If
  a request ever arrives already carrying an `Authorization` header (a retry through the same
  filter, or a caller that set one), it would be folded into the new signature.

## Root Cause

All four findings share the same underlying cause: the signing code was written and validated
against the one caller that happened to make it look correct (the CLI's short-lived,
string-body, always-fresh-credentials usage), and no test exists that would fail if the
sent-bytes-equal-signed-bytes invariant, the credential-refresh invariant, or the
fail-loud-on-missing-credentials invariant were broken. `SigV4ClientRequestFilter` in particular
was added with no consumer, so nothing ever exercised its POJO-body path.

## Fix

Priority order, matching the source report:

1. **Finding 1 + Finding 4 together, with a regression test**, in `SigV4ClientRequestFilter`.
   Serialize the entity inside the filter, sign those exact bytes, and write them back so the
   two cannot diverge:

   ```java
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
           requestContext.setEntity(serialized);   // sent bytes == signed bytes
           return serialized;
       } catch (Exception e) {
           throw new IllegalStateException("Failed to serialize the request entity of type "
                   + entity.getClass().getName() + " for SigV4 signing", e);
       }
   }
   ```

   This needs an `ObjectMapper` field on the filter, configured identically to the JAX-RS
   provider's — if the two disagree on null handling or property naming, the bytes diverge again,
   more subtly. Migrate signing itself from `Aws4Signer` to `AwsV4HttpSigner` at the same time,
   since this code is already being rewritten.

   Regression test: assert that a request **with** a body and one **without** produce
   **different** signatures. Under the current implementation both sign an empty payload, so
   their signatures are identical and the test fails — the cheapest possible guard against this
   regressing. Also assert `setEntity` was called with exactly
   `objectMapper.writeValueAsBytes(entity)`.

2. **Finding 2**, in `AwsSigV4Signer`. Store the `AwsCredentialsProvider` instead of the resolved
   `AwsCredentials`, and call `resolveCredentials()` inside `sign(...)` on every call instead of
   once at `create()`. Keep `reuseLastProviderEnabled(true)` on the provider so the credential
   chain itself is still only probed once; only the resolved credentials become per-call.

3. **Finding 3**, in the same class. Let `create()` propagate failures (or return a signer that
   throws on first use naming the credential chain) instead of returning `null` and letting
   `EcpApiClient` send unsigned requests. `No AWS credentials found; run 'aws sso login' or set
   AWS_PROFILE` is actionable; a bare `403` is not.

4. **The two smaller notes**, opportunistically alongside the above: fail fast when no region is
   configured rather than defaulting to `us-east-1`, and exclude both `Host` and `Authorization`
   from the headers copied into the signed request.

### Alternatives considered and rejected (per the source report)

- **`WriterInterceptor`** to capture serialized bytes instead of a `ClientRequestFilter`: the
  `Authorization` header must be set before the body is written, so this still requires buffering
  and header rewriting. More moving parts, no gain over materializing in the filter.
- **`UNSIGNED-PAYLOAD`**: not accepted by Lambda Function URLs with `AuthType: AWS_IAM`.
- **Delete `SigV4ClientRequestFilter` instead of fixing it**: viable if the MCP server is not
  imminent and the intent is for it to reuse the CLI's `AwsSigV4Signer` pattern instead — but
  only after that signer is fixed (Findings 2/3) and promoted to a shared module, since `ecp-cli`
  is a CLI artifact and the MCP server should not depend on it directly.

## Follow-up / Open Items

- Whether the `ObjectMapper` used for signing should be injected from the application's JAX-RS
  provider configuration rather than constructed fresh. Correct in principle; a default mapper is
  safe only while all DTOs stay plain records with default naming.
- Whether to offer a non-JAX-RS signing variant for `java.net.http` callers, so the MCP server
  does not have to re-derive the same logic a third time. The signing logic is identical for both
  and the JAX-RS ordering problem does not apply outside JAX-RS.

## Outcome

Implemented on branch `fix/sigv4-signing-issues`, in two commits:

1. **Finding 1 + 4, in `ecp-api`'s `SigV4ClientRequestFilter`.** `extractBody()` was replaced with
   `materializeEntity()`, which serializes non-`String`/`byte[]` entities with Jackson and writes
   the resulting bytes back onto the request via `setEntity()`, so the signed bytes and the sent
   bytes cannot diverge. Migrated from `Aws4Signer`/`Aws4SignerParams` to `AwsV4HttpSigner` (API
   verified against the `http-auth-aws:2.54.16` jar with `javap` before writing code against it —
   `SERVICE_SIGNING_NAME` on `AwsV4FamilyHttpSigner`, `REGION_NAME` on `AwsV4HttpSigner`, exactly
   as the source report described). Region now fails fast with an actionable message instead of
   silently defaulting to `us-east-1`. Added a package-visible constructor accepting an explicit
   `AwsCredentialsProvider` so the new regression tests use static test credentials rather than
   the machine's real credential chain, per the source report's testing guidance.

   Six new tests in `ecp-api/src/test/java/.../SigV4ClientRequestFilterTest.java`, including the
   report's own suggested regression test (a POJO-bodied request and a bodiless request must sign
   differently — verified this fails under the pre-fix code by temporarily reintroducing the bug
   and re-running the suite, then reverting). `ecp-api` had no test infrastructure before this
   change; added `junit-jupiter` (matching the version other simple-JUnit modules in this repo
   pin) and `jackson-databind` (needed by `materializeEntity()` itself, not only tests).

2. **Finding 2 + 3, across `ecp-cli`.** `AwsSigV4Signer` now stores the `AwsCredentialsProvider`
   and calls `resolveCredentials()` inside every `sign(...)` call instead of once at `create()`
   time — exactly the provider-not-STS-client fix described above, not a new STS client.
   `create()` now throws `IllegalStateException` with an actionable message instead of returning
   `null`. All 7 call sites' `if (signer != null)` / `if (signer == null)` guards were removed;
   each was already covered by an appropriate existing `catch` (either a top-level
   `System.err.println("Error: ...")` + `System.exit(1)`, or an intentional best-effort
   catch-and-return-null in `EcpConfig`'s SSM fallback and `ClusterAccessHelper`'s optional
   access-info lookup), so removing the dead guards changed behavior only for the case that was
   actually broken. `EcpApiClient.init()` is a CDI `@PostConstruct` shared by every CLI command,
   so it defers a `create()` failure into a `signerInitFailure` field and re-throws it lazily via
   `requireSigner()`, only when a signed call is actually attempted — letting `init()` itself
   throw would have crashed bean construction for every command, including ones that never sign a
   request.

   Five new/updated tests in `AwsSigV4SignerTest` (including a credential-rotation test proving
   `resolveCredentials()` is called once per `sign()` call, not cached — verified this fails under
   the pre-fix cached-field pattern by temporarily reintroducing it) and two new tests in
   `EcpApiClientTest` for the deferred-throw behavior.

**Deliberately not done:**

- **`AwsSigV4Signer` was not migrated to `AwsV4HttpSigner`.** The source report frames Finding 4 as
  "worth doing at the same time as Finding 1, since that code is already being rewritten" —
  referring to `SigV4ClientRequestFilter`, which was rewritten. `AwsSigV4Signer` was touched for
  Findings 2/3 but not rewritten wholesale; migrating its signer implementation too would have
  multiplied the risk surface across all 7 call sites for a low-priority, purely cosmetic change
  (both signers work correctly today; `Aws4Signer` is deprecated but not yet removed).
- **The "exclude `Authorization` from signed headers" smaller note does not apply to either file
  as currently structured.** Neither `SigV4ClientRequestFilter` nor `AwsSigV4Signer` ever copies
  the caller's pre-existing request headers into the bytes being signed — both build the signing
  input from scratch with only `Content-Type` (and, in `AwsSigV4Signer`, explicit `extraHeaders`
  the caller passes in for that specific call, e.g. `X-Amz-Target`). There is no vector today for a
  stale `Authorization` header to be folded into a new signature. Re-confirmed by reading both
  `filter()`/`sign()` methods directly rather than assuming the note transfers unchanged from the
  reference filter it was originally written against.
- **The `EcpConfig.getRegion()` `us-east-1` default was left alone.** It is a general CLI
  configuration default (documented, intentional), not the SigV4-signing-region silent fallback
  the smaller note warns about — that fallback only existed in `SigV4ClientRequestFilter` and has
  been removed there.

**Verification performed beyond unit tests:**

- Full repo `./build-local.sh --skip-tests` (all 11 modules) and `mvn test` (all 11 modules,
  0 failures) after both commits.
- Native `ecp-cli` build (`./build-local.sh --only cli --native --skip-tests`) succeeds — confirms
  no GraalVM native-image reflection issues from the changed signer internals.
- Ran the native binary directly with `AWS_PROFILE`/`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`/
  `AWS_SESSION_TOKEN` unset and `HOME` pointed at an empty directory: `list-clusters` printed
  `Failed to reach Express Compute service at https://express-compute.codriverlabs.ai: No AWS
  credentials found; run 'aws sso login' or set AWS_PROFILE.` — the exact actionable message
  Finding 3 exists to produce, observed end-to-end in a real binary, not just asserted in a test
  double.
- For both the empty-payload bug (Finding 1) and the credential-caching bug (Finding 2), the fix
  was temporarily reverted, the relevant test was re-run and confirmed to fail with a message
  pointing at the exact regression, then the fix was restored and the test re-confirmed passing —
  so the new tests are known to catch the bugs they were written for, not just pass incidentally.
