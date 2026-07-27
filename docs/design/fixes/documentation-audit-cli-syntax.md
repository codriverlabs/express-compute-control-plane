# Documentation Audit: CLI Syntax and Stale References

## Problem

The CLI was unified in v2.2.0 to use flat `ecp <verb-noun>` commands with positional
cluster names and mode inference (no `--oidc-mode` flag). Multiple documentation files
still reference the old syntax (`--name` flag, `--oidc-mode self-managed`, separate
`register-cluster`/`deregister-cluster` commands, `create-tenant`/`delete-tenant`).

Additionally, helper scripts were renamed from `create_tenant.sh`/`delete_tenant.sh` to
`create-cluster.sh`/`delete-cluster.sh` but docs still reference the old names.

## Scope

| File | Issues |
|------|--------|
| `README.md` | Typo, stale script names, missing karpenter module |
| `docs/architecture.md` | CLI command tree mermaid diagram completely stale |
| `docs/user-guides/deployment.md` | `--oidc-mode`, `--name` flag throughout |
| `docs/user-guides/integration-express-compute.md` | `--name` flag usage |
| `docs/user-guides/integration-express-compute-ce.md` | `--oidc-mode`, "Terraform" reference |
| `docs/user-guides/integration-k3s.md` | `--oidc-mode`, `--name` flag |
| `.kiro/steering/DEVELOPMENT.md` | Stale script names |

## Correct CLI Syntax (per current code)

```
ecp create-cluster <name> [--arch arm64] [--pricing spot] [--wait]     # managed
ecp create-cluster <name> --jwks-file <path> --issuer <url>            # self-managed
ecp create-cluster <name> --kubeconfig <path>                           # self-managed (auto-discovery)
ecp delete-cluster <name>
ecp describe-cluster <name>
ecp list-clusters
ecp update-cluster <name>
ecp stop-cluster <name>
ecp resume-cluster <name>
ecp get-cluster-access <name>
ecp create-association <cluster> <ns> <sa> <role-arn>
ecp delete-association <cluster> <id>
ecp describe-association <cluster> <id>
ecp list-associations <cluster>
ecp configure --endpoint <url> --region <region>
```

## Fix Plan

1. Update `docs/architecture.md` Section 9 (CLI command tree)
2. Update `docs/architecture.md` Section 3 (cluster registration flow)
3. Update `docs/user-guides/deployment.md`
4. Update `docs/user-guides/integration-express-compute.md`
5. Update `docs/user-guides/integration-express-compute-ce.md`
6. Update `docs/user-guides/integration-k3s.md`
7. Update `README.md`
8. Update `.kiro/steering/DEVELOPMENT.md`
