# k3s-Xpress: Control Plane Changes

Changes required in `express-compute-control-plane` to support the k3s-Xpress
distribution from `express-compute-platform`.

---

## 1. ecp-model — Data Model Changes

### TenantItem.java

Add `distribution` field:

```java
@RegisterForReflection
public record TenantItem(
    String tenantId,
    String clusterName,
    boolean managed,
    String distribution,       // NEW — "eks-d" (default) | "k3s"
    String idcUserId,
    String ownerArn,
    String createdAt,
    String updatedAt,
    // managed only
    String state,
    String phase,
    int progress,
    String instanceId,
    String publicIp,
    String eipAllocationId,
    String sshKeySecretArn,
    String ec2PricingModel,
    String error
) {}
```

**Backward compatibility:** existing EKS-D tenants have `distribution=null`.
All code must treat `null` as `"eks-d"`. Helper method:

```java
public String effectiveDistribution() {
    return distribution != null ? distribution : "eks-d";
}
```

---

## 2. ecp-tenant-service — InfraNaming.java

Add k3s SSM path resolution:

```java
public final class InfraNaming {
    // ... existing ...

    /**
     * SSM path for AMI lookup. k3s uses a /k3s/ prefix; EKS-D uses the legacy path.
     */
    public static String ssmAmiPath(String arch, String k8sVersion) {
        return SSM_PREFIX + "/ami/" + arch + "/" + k8sVersion;
    }

    /** NEW — distribution-aware AMI lookup. */
    public static String ssmAmiPath(String distribution, String arch, String k8sVersion) {
        if ("k3s".equals(distribution)) {
            return SSM_PREFIX + "/ami/k3s/" + arch + "/" + k8sVersion;
        }
        // EKS-D legacy path (no distribution prefix)
        return SSM_PREFIX + "/ami/" + arch + "/" + k8sVersion;
    }

    /** NEW — distribution-aware launch template lookup. */
    public static String ssmLaunchTemplatePath(String distribution, String arch, String pricing) {
        if ("k3s".equals(distribution)) {
            return SSM_PREFIX + "/launch-template/k3s/" + arch + "/" + pricing;
        }
        return SSM_PREFIX + "/launch-template/" + arch + "/" + pricing;
    }
}
```

---

## 3. ecp-tenant-service — TenantEc2Service.java

### 3.1 AMI Resolution

Change `launchInstance()` to accept and use `distribution`:

```java
// BEFORE
String amiId = ssm.getParameter(GetParameterRequest.builder()
    .name(InfraNaming.ssmAmiPath(arch, k8sVersion))
    .build()).parameter().value();

// AFTER
String amiId = ssm.getParameter(GetParameterRequest.builder()
    .name(InfraNaming.ssmAmiPath(distribution, arch, k8sVersion))
    .build()).parameter().value();
```

### 3.2 User Data Script

Add a k3s branch in `userDataScript()`. The key differences:
- Writes to `/opt/k3s-xpress/cluster.env` (not `/opt/eks-d/cluster.env`)
- Does NOT include EKS-D-specific fields (`POD_SUBNET`, `K8S_VERSION`)

