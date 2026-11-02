# Frontier admission: source/build feasibility and implementation boundary

2026-11-03. **Build feasibility verified; admission NOT implemented.** ADR0002
remains fail-closed and ADR0003 remains proposed. This investigation changes no
production class, image, API, volume or admission mode. It is independent of the
still-failing M2 restart prerequisite.

## Exact owning source and artifact

- Official upstream `crawler-commons/url-frontier`, tag2.4, commit
  `27363207fdb68abad7606cf249d93be55509d71a`.
- Cached image `crawlercommons/url-frontier:2.4`, repository digest
  `sha256:000f2c942778718f7f6554cb4b10b004df0eaad794f5fe46b3689239024b2095`;
  its OCI revision equals that source commit. Baseline image reports Java11.
- `/home/urlfrontier/urlfrontier-service.jar` extracted from a stopped,
  network-none helper in unique Compose project `onionall-brawler-m2-968f6efceb5d`.
  No existing service was touched. Helper was removed; no volume was attached.
- Service JAR SHA256:
  `ff1b774bf81343234e6f34878f8b7c9a826371b3e305e96bf0e204895e3562df`.
- Ignored evidence root:
  `.runtime/autonomous-recovery/frontier-build-feasibility/onionall-brawler-m2-968f6efceb5d/`.

The earlier missing offline Maven plugin does **not** prevent a minimal source
patch build. All14 unchanged service production Java files compile with the
selected JDK25 `javac --release 11`, using the exact fat service JAR as classpath.
This proves a source-pinned patch route analogous to the existing Nimbus patch;
it does not reproduce the full upstream Maven reactor or certify a new image.

Nine unchanged upstream `RocksDBServiceTest` tests were compiled and executed
against those recompiled classes and the image's JNI/dependency implementation:
**9 found/started/succeeded, zero failures/skips**. Local cached JUnit5.12.2 /
Platform1.12.2 were used (upstream POM specifies JUnit5.11.0). Initial execution
failed because the scratch `target/` parent was absent; that preparation error is
preserved as `storage-tests-preparation-fail.log`. After creating the new scratch
parent, `storage-tests.log` passes. No test source was edited. JNI extraction
works on this host/JDK25, with native-access/Unsafe future-compatibility warnings.
This is not Java11-container execution or admission conformance.

## Reproducing the already-executed compilation

From repository root, after recovering the pinned source/artifact above:

```sh
evidence=.runtime/autonomous-recovery/frontier-build-feasibility/onionall-brawler-m2-968f6efceb5d
"$JAVA_HOME/bin/javac" --release 11 \
  -cp "$evidence/urlfrontier-service.jar" -d "$evidence/classes" \
  @"$evidence/sources.txt"
```

`sources.txt` contains the14 pinned service source paths. `compile-result.json`
records the exact command, file count and input hash. `storage-test-result.json`
records test compilation/initial execution; `storage-test-prepared-result.json`
records the passing prepared execution including its complete classpath. The
small `RunStorageTests.java` launcher selects only the unchanged storage test
class and fails unless all9 tests succeed. All generated classes and scratch
artifacts remain ignored. No downloaded dependency or source was introduced by
this investigation.

## Confirmed storage and migration constraints

Owning class:
`service/src/main/java/crawlercommons/urlfrontier/service/rocksdb/RocksDBService.java`
in the pinned upstream source.

1. `putURLItem` already owns a WriteBatch for the default-CF existence pointer
   and queues-CF scheduling row. A separate reserve call followed by invoking
   this method is **not** atomic. Its per-URL interned-string monitor does not
   serialize two different URLs competing for one session quota.
2. QueueMetadata counters/hold removal are mutated before `rocksDB.write`.
   A patch must build mutations, commit the complete batch, then publish the
   in-memory delta. Injected write failure must leave both durable rows and the
   current process's scheduling/counter view consistent; restart is not a fix.
3. DB open enumerates exactly `default`, `queues`, `queueInfos`. Their recovery
   scans interpret keys as existing queue encodings; arbitrary admission keys
   cannot safely be hidden in those namespaces.
4. A separate versioned CF is the cleanest storage boundary but changes rollback.
   A local negative probe created a new scratch DB with those three CFs plus
   `admissionV1`, then opened it with the unchanged recompiled2.4 service.
   **Expected refusal reproduced:** `Column families not opened: admissionV1`.
   All four CFs remain present; no destructive rollback/drop was attempted.
   Evidence: `cf-rollback.log`, `cf-rollback-result.json`, retained scratch DB.

