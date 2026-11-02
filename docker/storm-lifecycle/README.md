# Storm 3.1 cleanup/submission lifecycle backport

This is an opt-in Java25 image candidate, not a dependency version upgrade.
`storm:3.1.0-jre25` remains the unmodified baseline. The candidate changes only
Nimbus and its compiler-generated nested classes; no logger/timer, topology,
network, robots or acceptance assertion is disabled.

## Cause and scope

In the pinned Apache Storm 3.1 source, LocalFsBlobStore.createBlob writes metadata
and publishes the key to ZooKeeper before the caller closes the data stream.
Nimbus topology submission holds `submitLock` during this publication. Concurrent
`doCleanup` does not hold that lock, and its referencedDependencyKeys pass reads
an in-progress stormcode.ser. The observed sequence is publication at10:22:59.088,
cleanup FileNotFoundException at10:22:59.089, and completed topology submission
at10:22:59.103. No crawler shutdown or frontier operation is involved in this
startup overlap.

Revision1 serializes the entire existing cleanup operation on Nimbus's existing
submission/transition lock. Cleanup still runs and still logs genuine errors;
it observes completed publication, rather than partially-created blobs. The
tradeoff is that submissions/transitions wait for an active cleanup pass, and
cleanup waits for submission. No new lock or supervisor is introduced; the existing lock now covers cleanup
blob I/O, so submission latency is part of the compatibility assessment.

Relevant upstream source (actual retrieved content is hash-pinned; line numbers
in cached browser renderings may differ):

- https://raw.githubusercontent.com/apache/storm/v3.1.0/storm-server/src/main/java/org/apache/storm/daemon/nimbus/Nimbus.java
- https://raw.githubusercontent.com/apache/storm/v3.1.0/storm-server/src/main/java/org/apache/storm/blobstore/LocalFsBlobStore.java

Revision2 also corrects shutdown ordering. Actual cleanup1 runfcab856c3399
showed timer.close interrupting an active ZooKeeper getChildren during cleanup.
A volatile stop flag prevents new cleanup dependency I/O, and shutdown drains the
submission lock before closing cleanupTimer and other dependencies. Timer.close
runs outside the lock, so joining a queued task cannot deadlock on that lock.
Existing dependency timeouts remain authoritative; no ERROR is swallowed and no
sleep, log-level change or timer suppression is introduced.

## Build and proof

Select Java25 via JAVA_HOME and run:

```sh
bash scripts/build-storm-lifecycle.sh
M1_CACHED_IMAGES=1 M1_STORM_IMAGE=onionall-storm:3.1.0-jre25-cleanup2 bash scripts/m1.sh
# Preserve/reproduce the first publication-only candidate independently:
# STORM_LIFECYCLE_REVISION=1 bash scripts/build-storm-lifecycle.sh
```

The builder requires the existing local baseline image, copies only its library
files from a network-disabled temporary container, and downloads the exact
Apache source over normal verified HTTPS if not already cached. An approved
offline source file may be supplied with STORM_NIMBUS_SOURCE; the identical
SHA256 is enforced. Source SHA256 is
`0154b08f750c29ef7c049c3560c50520213fe256bcae2acf566d44b702d27460`;
original server JAR SHA256 is
`68c542091e40d0d974601fe852787965f2723e7c45bbd6fd981c48ff3307ef26`.
Unexpected source or baseline fails closed. Patch has zero context because
source integrity is independently fixed, and avoids whitespace-only patch data.

Only Nimbus.java is compiled against the exact baseline classpath. The copied
JAR receives those classes with fixed ZIP timestamps; the image build has no
network. Receipts, original/patched hashes, base/candidate image IDs and logs
remain under ignored `.runtime/storm-lifecycle-build-cleanup<revision>/` (the
initial revision1 build also exists under `.runtime/storm-lifecycle-build/`). No persistent volumes
are mounted, overwritten or deleted by the builder. The resulting class files
require Java25, matching this migration branch; this is not a Java17 image.

NimbusCleanupLockProbe invokes the actual Nimbus implementation with a test-only
allocated object and an inert non-leader elector (no ZooKeeper or networking).
It holds the same submitLock from another thread. The original JAR must fail
with the exact overlap AssertionError; the patched implementation must block
while publication is locked and complete after release. This proves exclusion
of the offending overlap; it is not by itself full M1/M2 acceptance. Fresh strict
M1 runs, persistence, cleanup and zero-ERROR logs remain required.

NimbusCleanupShutdownProbe uses real Nimbus and StormTimer plus a blocking inert
leader dependency. Legacy timer.close interrupts the active lookup and fails with
an exact AssertionError. Revision2 shutdown helper must instead wait until the
lookup is explicitly released, close the timer, and reject a late cleanup task
before any dependency access. This is a deterministic test of the observed
shutdown overlap, alongside the independent publication-lock test.

## Actual M1 repeatability

Revision1:93e27294af36 PASS, thenfcab856c3399 FAIL on a different shutdown
interruption (both preserved). Revision2:25da2046fecb andd927d1d8835e both
PASS strict unchanged verification, all evidence flags true, six persisted
records, pending resume and zero unexpected ERROR/FATAL. Minimum gaps2.002548506
and2.002844937s. Containers/networks removed and both frontier volumes retained.
These results do not establish M2 Tor recovery or M3 acceptance.
