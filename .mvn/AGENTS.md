# Maven bootstrap

Inherited repository and ancestor AGENTS.md constraints remain in force.

## MODULE PURPOSE

Reproducible project-local Maven wrapper configuration.

## RESPONSIBILITIES

Select the declared Maven distribution and verify its checksum; leave host tool configuration untouched.

## IMPLEMENTED ARCHITECTURE

Root mvnw/mvnw.cmd are source-only wrapper launchers using wrapper/maven-wrapper.properties for Maven 3.10.0 and distribution SHA256 verification.

## KEY COMPONENTS

`wrapper/maven-wrapper.properties`, root `mvnw` and `mvnw.cmd`.

## DATA FLOW

Matching JDK + verified cache/download -> selected Maven distribution -> root pom.xml build.

## DEPENDENCIES

Official Maven distribution endpoint, shell/PowerShell wrapper logic and Java 25 toolchain.

## INVARIANTS

No unchecked binary download, version downgrade or system installation as a test workaround. Preserve exact distribution checksum validation.

## CHANGE GUIDELINES

Inspect both platform launchers, offline cache behavior and negative checksum/tool cases before bootstrap edits. Keep machine-local paths out of shared properties.

## RELATED MODULES

[Project build](../pom.xml); [Build callers](../scripts/cli/AGENTS.md); [Wrapper checks](../test/scripts/AGENTS.md).

## KNOWN LIMITATIONS

Wrapper availability does not imply Maven dependencies or the separately built Frontier baseline are cached.