```java
private String userDataScript(String tenantId, String clusterName,
                              String region, String k8sVersion, String nodeIp,
                              String accountId, String arch, String vpcCidr,
                              String publicSubnetId, String privateSubnetId,
                              String securityGroupId, String distribution,
                              String cni, String autoscaling) {

    if ("k3s".equals(distribution)) {
        return k3sUserDataScript(tenantId, clusterName, region, nodeIp,
                                 accountId, publicSubnetId, privateSubnetId,
                                 securityGroupId, cni, autoscaling);
    }
    // existing EKS-D user data unchanged
    return eksUserDataScript(tenantId, clusterName, region, k8sVersion,
                             nodeIp, accountId, arch, vpcCidr,
                             publicSubnetId, privateSubnetId, securityGroupId);
}

private String k3sUserDataScript(String tenantId, String clusterName,
                                 String region, String nodeIp, String accountId,
                                 String publicSubnetId, String privateSubnetId,
                                 String securityGroupId) {
    String nodeRoleArn = "arn:aws:iam::" + accountId + ":role/"
        + TenantNaming.roleName(tenantId);
    String progressQueueUrl = "https://sqs." + region + ".amazonaws.com/"
        + accountId + "/" + TenantNaming.progressQueueName(tenantId);
    return """
        #!/bin/bash
        mkdir -p /opt/k3s-xpress
        ECP_ENDPOINT=$(aws ssm get-parameter \
          --name /express-compute/control-plane/api/endpoint \
          --region %s \
          --query Parameter.Value \
          --output text 2>/dev/null || echo "")
        cat > /opt/k3s-xpress/cluster.env <<CONF
        TENANT_ID="%s"
        CLUSTER_NAME="%s"
        AWS_ACCOUNT_ID="%s"
        AWS_REGION="%s"
        NODE_ROLE_ARN="%s"
        PUBLIC_SUBNET_ID="%s"
        PRIVATE_SUBNET_ID="%s"
        SECURITY_GROUP_ID="%s"
        ECP_ENDPOINT="${ECP_ENDPOINT}"
        PROGRESS_QUEUE_URL="%s"
        CONF
        """.formatted(region, tenantId, clusterName, accountId, region,
                     nodeRoleArn, publicSubnetId, privateSubnetId,
                     securityGroupId, progressQueueUrl);
}
```

### 3.3 Instance Configuration Differences

| Aspect | EKS-D | k3s |
|--------|-------|-----|
| Root volume | `diskSizeGb` (default 20) | 15 GB |
| Additional block device | etcd 10 GB gp3 on `/dev/xvdb` | None |
| Instance tags | `ecp-boot-script=setup-eks-d.sh` | `ecp-boot-script=setup-k3s-xpress.sh` |
| Boot timeout | 240s | 120s |

The `launchInstance()` method should skip the etcd volume `BlockDeviceMapping`
when `distribution=k3s` and use a smaller root volume default.

### 3.4 Default Instance Types

The `TenantProvisioningService` should select appropriate defaults:

```java
private String defaultInstanceType(String distribution, String arch) {
    if ("k3s".equals(distribution)) {
        return "arm64".equals(arch) ? "c6g.large" : "m7i.large";
    }
    return "arm64".equals(arch) ? "c6g.large" : "m7i.large";
}
```

---

## 4. ecp-tenant-service — TenantProvisioningService.java

### 4.1 Accept `distribution`, `cni`, `autoscaling` in provisioning request

The `ClusterResource` endpoint handler must pass these through to
`TenantProvisioningService.provision()` and persist them on the `TenantItem`.

### 4.2 Skip etcd DLM for k3s

k3s uses SQLite (single-node) or embedded etcd — no separate EBS volume,
no DLM lifecycle policy needed.

```java
// In the provisioning orchestrator
if (!"k3s".equals(distribution)) {
    // existing: create etcd EBS volume + DLM policy
    tenantDlmService.createLifecyclePolicy(...);
}
```

### 4.3 Boot Timeout

```java
int bootTimeoutSeconds = "k3s".equals(distribution) ? 120 : 240;
```

### 4.4 Rollback — TenantDlmService

Skip DLM cleanup for k3s tenants during rollback.

---

## 5. ecp-tenant-service — TenantDlmService.java

No changes needed. The provisioning orchestrator simply doesn't call it for k3s.

---

## 6. ecp-tenant-service — TenantIamService.java

The instance role permissions differ slightly for k3s:
- **Remove:** etcd EBS volume attach/detach (no separate etcd volume)
- **Add:** SSM PutParameter for `/express-compute/cluster/{name}/*` (Karpenter join credentials)

```java
// Additional policy statement for k3s (Karpenter always enabled)
if ("k3s".equals(distribution)) {
    statements.add(Statement.builder()
        .effect(Effect.ALLOW)
        .addAction("ssm:PutParameter")
        .addResource("arn:aws:iam::" + accountId
            + ":parameter/express-compute/cluster/" + clusterName + "/*")
        .build());
}
```

---

## 7. ecp-cli — UnifiedCreateClusterCommand.java

### 7.1 New CLI Flags

```java
@Option(names = "--distribution", defaultValue = "eks-d",
        description = "Cluster distribution: eks-d or k3s")
String distribution;
```