Therefore an additive RPC does not by itself preserve binary rollback to stock
2.4 on an upgraded data directory. Before any live migration, provide a compatible
rollback build that opens/preserves the added CF, disables new admission, and
retains protected-crawl quota guards. Do not launch stock2.4 against migrated
state, drop the CF, or convert protected sessions into unrestricted legacy crawls.
Migration rehearsal must use a copy/new test volume; never mutate current M1/M2
volumes as a feasibility experiment.

## Smallest coherent implementation increment

Implement in the owning pinned server source, initially disabled, retaining
stock behavior for all non-session crawl IDs:

1. Open a versioned admission CF and rebuild immutable session ownership before
   accepting RPCs. Use unambiguous length-delimited keys. A session owns one new
   crawl ID; reject adoption of legacy records. Start with a single frontier
   server only: reject durable activation with cluster/shard routing configured,
   rather than imply a transaction across independent RocksDB nodes.
2. Refactor existing enqueue code into an internal batch participant. One
   server-owned session lock covers lookup, quota check, provenance validation,
   reservation/counter/ordinary scheduling batch commit with WAL+sync enabled,
   and publication of queue metadata. Lock ordering must be session→URL wherever
   both are required; avoid an inverse order through legacy Known updates.
3. First commit consumes seed/unique quota; replay returns the same stable
   reservation ID/ordinal without another enqueue/count. Transport timeout cannot
   cause a new session or unique slot. Fetch/robots failure never releases quota.
4. In protected crawl IDs, reject legacy Discovered creation and unknown Known
   creation. Existing reserved Known updates remain quota-neutral and compatible
   with the installed status updater. Guard deleteQueue/deleteCrawl, active-state
   changes, purge flags and administrative writes; no side path may erase or
   recreate protected admission state outside the session contract.
5. Register the additive service in `URLFrontierServer.start` on the existing
   internal port. Require explicit operator authority for OpenSession/Seal and
   session-scoped pipeline authority for reserve/read/update. Reject activation
   without configured authentication/capabilities; no host port or public RPC
   is introduced. A caller-supplied digest/metadata is not authority. Source/depth,
   v3 checksum, scheme, canonical URL and allowlist must be revalidated server-side.
6. Only after conformance, add the application adapter/capability check. Old
   servers fail closed. CLI discover remains offline; local-test mode is retained
   and never silently substituted for durable mode.

The image's Java11 baseline cannot directly load this application's Java25
classes. Policy reuse must explicitly resolve that runtime boundary without
adding all Storm dependencies to the server or silently relaxing validation.
Either compile a small shared pure-policy surface to the compatible release or
qualify an isolated Java25 server candidate with the same pinned service/JNI
dependencies. The local Java25 storage tests above are useful evidence, not that
deployment compatibility gate.

## Necessary conformance before activation

- Concurrent distinct/duplicate writers at quotaN: exactlyN reservations and
  ordinary queue rows, including seed; no overshoot or duplicate provenance.
- Precommit failure versus fsync-success/ACK-loss: restart yields neither row or
  both; replay returns identical reservation identity and one enqueue/count.
- Commit failure keeps QueueMetadata consistent immediately, without restart.
- Crash/reopen rebuilds dequeueable scheduling and reservation counters; worker
  lease expiry/redelivery remains at-least-once, not exactly-once fetching.
- Unknown/sealed/conflicting sessions, unauthorized callers, bad scope/depth,
  unknown Known/Discovered legacy submissions and destructive admin calls leave
  admission/quota/queue unchanged.
- Added-CF upgrade plus compatible rollback preserve all old records and reject
  protected-crawl bypass. M1/M2 legacy snapshots/API behavior stay unchanged.
- Only then real Tor M3 A→B→C and interruption/replay tests after strict M2 passes.

No new admission algorithm is implemented here. Proposed per-session serialization
has O(1) lock ownership and bounded provenance per operation; durable storage is
O(unique reservations + bounded edges). Its throughput tradeoff and lock ordering
need tests before adoption. No independent queue or second database is required.

## Remaining build prerequisites and next executable step

Current system protoc is `libprotoc36.1`; no local `grpc_java_plugin` or cached
`protoc-gen-grpc-java` executable was found. Do not generate new bindings with an
arbitrary modern compiler against the old bundled protobuf runtime. Obtain/pin
compatible protobuf/gRPC generators through the normal authenticated dependency
workflow, or a reviewed reproducible generation context, before the new API build.
Full Maven reactor dependencies/plugins are still absent offline; the successful
source-patch route removes that as a blocker for **service source compilation**,
not for every proposed new tool or source generator.

The next implementation checkpoint should create the pinned patch/build harness
and a minimal server transaction conformance test in an isolated candidate,
starting with duplicate/quota/write-failure cases before API/client activation.
Do not call the absence of the original Maven plugin an inability to build the
server; do not call these successful baseline tests durable admission completion.
