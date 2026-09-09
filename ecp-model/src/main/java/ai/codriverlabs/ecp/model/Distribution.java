package ai.codriverlabs.ecp.model;

/**
 * Kubernetes distribution used for managed clusters.
 * Determines AMI selection, launch template, user data, boot scripts,
 * and add-on installation sequence.
 */
public enum Distribution {

    /** Amazon EKS Distro — kubeadm + separate etcd, VPC CNI, Karpenter. */
    EKS_D("eks-d"),

    /** k3s — single binary control plane, VPC CNI, Karpenter. */
    K3S("k3s");

    private final String value;

    Distribution(String value) {
        this.value = value;
    }

    /** Wire value used in API requests, DynamoDB, SSM paths, and CLI flags. */
    public String value() {
        return value;
    }

    /** SSM path segment: "eks-d" or "k3s". */
    public String ssmSegment() {
        return value;
    }

    /** Directory used on the EC2 instance for cluster state. */
    public String stateDir() {
        return switch (this) {
            case EKS_D -> "/opt/eks-d";
            case K3S   -> "/opt/k3s-xpress";
        };
    }

    /** cluster.env file path on the EC2 instance. */
    public String clusterEnvPath() {
        return stateDir() + "/cluster.env";
    }

    /** Boot timeout in seconds. */
    public int bootTimeoutSeconds() {
        return switch (this) {
            case EKS_D -> 240;
            case K3S   -> 120;
        };
    }

    /** Default root disk size in GB. */
    public int defaultRootDiskGb() {
        return switch (this) {
            case EKS_D -> 20;
            case K3S   -> 15;
        };
    }

    /** Default data disk size in GB (etcd for EKS-D, SQLite for k3s). */
    public int defaultDataDiskGb() {
        return switch (this) {
            case EKS_D -> 20;
            case K3S   -> 2;
        };
    }

    /**
     * Parse from string. Returns null if value is null, blank, or unrecognized —
     * callers must validate explicitly (no silent default).
     */
    public static Distribution fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toLowerCase();
        return switch (normalized) {
            case "k3s" -> K3S;
            case "eks-d", "eks_d", "eksd" -> EKS_D;
            default -> null;
        };
    }

    /**
     * Parse from DynamoDB/storage where legacy records may lack the field.
     * Returns EKS_D for null/blank (backward compat with existing data).
     */
    public static Distribution fromStorageValue(String value) {
        Distribution d = fromString(value);
        return d != null ? d : EKS_D;
    }

    @Override
    public String toString() {
        return value;
    }
}