### 7.2 Request Body

```java
private void runManaged() {
    var body = new LinkedHashMap<String, Object>();
    body.put("clusterName", name);
    body.put("distribution", distribution);       // NEW
    if (sshCidr != null) body.put("sshCidr", sshCidr);
    // ...
}
```

### 7.3 Validation

```java
// k3s-specific defaults
if ("k3s".equals(distribution)) {
    if (diskSizeGb == 20) diskSizeGb = 15;  // smaller default for k3s
}
```

### 7.4 Example Usage

```bash
# k3s (same capabilities as EKS-D: VPC CNI, Karpenter, WI)
ecp create-cluster my-k3s --distribution k3s --wait

# EKS-D (unchanged, default)
ecp create-cluster my-eks --wait
```

---

## 8. ecp-cli — ListClustersCommand / DescribeClusterCommand

### list-clusters

Add `DISTRIBUTION` column:

```
NAME        DISTRIBUTION  STATUS  ARCH    PRICING  AGE
my-eks      eks-d         ready   arm64   spot     2d
my-k3s      k3s           ready   arm64   spot     1h
```

### describe-cluster

Add distribution-specific fields:

```
Name:           my-k3s
Distribution:   k3s
CNI:            vpc
Autoscaling:    karpenter
Status:         ready
Kubernetes:     v1.35.7
k3s:            v1.35.7+k3s1
Architecture:   arm64
Instance:       c6g.large
Pricing:        spot
Node IP:        10.0.1.42
Region:         us-east-1
Created:        2026-08-16T14:30:00Z
```

---

## 9. ecp-api — ClusterResource / TenantResource

### Create Cluster Request DTO

Add optional fields to the create request:

```java
public record CreateClusterRequest(
    String clusterName,
    String arch,
    String ec2PricingModel,
    String k8sVersion,
    int diskSizeGb,
    boolean assignElasticIp,
    String sshCidr,
    String distribution   // NEW — "eks-d" | "k3s"
) {}
```

### Tenant List/Describe Response

Include distribution in all tenant responses so the CLI can display it.

---

## 10. infra — CDK Stack Changes

### 10.1 k3s Launch Templates

Create launch templates for k3s (smaller root volume, no etcd volume):

```java
// k3s launch templates — ARM64 + x86_64 × spot + on-demand
for (String arch : List.of("arm64", "x86_64")) {
    for (String pricing : List.of("spot", "ondemand")) {
        var lt = LaunchTemplate.Builder.create(this, "K3sLt" + arch + pricing)
            .blockDevices(List.of(BlockDevice.builder()
                .deviceName("/dev/xvda")
                .volume(BlockDeviceVolume.ebs(15, EbsDeviceOptions.builder()
                    .volumeType(EbsDeviceVolumeType.GP3).build()))
                .build()))
            // Note: no etcd volume for k3s
            .build();

        StringParameter.Builder.create(this, "K3sLtParam" + arch + pricing)
            .parameterName("/express-compute/infra/launch-template/k3s/" + arch + "/" + pricing)
            .stringValue(lt.getLaunchTemplateId())
            .build();
    }
}
```

### 10.2 SSM Parameter Reads

The CDK stack must provide the k3s launch template IDs to the tenant-service
Lambda as environment variables (or the Lambda reads them from SSM at runtime).

---

## 11. SSM Parameters — Full Contract

### Published by `express-compute-platform` (this repo's Packer builds)

| SSM Path | Distribution | Value |
|----------|-------------|-------|
| `/express-compute/infra/ami/{arch}/{k8s-version}` | EKS-D | AMI ID |
| `/express-compute/infra/ami/{arch}/{k8s-version}/signature` | EKS-D | KMS signature |
| `/express-compute/infra/ami/k3s/{arch}/{k8s-version}` | **k3s** | AMI ID |
| `/express-compute/infra/ami/k3s/{arch}/{k8s-version}/signature` | **k3s** | KMS signature |

### Published by `express-compute-control-plane` CDK stack

