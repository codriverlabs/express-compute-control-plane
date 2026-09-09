package ai.codriverlabs.ecp.tenant;

/**
 * Naming constants for shared infrastructure resources created by
 * the ExpressComputeManagedK8sInfraStack CDK stack.
 *
 * <p>These must match the {@code ProjectName} parameter used when deploying
 * the infra stack (default: {@code express-compute-managed-k8s-infra}).
 */
public final class InfraNaming {

    /** Project name used by the shared infra CDK stack. */
    public static final String INFRA_PROJECT_NAME = "express-compute-managed-k8s-infra";

    /** Platform tag value applied to all express-compute resources. */
    public static final String PLATFORM_TAG_VALUE = "express-compute";

    /** Platform tag key. */
    public static final String PLATFORM_TAG_KEY = "Platform";

    /** SSM parameter path prefix for shared infrastructure. */
    public static final String SSM_PREFIX = "/express-compute/infra";

    private InfraNaming() {}

    // ── Route Tables ──────────────────────────────────────────────────────────

    public static String publicRouteTableName() {
        return INFRA_PROJECT_NAME + "-public-rt";
    }

    public static String privateRouteTableName() {
        return INFRA_PROJECT_NAME + "-private-rt";
    }

    // ── SSM Parameters ────────────────────────────────────────────────────────

    /** Legacy AMI path (EKS-D, no distribution prefix). */
    public static String ssmAmiPath(String arch, String k8sVersion) {
        return SSM_PREFIX + "/ami/" + arch + "/" + k8sVersion;
    }

    /** Distribution-aware AMI path. */
    public static String ssmAmiPath(ai.codriverlabs.ecp.model.Distribution distribution, String arch, String k8sVersion) {
        return SSM_PREFIX + "/ami/" + distribution.ssmSegment() + "/" + arch + "/" + k8sVersion;
    }

    /** Distribution-aware launch template path. */
    public static String ssmLaunchTemplatePath(ai.codriverlabs.ecp.model.Distribution distribution, String arch, String pricing) {
        return SSM_PREFIX + "/launch-template/" + distribution.ssmSegment() + "/" + arch + "/" + pricing;
    }
}
