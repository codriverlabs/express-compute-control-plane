# Community Edition: Source-Release Archive Support

## Context

The PRO edition needs to rebuild community Lambda functions and containers as GraalVM native binaries. To avoid forking community source code, community should publish **buildable source tarballs** as Maven artifacts alongside the existing JVM outputs.

This follows the Apache Software Foundation's source-release pattern — the canonical release format is source, and downstream consumers (PRO) rebuild from it.

## Scope

Add `maven-assembly-plugin` to **5 modules** that PRO needs to rebuild natively:

| Module | artifactId | Current output |
|--------|-----------|----------------|
| `ecp-credential-service` | `ecp-credential-service` | Lambda `function.zip` (JVM) |
| `ecp-tenant-service` | `ecp-tenant-service` | Lambda `function.zip` (JVM) |
| `ecp-auth-proxy` | `ecp-auth-proxy` | Container image |
| `ecp-workload-identity-webhook` | `ecp-pod-identity-webhook` | Container image |
| `ecp-karpenter-support` | `ecp-karpenter-support` | Container image |

**NOT in scope** (PRO consumes these as library jars or replaces them):
- `ecp-model` — library jar, consumed as compile dependency
- `ecp-api` — library jar, consumed as compile dependency
- `ecp-mgmt-service` — PRO replaces with `ecp-pro-mgmt-service`
- `ecp-cli` — PRO extends via `ecp-pro-cli` (already has source access)
- `infra` — CDK module, PRO has its own `infra-pro/`

## Implementation

### Step 1: Create the assembly descriptor

In each of the 5 modules, create the file `src/assembly/source-release.xml`:

```xml
<assembly xmlns="http://maven.apache.org/ASSEMBLY/2.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/ASSEMBLY/2.2.0
          http://maven.apache.org/xsd/assembly-2.2.0.xsd">
  <id>source-release</id>
  <formats>
    <format>tar.gz</format>
  </formats>
  <includeBaseDirectory>true</includeBaseDirectory>
  <fileSets>
    <fileSet>
      <includes>
        <include>pom.xml</include>
        <include>src/**</include>
      </includes>
    </fileSet>
  </fileSets>
</assembly>
```

The file is identical across all 5 modules — copy it into each.

### Step 2: Add the plugin to each module's `pom.xml`

Add this to the `<build><plugins>` section of each module:

```xml
<plugin>
  <artifactId>maven-assembly-plugin</artifactId>
  <version>3.7.1</version>
  <executions>
    <execution>
      <id>source-release</id>
      <phase>package</phase>
      <goals><goal>single</goal></goals>
      <configuration>
        <descriptors>
          <descriptor>src/assembly/source-release.xml</descriptor>
        </descriptors>
      </configuration>
    </execution>
  </executions>
</plugin>
```

That's it. No other changes required.

## What this produces

On `mvn package` (or `mvn deploy` during release), each module emits an **additional** artifact with classifier `source-release`:

```
target/ecp-credential-service-1.1.4-source-release.tar.gz
target/ecp-tenant-service-1.1.4-source-release.tar.gz
target/ecp-auth-proxy-1.1.4-source-release.tar.gz
target/ecp-pod-identity-webhook-1.1.4-source-release.tar.gz
target/ecp-karpenter-support-1.1.4-source-release.tar.gz
```

Maven coordinates for downstream consumption:

```
ai.codriverlabs:ecp-credential-service:1.1.4:tar.gz:source-release
ai.codriverlabs:ecp-tenant-service:1.1.4:tar.gz:source-release
ai.codriverlabs:ecp-auth-proxy:1.1.4:tar.gz:source-release
ai.codriverlabs:ecp-pod-identity-webhook:1.1.4:tar.gz:source-release
ai.codriverlabs:ecp-karpenter-support:1.1.4:tar.gz:source-release
```

### Tarball contents

Each tarball is a self-contained buildable source tree:

```
ecp-credential-service-1.1.4-source-release/
├── pom.xml                                    # Full build descriptor with native profile
├── src/
│   ├── main/
│   │   ├── java/...                           # Application source code
│   │   └── resources/
│   │       ├── application.properties         # Quarkus configuration
│   │       └── META-INF/native-image/         # GraalVM reflection configs (if present)
│   │           ├── reflect-config.json
│   │           ├── resource-config.json
│   │           └── serialization-config.json
│   └── test/...                               # Tests (for verification builds)
```

## What does NOT change

- **No CI workflow changes** — assembly runs during the existing `package` phase
- **No `release.yml` changes** — source-release artifacts publish automatically via `mvn deploy`
- **No GraalVM or Mandrel in community CI** — native compilation is exclusively a PRO concern
- **No new dependencies** — `maven-assembly-plugin` is a core Maven plugin
- **Existing outputs unaffected** — `function.zip` and container images continue to build identically

## Verification

After implementation, verify in each module:

```bash
# 1. Build produces the tarball
mvn -pl ecp-credential-service package -DskipTests
ls ecp-credential-service/target/*source-release*
# → ecp-credential-service-1.1.4-SNAPSHOT-source-release.tar.gz

# 2. Tarball is self-contained and compilable
mkdir /tmp/verify && cd /tmp/verify
tar xzf /path/to/ecp-credential-service-1.1.4-SNAPSHOT-source-release.tar.gz
cd ecp-credential-service-*
mvn compile -DskipTests
# Should resolve deps from Maven Central + GitHub Packages and compile cleanly
```

## How PRO consumes these

PRO CI downloads the source-release archives and rebuilds natively:

```bash
# Download
mvn dependency:copy \
  -Dartifact=ai.codriverlabs:ecp-credential-service:1.1.4:tar.gz:source-release \
  -DoutputDirectory=./native-sources/

# Unpack
tar xzf native-sources/ecp-credential-service-1.1.4-source-release.tar.gz

# Build natively (GraalVM/Mandrel — PRO toolchain only)
cd ecp-credential-service-1.1.4
mvn package -Pnative -DskipTests
# → target/function.zip (native arm64 binary)
```

## File checklist

After this change, the following files should be new or modified:

```
ecp-credential-service/
├── pom.xml                          # MODIFIED — added assembly plugin
└── src/assembly/source-release.xml  # NEW

ecp-tenant-service/
├── pom.xml                          # MODIFIED — added assembly plugin
└── src/assembly/source-release.xml  # NEW

ecp-auth-proxy/
├── pom.xml                          # MODIFIED — added assembly plugin
└── src/assembly/source-release.xml  # NEW

ecp-workload-identity-webhook/
├── pom.xml                          # MODIFIED — added assembly plugin
└── src/assembly/source-release.xml  # NEW

ecp-karpenter-support/
├── pom.xml                          # MODIFIED — added assembly plugin
└── src/assembly/source-release.xml  # NEW
```

Total: 5 files modified, 5 files created. No logic changes, no new dependencies, no CI changes.