| SSM Path | Distribution | Value |
|----------|-------------|-------|
| `/express-compute/infra/launch-template/{arch}/{pricing}` | EKS-D | Launch template ID |
| `/express-compute/infra/launch-template/k3s/{arch}/{pricing}` | **k3s** | Launch template ID |
| `/express-compute/infra/network/vpc-id` | Shared | VPC ID |
| `/express-compute/infra/network/nat-gateway-enabled` | Shared | true/false |
| `/express-compute/control-plane/api/endpoint` | Shared | API Gateway URL |

### Published at boot by k3s cluster (when Karpenter enabled)

| SSM Path | Written By | Value |
|----------|-----------|-------|
| `/express-compute/cluster/{name}/k3s-url` | `install-karpenter.sh` | API server URL |
| `/express-compute/cluster/{name}/k3s-token` | `install-karpenter.sh` | Node join token (SecureString) |
| `/express-compute/cluster/{name}/bootstrap-token` | `install-karpenter.sh` | Kubelet bootstrap token (SecureString) |

---

## 12. Cleanup / Deletion Changes

### TenantProvisioningService — delete path

When deleting a k3s cluster, clean up the per-cluster SSM parameters:

```java
if ("k3s".equals(tenant.effectiveDistribution())) {
    for (String suffix : List.of("/k3s-url", "/k3s-token", "/bootstrap-token")) {
        try {
            ssm.deleteParameter(DeleteParameterRequest.builder()
                .name("/express-compute/cluster/" + clusterName + suffix)
                .build());
        } catch (ParameterNotFoundException ignored) {}
    }
}
```

---

## 13. Tests

### Unit Tests

| Test | Scope |
|------|-------|
| `TenantEc2ServiceTest` — k3s user data script | Verify `/opt/k3s-xpress/cluster.env` path, no CNI_MODE/AUTOSCALING_MODE |
| `TenantEc2ServiceTest` — k3s AMI resolution | Verify SSM path includes `/k3s/` prefix |
| `InfraNamingTest` | Verify `ssmAmiPath("k3s", "arm64", "1.35")` = `/express-compute/infra/ami/k3s/arm64/1.35` |
| `TenantProvisioningServiceTest` — k3s skips DLM | Verify no `createLifecyclePolicy` call |
| `TenantProvisioningServiceTest` — k3s instance defaults | Verify `c6g.large` for arm64 k3s |
| `TenantResourceTest` — distribution field | Verify round-trip persistence and response |

### UAT (Robot Framework)

| Test | Description |
|------|-------------|
| `k3s-cluster-lifecycle.robot` | `create-cluster --distribution k3s` → verify ready → `delete-cluster` |
| `k3s-workload-identity.robot` | Create association → verify pod gets credentials |
| `k3s-vpc-cni.robot` | Verify pod has VPC IP (always-on) |
| `k3s-karpenter.robot` | Schedule pod → verify worker node joins |
| `k3s-boot-time.robot` | Assert boot completes in < 120s |

---

## 14. Summary of Changed Files

| Module | File | Change |
|--------|------|--------|
| `ecp-model` | `TenantItem.java` | Add `distribution` field |
| `ecp-tenant-service` | `InfraNaming.java` | Add distribution-aware `ssmAmiPath()`, `ssmLaunchTemplatePath()` |
| `ecp-tenant-service` | `TenantEc2Service.java` | k3s user data, AMI resolution, skip etcd volume |
| `ecp-tenant-service` | `TenantProvisioningService.java` | Distribution routing, skip DLM, boot timeout, instance defaults |
| `ecp-tenant-service` | `TenantIamService.java` | SSM PutParameter permission for Karpenter join creds |
| `ecp-tenant-service` | `ClusterResource.java` | Accept distribution in create request |
| `ecp-cli` | `UnifiedCreateClusterCommand.java` | `--distribution` flag |
| `ecp-cli` | `ListClustersCommand.java` | Add DISTRIBUTION column |
| `ecp-cli` | `DescribeClusterCommand.java` | Show distribution-specific fields |
| `ecp-api` | Request/Response DTOs | Add distribution field |
| `infra` | CDK stack | k3s launch templates + SSM parameters |
| `tests/uat` | New test suites | k3s lifecycle, WI, VPC CNI, Karpenter, boot time |
