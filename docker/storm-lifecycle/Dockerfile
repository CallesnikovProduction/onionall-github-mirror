# This local backport changes only Nimbus cleanup/submission synchronization.
# Build via scripts/build-storm-lifecycle.sh after source/jar integrity checks.
ARG STORM_BASE=storm:3.1.0-jre25
FROM ${STORM_BASE}
COPY storm-server-3.1.0.jar /apache-storm/lib/storm-server-3.1.0.jar
ARG LIFECYCLE_REVISION=2
LABEL dev.onionall.storm.lifecycle-fix="cleanup-submit-lock-${LIFECYCLE_REVISION}"
