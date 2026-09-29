# Fix: k3s Boot-Script Progress States Silently Dropped by SSE Validation

## Status

Found while diagnosing an apparent "80s tail" after k3s cluster provisioning (which
turned out not to exist — see below). This is a real, separate, low-severity bug.

## Problem

`ecp create-cluster --distribution k3s --wait` reaches `100% Cluster ready`
correctly, but two of the boot script's own progress updates are silently dropped
along the way and never reach the CLI:

```
2026-09-28 23:00:07 WARN  [...TenantStreamResource] Invalid state 'k3s-starting' from tenant b81e9a8b — ignoring
2026-09-28 23:00:07 WARN  [...TenantStreamResource] Invalid state 'k3s-ready' from tenant b81e9a8b — ignoring
```

## Root Cause

`express-compute-platform`'s `cluster-setup/k3s/setup-k3s-xpress.sh` reports two
k3s-specific intermediate states:

```bash
update_progress "k3s-starting" "Starting k3s server" 20
...
update_progress "k3s-ready" "k3s server running" 35
```

(`k3s-ready` here means "the k3s server process itself is up," at 35% — it is
**not** the terminal cluster-ready signal. The actual terminal state is reported
separately by `report_ready()` later in the script, which sends the generic
`"ready"` state at 100% — that one *is* valid, which is why the CLI still reaches
"Cluster ready" correctly.)

`TenantStreamResource.isValidState()` in this repo only accepts:

```java
case "provisioning", "booting", "pulling-key",
     "kubeadm-init", "kubeadm-done", "registering",
     "ready", "failed" -> true;
```

This list was written for EKS-D's state names (`kubeadm-init`, `kubeadm-done`).
`k3s-starting` and `k3s-ready` are not in it, so `validateAndPersist()` rejects
both, logs a warning, and returns `null` — the message is never persisted to
DynamoDB and never streamed to the client as an SSE event.

### Not the "80s tail"

This was originally suspected of causing an ~80-second delay between k3s
finishing and the CLI printing "Cluster ready." That turned out to be a
misdiagnosis on my part (I anchored "when k3s reported ready" to the rejected
`k3s-ready` warning log line instead of the actual terminal `report_ready()`
call). A follow-up run with per-line client-side wall-clock timestamps showed
the CLI prints "✓ Cluster ready." within 16ms of the server's "Deleted progress
queue... provisioning complete" log line — there is no tail to fix. The full
~265-283s duration observed across two runs is legitimate boot-script work
(VPC CNI, add-ons, Karpenter installation), not a validation or streaming delay.

The dropped states are real, but cosmetic: the user briefly sees a stale
percentage (their previous gap-filled value) instead of a smooth climb through
20% → 35%, for a few seconds during k3s server startup.

## Fix

Add `k3s-starting` and `k3s-ready` to `TenantStreamResource.isValidState()`'s
allowlist, alongside the existing EKS-D-specific states:

```java
private boolean isValidState(String state) {
    return switch (state) {
        case "provisioning", "booting", "pulling-key",
             "kubeadm-init", "kubeadm-done", "registering",
             "k3s-starting", "k3s-ready",
             "ready", "failed" -> true;
        default -> false;
    };
}
```

No change needed to `isTerminalState()`/`isTerminal()` — `k3s-ready` here means
"k3s server process started," not "cluster provisioning complete." Only the
generic `"ready"` state (sent separately by `report_ready()`) is terminal; adding
`k3s-ready` to the terminal check would cause the SSE stream to close and the
progress queue to be deleted at 35% instead of 100%, breaking the remaining
Karpenter/add-on install progress reporting.

### Regression tests

Added to `TenantStreamResourceTest`:

- `validateAndPersist_acceptsK3sStartingState` / `validateAndPersist_acceptsK3sReadyState` —
  assert both states are accepted and persisted, pinning the fix
- `validateAndPersist_k3sReadyIsNotTreatedAsTerminal` — asserts `k3s-ready` does
  **not** trigger the terminal path (queue deletion), guarding against the
  adjacent mistake of also marking it terminal

Verified these fail against the pre-fix `isValidState()` (reverted, re-ran, confirmed
failure), then restored and confirmed passing.

## Scope

This fix is in `express-compute-control-plane` only (`TenantStreamResource`). No
change needed in `express-compute-platform`'s boot script — its progress
reporting was already correct; the server-side validation allowlist was
incomplete.
