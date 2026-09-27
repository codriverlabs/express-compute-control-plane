# SSM Parameter Contract: express-compute-managed-k8s-infra → ecp-control-plane

The `express-compute-managed-k8s-infra` CDK stack (and the AMI-publishing pipeline in
`express-compute-platform`) write these SSM parameters per region. The control plane
reads the shared VPC ID via CDK at deploy time (`StringParameter.valueForStringParameter()`);
AMI and launch template IDs are read by the tenant-service Lambda at **runtime** via the AWS SDK
(`ssm:GetParameter`), not resolved by CDK at synth time — this lets both distributions (`eks-d`,
`k3s`) share one lookup code path (`InfraNaming.ssmAmiPath`/`ssmLaunchTemplatePath`).

**Deploy order**: shared infra stack first → control plane stack second.

## Design Principles

1. **Region-scoped** — SSM Parameter Store is regional. Same paths, different values per region.
2. **Hierarchical paths** — supports `get-parameters-by-path` for discovery and listing.
3. **Distribution-prefixed** — every AMI and launch template path includes the distribution
   segment (`eks-d` or `k3s`). There is no unprefixed path for either distribution.

## Parameter Hierarchy

```
/express-compute/
├── infra/                              ← written by express-compute-managed-k8s-infra CDK stack
│   │                                     and the AMI-publishing pipeline (express-compute-platform)
│   ├── ami/
│   │   ├── eks-d/
│   │   │   ├── arm64/
│   │   │   │   └── 1.35          → ami-0aaa111
│   │   │   └── x86_64/
│   │   │       └── 1.35          → ami-0bbb222
│   │   └── k3s/
│   │       ├── arm64/
│   │       │   └── 1.35          → ami-0eee555
│   │       └── x86_64/
│   │           └── 1.35          → ami-0fff666
│   ├── launch-template/
│   │   ├── eks-d/
│   │   │   ├── arm64/
│   │   │   │   ├── ondemand      → lt-0aaa111
│   │   │   │   └── spot          → lt-0bbb222
│   │   │   └── x86_64/
│   │   │       ├── ondemand      → lt-0ccc333
│   │   │       └── spot          → lt-0ddd444
│   │   └── k3s/
│   │       ├── arm64/
│   │       │   ├── ondemand      → lt-0ggg777
│   │       │   └── spot          → lt-0hhh888
│   │       └── x86_64/
│   │           ├── ondemand      → lt-0iii999
│   │           └── spot          → lt-0jjj000
│   └── network/
│       └── vpc-id                → vpc-0abc123
└── control-plane/                      ← written by express-compute-control-plane CDK stack
    ├── api/
    │   └── endpoint              → https://xxx.execute-api.us-east-1.amazonaws.com/prod
    └── quota/
        └── max-tenants-per-caller → 1
```

## Discovery via get-parameters-by-path

```bash
# All EKS-D AMIs for arm64 (all k8s versions)
aws ssm get-parameters-by-path --path /express-compute/infra/ami/eks-d/arm64

# All k3s AMIs for arm64 (all k8s versions)
aws ssm get-parameters-by-path --path /express-compute/infra/ami/k3s/arm64

# All AMIs (both distributions, all arches)
aws ssm get-parameters-by-path --path /express-compute/infra/ami --recursive

# All launch templates for EKS-D arm64 (spot + ondemand)
aws ssm get-parameters-by-path --path /express-compute/infra/launch-template/eks-d/arm64

# All launch templates (all distributions, all arches, all types)
aws ssm get-parameters-by-path --path /express-compute/infra/launch-template --recursive

# All network params
aws ssm get-parameters-by-path --path /express-compute/infra/network
```

## Full Parameter List

### AMIs (per distribution, per arch, per k8s version)

All AMI paths are distribution-prefixed — there is no unprefixed path.

| SSM Path | Type | Description |
|----------|------|-------------|
| `/express-compute/infra/ami/eks-d/{arch}/{k8s-version}` | `String` | Region-specific AMI for EKS-D nodes |
| `/express-compute/infra/ami/k3s/{arch}/{k8s-version}` | `String` | Region-specific AMI for k3s nodes |

### Launch Templates (per distribution, per arch, per pricing model)

All launch template paths are distribution-prefixed — there is no unprefixed path.

