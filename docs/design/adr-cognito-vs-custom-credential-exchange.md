# ADR: Custom Credential Exchange vs Amazon Cognito Identity Pools

**Status:** Accepted  
**Date:** 2026-08-12  
**Deciders:** Express Compute Platform Team

## Context

Express Compute provides EKS-compatible Workload Identity (`AssumeRoleForPodIdentity`) to non-EKS Kubernetes clusters (EKS-D, k3s, microk8s). Pods present service account tokens and receive temporary AWS credentials scoped to an IAM role.

Amazon Cognito Identity Pools (federated identities) is the AWS-native service for exchanging third-party tokens for temporary AWS credentials. We evaluated whether Cognito could replace our custom credential-exchange Lambda.

## Decision

We implement a custom credential-exchange service backed by DynamoDB-cached JWKS and direct STS AssumeRole, rather than using Cognito Identity Pools.

## Rationale

### 1. No Public OIDC Endpoint Required

Cognito requires each registered OIDC provider to expose a publicly accessible `/.well-known/openid-configuration` discovery endpoint.

Note: managed EKS always provides a public OIDC endpoint (hosted by AWS at `oidc.eks.{region}.amazonaws.com`), even for private-API clusters. However, our target clusters — EKS-D (kubeadm), k3s, microk8s — do **not** get this automatically. To use Cognito or IAM OIDC Identity Providers with self-managed clusters, the operator must:

1. Host JWKS and discovery metadata on a publicly accessible endpoint (e.g., public S3 bucket, CloudFront)
2. Configure kube-apiserver `--service-account-issuer` to that public URL
3. Keep the hosted JWKS in sync on every key rotation

This is documented by AWS for IRSA on EKS Anywhere, but it introduces operational burden and a public attack surface that we eliminate entirely. Our model:

- Generates KMS-signed SA keypairs at provisioning time (`TenantCryptoService`)
- Pre-registers JWKS in DynamoDB atomically — no runtime discovery needed
- Requires zero public infrastructure from tenants

### 2. Fine-Grained Per-ServiceAccount Role Mapping

Our DynamoDB association model provides O(1) lookup:

```
PK = CLUSTER#<cluster-name>
SK = <namespace>#<serviceAccount>
→ roleArn
```

Cognito's role mapping uses rule-based claim matching, which becomes unwieldy when mapping hundreds of distinct service accounts to distinct IAM roles per cluster. Our model supports arbitrary cardinality with no Cognito rule-limit concerns.

### 3. STS Session Tags from Token Claims

We inject Kubernetes token claims (`namespace`, `serviceAccount`, `podName`) as STS session tags during AssumeRole. This enables downstream IAM policies to scope access based on workload identity attributes (e.g., S3 prefix per namespace). Cognito Identity Pools does not support passing custom session tags into assumed role sessions.

### 4. Self-Managed Cluster Simplicity

Self-managed clusters (k3s, microk8s) register by POSTing their JWKS to our management API — a single API call. With Cognito, each cluster would require:

1. A publicly reachable OIDC issuer URL
2. Registration as an identity provider in the Cognito Identity Pool
3. Configuration of role mapping rules

This significantly raises the barrier for self-managed cluster onboarding.

### 5. Latency Control

Credential exchange is a hot path (every pod credential refresh). Our implementation is a single Lambda invocation (~50ms p50). Adding Cognito introduces an additional service hop with latency we cannot tune or optimize.

## Alternatives Considered

| Approach | Rejected Because |
|----------|-----------------|
| Cognito Identity Pools (OIDC provider per cluster) | Requires public OIDC endpoint; coarse role mapping; no session tags |
| Cognito with synthetic OIDC proxy | Adds a service (API Gateway + Lambda serving JWKS from DynamoDB) that replicates what we already do, plus Cognito overhead |
| IAM OIDC Identity Provider + AssumeRoleWithWebIdentity | Same public endpoint requirement; one IdP per cluster hits account limits at scale |

## Consequences

### Positive

- Zero public infrastructure required from tenants
- Millisecond-level credential exchange with full control over caching and validation
- Arbitrary role mapping granularity (per namespace, per SA, per cluster)
- Session tags enable attribute-based access control downstream
- Self-managed clusters onboard with a single API call

### Negative

- We own the JWKS validation code (jose4j) and must maintain it
- We own the DynamoDB schema and caching logic
- No AWS-managed token validation — bugs in our validation are our responsibility
- Cognito's built-in fraud detection and advanced auth flows are unavailable (not needed for machine-to-machine credential exchange)

## References

- [Cognito Identity Pools - OIDC Providers](https://docs.aws.amazon.com/cognito/latest/developerguide/open-id.html)
- [EKS Pod Identity / IRSA Architecture](https://docs.aws.amazon.com/eks/latest/userguide/pod-identities.html)
- [STS Session Tags](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_session-tags.html)
