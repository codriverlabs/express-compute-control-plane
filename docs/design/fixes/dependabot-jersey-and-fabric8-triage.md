# Dependabot Batch: Jersey Version-Split Fix and Fabric8 Major-Version Block

## Status

Merged: quarkus group (#127), aws-sdk group (#128), aws-cdk-lib (#129),
jackson-databind (#130). This doc covers the remaining three, which needed
investigation rather than a straight merge.

## Jersey (#131 `jersey-hk2`, #132 `jersey-client`) — fixed by combining

Dependabot opened two **separate** single-artifact PRs bumping `jersey-hk2`
and `jersey-client` from 3.0.18 to 4.0.2 independently. Both are test-scope
dependencies in `ecp-api/pom.xml`, used only by
`SigV4ClientRequestFilterJaxRsIntegrationTest` to exercise a real JAX-RS
client pipeline.

Merging either PR alone puts a 4.0.2 Jersey artifact on the classpath
alongside a 3.0.18 one, producing:

```
java.lang.NoClassDefFoundError: org/glassfish/jersey/innate/inject/InstanceBinding
    at org.glassfish.jersey.inject.hk2.Hk2InjectionManagerFactory.initInjectionManager
```

`org.glassfish.jersey.innate.inject.InstanceBinding` is a class that moved/was
introduced between Jersey 3.0.x and 4.0.x — a mixed-version Jersey classpath
is not binary compatible.

**Fix:** bumped both `jersey-client` and `jersey-hk2` to 4.0.2 together in a
single change (not via either Dependabot branch — applied directly to
`ecp-api/pom.xml`). Verified:

```
mvn -pl ecp-model,ecp-api test -am   # 8/8 pass with both at 4.0.2
mvn test                              # full suite, all 11 modules green
```

Both individual Dependabot PRs (#131, #132) were closed without merging
their branches directly; the equivalent change was applied as a manual
commit combining both bumps. This avoids leaving either PR's branch in a
state that silently breaks the build if merged alone later.

## Fabric8 `kubernetes-server-mock` (#133) — NOT merged, needs coordinated major upgrade

Bumping `kubernetes-server-mock` alone from 7.9.0 to 8.0.0 fails hard:

```
java.lang.NoClassDefFoundError: tools/jackson/core/JacksonException
    at io.fabric8.kubernetes.client.server.mock.KubernetesMixedDispatcher.<init>
Caused by: java.lang.ClassNotFoundException: tools.jackson.core.JacksonException
```

`tools.jackson.core` is Jackson 3.x's new package namespace. Fabric8
`kubernetes-server-mock:8.0.0` depends on it, but the actual
`kubernetes-client` runtime dependency in this module comes transitively via
`quarkus-kubernetes-client` (managed by the Quarkus BOM, currently 3.39.5),
which is still on an older fabric8/Jackson 2.x line. Bumping only the test
mock artifact creates a Jackson 2.x/3.x split, not just a fabric8 version
split.

This is a **coordinated major-version migration** (fabric8 client + mock
together, likely gated by when Quarkus's own BOM picks up fabric8 8.x), not
a routine patch/minor bump Dependabot can safely automate alone. Forcing it
through as a side effect of a routine dependency PR risks a much larger,
unplanned migration.

**Decision:** left PR #133 open, un-merged, with a comment explaining why.
Re-evaluate when the Quarkus BOM ships a `quarkus-kubernetes-client` that
pulls in fabric8 8.x by default — at that point `kubernetes-client` and
`kubernetes-server-mock` will naturally line up again.

## Also observed (unrelated)

`Ec2NodeClassReconcilerMockServerTest.specUpdate_generationBumps_retriggersReconcile`
failed once with a `409 Conflict` ("the object has been modified") against
the fabric8 mock server — a pre-existing optimistic-concurrency race in the
test, unrelated to any dependency bump here. Reran in isolation twice,
passed both times. Not fixed as part of this batch; flagging for future
attention if it recurs.
