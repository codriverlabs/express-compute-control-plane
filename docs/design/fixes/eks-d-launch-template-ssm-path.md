# Fix: EKS-D Launch Template Resolution Used a Removed SSM Path

## Status

Implemented alongside a 2-repo companion fix standardizing AMI/launch-template SSM
paths on the distribution-prefixed convention (`express-compute-managed-k8s-infra`
PR #31, `express-compute-platform` PR #42). This document covers the control-plane
side of that change plus a bug discovered while making it deployment-safe.

## Problem

`ecp create-cluster --distribution eks-d` (no `--wait`) failed with:

```
ParameterNotFound: /express-compute/infra/ami/eks-d/arm64/1.35
```

after successfully creating a subnet, security group, KMS-signed CA/PKI, IAM role,
SQS queue, EventBridge rules, and DLM policy — all of which were then cleanly rolled
back (see `ClusterAlreadyExistsException` and the compensating-rollback logic in
`TenantProvisioningService`, which worked correctly).

## Root Cause

Two related but distinct bugs, both stemming from the same underlying inconsistency
between how `express-compute-platform`/`express-compute-managed-k8s-infra` published
SSM parameters and how this repo read them:

1. **AMIs**: `express-compute-platform` published EKS-D AMIs to an unprefixed path
   (`/express-compute/infra/ami/{arch}/{version}`), while `TenantEc2Service` here
   always reads the distribution-prefixed path
   (`InfraNaming.ssmAmiPath(Distribution, arch, version)` →
   `/express-compute/infra/ami/eks-d/{arch}/{version}`). This is the bug that
   surfaced first, in the `ParameterNotFound` above.

2. **Launch templates** (discovered while fixing #1): `TenantProvisioningService`'s
   `resolveLaunchTemplate()` had a distribution branch that made the same problem
   *worse*, not better:

   ```java
   // BEFORE
   private String resolveLaunchTemplate(Distribution distribution, String arch, String pricingModel) {
       if (distribution == Distribution.K3S) {
           // k3s: resolve at runtime via SSM
           String ssmPath = InfraNaming.ssmLaunchTemplatePath(distribution, arch, pricingModel);
           return ssm.getParameter(...).parameter().value();
       }
       // EKS-D: use pre-resolved config properties (env vars, injected at CDK synth time)
       return switch (arch + "/" + pricingModel) {
           case "arm64/ondemand" -> ltArm64Ondemand;
           ...
       };
   }
   ```

   `ltArm64Ondemand` etc. were `@ConfigProperty`-injected from environment variables
   (`EXPRESS_COMPUTE_LT_ARM64_ONDEMAND`, ...) that `ExpressComputeControlPlaneStack`
   set by resolving `StringParameter.valueForStringParameter(this,
   "/express-compute/infra/launch-template/{arch}/{mode}")` — the **unprefixed**
   path — at CDK synth time.

   This meant EKS-D launch template resolution had a completely different failure
   mode than AMI resolution: not a runtime `ParameterNotFound` from the Lambda, but
   a **CDK synth/deploy-time failure** the moment the unprefixed launch-template SSM
   parameter was removed (which is exactly what the companion fix in
   `express-compute-managed-k8s-infra` does). Deploying that companion fix without
   also fixing this would have broken `cdk deploy` for the control plane stack
   entirely — a `StringParameter.valueForStringParameter()` call against a
   nonexistent parameter fails at synth/deploy time, not gracefully at runtime.

## Fix

Unify EKS-D onto the exact same runtime SSM lookup k3s already used successfully,
instead of carrying two resolution mechanisms:

```java
// AFTER
private String resolveLaunchTemplate(Distribution distribution, String arch, String pricingModel) {
    String ssmPath = InfraNaming.ssmLaunchTemplatePath(distribution, arch, pricingModel);
    return ssm.getParameter(GetParameterRequest.builder().name(ssmPath).build())
        .parameter().value();
}
```

Changes:

- `TenantProvisioningService`: removed the `distribution == Distribution.K3S` branch
  and the 4 `@ConfigProperty` fields (`ltArm64Ondemand`, `ltArm64Spot`,
  `ltX86Ondemand`, `ltX86Spot`). Both distributions now call
  `ssm.getParameter(InfraNaming.ssmLaunchTemplatePath(distribution, arch, pricing))`.
- `application.properties`: removed the 4 `express-compute.tenant.lt-*` properties
  and their `EXPRESS_COMPUTE_LT_*` env var bindings.
- `ExpressComputeControlPlaneStack`: removed the 4
  `StringParameter.valueForStringParameter()` calls resolving launch templates at
  synth time, and the corresponding `Map.entry(...)` env var injections on the
  tenant-service Lambda. The stack still resolves `vpc-id` at synth time (shared
  infra, not distribution-specific) — that one call is unaffected.
- IAM policy: no change needed. The existing `ssm:GetParameter` grant on
  `.../infra/launch-template/*` is a wildcard that already covers the
  `eks-d/`/`k3s/` sub-paths.
- Docs: `README.md`, `docs/design/ssm-parameter-contract.md` (rewrote the stale
  "Terraform Implementation" section — this repo's shared infra is Java CDK, not
  Terraform, and points to the actual publishing code instead of fabricated sample
  code), `docs/design/k3s-xpress-control-plane-changes.md`,
  `docs/design/infra/deployment-modes.md`, `docs/user-guides/iam/lambda-permissions.md`
  (added the missing `launch-template/*` scope, which was undocumented even before
  this fix).

### Regression tests

`TenantProvisioningServiceTest` had zero coverage of `resolveLaunchTemplate()` before
this fix — that gap is exactly why the EKS-D/k3s split went unnoticed. Added three
tests:

- `resolveLaunchTemplate_eksD_readsFromDistributionPrefixedSsmPath` — asserts EKS-D
  calls `ssm.getParameter` with `/express-compute/infra/launch-template/eks-d/arm64/spot`
- `resolveLaunchTemplate_k3s_readsFromDistributionPrefixedSsmPath` — same for k3s
- `resolveLaunchTemplate_eksD_and_k3s_useTheSameCodePath_notASpecialCase` — asserts
  both distributions call `ssm.getParameter` exactly once each, so neither falls
  through to a special-cased pre-resolved field

Verified these catch the regression: reintroduced a version of the bug (EKS-D
returning a hardcoded string instead of calling SSM), confirmed the first two tests
failed, then restored the fix and confirmed all three pass.

## Verification

- `./build-local.sh --skip-tests` and `mvn test` — all 11 modules green
- `cdk synth` in `infra/` — synthesizes cleanly; confirmed zero
  `EXPRESS_COMPUTE_LT_*` env vars and zero leftover
  `SsmParameterValue...launchtemplate...` CloudFormation template parameters in the
  output (both were present before this fix, tied to the removed
  `valueForStringParameter()` calls)
- Confirmed via a live `ecp create-cluster --distribution eks-d --wait` attempt
  (before this fix existed) that the failure mode was exactly `ParameterNotFound`
  on the AMI path, with the rollback logic working correctly — that incident is
  what surfaced both bugs described here

## Deployment Note

Requires a full redeploy of the control plane CDK stack (confirmed acceptable).
Deploy this fix **before or together with** the `express-compute-managed-k8s-infra`
companion fix that removes the unprefixed launch-template SSM parameter — deploying
the infra fix first, alone, would break `cdk deploy` for this stack (see Root Cause,
bug #2).
