# Express Compute Integration: k3s

> **Primary guide:** See the [Self-Managed Quick Start](https://github.com/codriverlabs/express-compute-platform/blob/main/docs/user-guides/self-managed-quick-start.md)
> in the platform repository. It covers k3s, microk8s, EKS-D, and kubeadm clusters
> with versioned installers, full IAM setup, and cleanup instructions.

> **Full EC2 tutorial:** See [ec2-k3s-pod-identity/](ec2-k3s-pod-identity/) for a
> step-by-step walkthrough including EC2 launch and k3s installation from scratch.

---

## k3s-specific: OIDC issuer configuration

If `ecp create-cluster --kubeconfig` cannot auto-discover the JWKS (e.g., the
kube-apiserver is not reachable from your local machine), you need to configure
k3s with a public OIDC issuer URL manually.

### New k3s installation

```bash
PUBLIC_IP=$(curl -s http://169.254.169.254/latest/meta-data/public-ipv4)

curl -sfL https://get.k3s.io | sh -s - \
  --kube-apiserver-arg="service-account-issuer=https://${PUBLIC_IP}" \
  --kube-apiserver-arg="service-account-jwks-uri=https://${PUBLIC_IP}/openid/v1/jwks"
```

### Existing k3s installation

Add to `/etc/rancher/k3s/config.yaml`:

```yaml
kube-apiserver-arg:
  - "service-account-issuer=https://<PUBLIC_IP>"
  - "service-account-jwks-uri=https://<PUBLIC_IP>/openid/v1/jwks"
```

Then restart: `systemctl restart k3s`

### Manual cluster registration

Once the issuer is configured, register with the JWKS fetched from the node:

```bash
# On the k3s node
kubectl get --raw /openid/v1/jwks > /tmp/jwks.json

# From your machine
ecp create-cluster my-k3s \
  --issuer https://<PUBLIC_IP> \
  --jwks-file /tmp/jwks.json
```

After registration, continue with step 3 of the
[Self-Managed Quick Start](https://github.com/codriverlabs/express-compute-platform/blob/main/docs/user-guides/self-managed-quick-start.md#3-install-workload-identity-components).