| SSM Path | Type | Description |
|----------|------|-------------|
| `/express-compute/infra/launch-template/eks-d/arm64/ondemand` | `String` | LT: EKS-D arm64 on-demand instances |
| `/express-compute/infra/launch-template/eks-d/arm64/spot` | `String` | LT: EKS-D arm64 spot instances |
| `/express-compute/infra/launch-template/eks-d/x86_64/ondemand` | `String` | LT: EKS-D x86_64 on-demand instances |
| `/express-compute/infra/launch-template/eks-d/x86_64/spot` | `String` | LT: EKS-D x86_64 spot instances |
| `/express-compute/infra/launch-template/k3s/arm64/ondemand` | `String` | LT: k3s arm64 on-demand instances |
| `/express-compute/infra/launch-template/k3s/arm64/spot` | `String` | LT: k3s arm64 spot instances |
| `/express-compute/infra/launch-template/k3s/x86_64/ondemand` | `String` | LT: k3s x86_64 on-demand instances |
| `/express-compute/infra/launch-template/k3s/x86_64/spot` | `String` | LT: k3s x86_64 spot instances |

### Network

| SSM Path | Type | Description |
|----------|------|-------------|
| `/express-compute/infra/network/vpc-id` | `String` | VPC for ecp workloads |
| `/express-compute/infra/network/public-subnet-ids` | `StringList` | Public subnets (NAT/ALB) |
| `/express-compute/infra/network/private-subnet-ids` | `StringList` | Private subnets (tenant nodes) |
| `/express-compute/infra/network/security-group-id` | `String` | SG for tenant k3s nodes |

## Actual Implementation

The parameters above are **not** written by Terraform — they are published by:

- **Launch templates** — `express-compute-managed-k8s-infra` (Java CDK):
  `ExpressComputeManagedK8sInfraStack.java`, `createLaunchTemplates()` /
  `createK3sLaunchTemplates()` methods.
- **AMI IDs and signatures** — `express-compute-platform`: the Packer post-processors
  in `ami-builder/ecp-golden-ami.pkr.hcl` (EKS-D) and `ami-builder/k3s-xpress.pkr.hcl`
  (k3s) publish the AMI ID directly; `ami-builder/scripts/sign-ami.sh` publishes the
  signature. `bundle/deploy.sh`'s `register_amis` and `ami-builder/scripts/import-ami.sh`
  handle re-publishing to additional regions.

See those repos' own docs (`docs/design/k3s-cli-and-ssm-contract.md` in
`express-compute-platform`) for the authoritative parameter-writing code — this file
documents the contract from the consuming (control plane) side, not the implementation.

## CDK Consumer

```java
// Launch template IDs are NOT resolved at CDK synth time. TenantProvisioningService
// resolves them at runtime via SSM (InfraNaming.ssmLaunchTemplatePath(distribution, arch,
// pricing)) for both eks-d and k3s — the same mechanism AMI lookup already uses. This
// keeps both distributions on one code path instead of a synth-time/runtime split.

// The one param the control plane stack still resolves at synth time is the shared VPC:
String vpcId = StringParameter.valueForStringParameter(this, "/express-compute/infra/network/vpc-id");

// Network
String subnetIds = StringParameter.valueForStringParameter(this, "/express-compute/infra/network/private-subnet-ids");
```

Note: AMIs and launch templates are read by the tenant-service Lambda at runtime via the AWS SDK
(`ssm:GetParameter`), not resolved by CDK at synth time. The Lambda's IAM role has
`ssm:GetParameter` scoped to `/express-compute/infra/ami/*` and `/express-compute/infra/launch-template/*`.

## Multi-Region Deployment

```
Source Region (us-east-1)              Target Region (eu-west-1)
─────────────────────────              ─────────────────────────
Packer → AMI (ami-src-111)    ──copy──► AMI (ami-tgt-222)
import-ami.sh publishes:               import-ami.sh publishes:
  /express-compute/infra/ami/eks-d/arm64/1.35 = ami-src    /express-compute/infra/ami/eks-d/arm64/1.35 = ami-tgt
  /express-compute/infra/launch-template/...               /express-compute/infra/launch-template/...
  /express-compute/infra/network/...                        /express-compute/infra/network/...
```

## Notes

- All parameters use `String` or `StringList` — not `SecureString`.
- AMI IDs are region-specific — copying produces a new ID.
- Launch templates reference AMIs, so they are also region-specific.
- The k8s version in AMI paths enables multiple versions to coexist (rolling upgrades).
- `get-parameters-by-path` with `--recursive` traverses the full subtree.
