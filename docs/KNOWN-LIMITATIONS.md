# Known limitations

Only limitations that currently apply belong here. Resolved limitations move to the monthly archive
and, when necessary, retain their evidence in a dated report.

## LIM-001: Owner and group mapping

- Status: OPEN
- Affected scope: status ownership, group-based `access()`, `setOwner`, Hive/YARN ownership checks
- Trigger: a Hadoop user or group name cannot be represented by the connector's numeric CephFS model
- Impact: status may expose numeric IDs; group permission prechecks can reject a permitted POSIX user;
  non-numeric chown requests can leave ownership unchanged without a useful failure
- Workaround: use numeric ownership where the workflow permits it and validate effective cephx/path
  access instead of relying on Hadoop owner strings
- Planned resolution: define and implement a portable identity-mapping and failure policy
- Tracking: SEC-01, SEC-07, SP-01, SP-02, SP-03, SP-08B
- Last verified: [2026-08-14 E1 ecosystem spikes](reports/2026-08-14-e1-ecosystem-spikes.md)

## LIM-002: Open reader append visibility

- Status: OPEN
- Affected scope: readers following a file while another process appends, including WAL-like use
- Trigger: a reader remains open while a writer extends the same file
- Impact: the open reader can observe only the length captured at open time; reopening exposes the
  appended data
- Workaround: close and reopen the input stream before reading newly appended data
- Planned resolution: decide whether stream length refresh can preserve Hadoop seek/read invariants
- Tracking: SP-05, ECO-HB-02, ECO-HB-03
- Last verified: [2026-08-14 E1 ecosystem spikes](reports/2026-08-14-e1-ecosystem-spikes.md)

## LIM-003: Authentication and delegation-token boundary

- Status: OPEN
- Affected scope: Kerberos clusters, YARN credential propagation, proxy users, multi-tenant jobs
- Trigger: a job expects a Hadoop delegation token or expects UGI identity to become the CephFS user
- Impact: the connector returns no delegation token and uses the cephx identity available to the
  client JVM; multiple Hadoop users can therefore share one Ceph identity
- Workaround: distribute a least-privilege keyring to each required node and isolate tenants with
  separate cephx identities, path-restricted capabilities, and configured mount roots
- Planned resolution: complete isolated Kerberos and multi-tenant tests before declaring support
- Tracking: SEC-01–SEC-06, SP-06, ECO-SEC-01–05
- Last verified: [2026-08-15 E3 ecosystem](reports/2026-08-15-e3-distributed-ecosystem.md)

## LIM-004: File checksum is unavailable

- Status: OPEN
- Affected scope: `getFileChecksum`, DistCp update/verification, workflows that infer content equality
- Trigger: the caller compares existing source and destination files using the Hadoop checksum API
- Impact: a null checksum can cause DistCp `-update` to skip equal-length files whose contents differ
- Workaround: do not use `-update` as an integrity check; use an independent content digest or force a
  copy when correctness requires byte comparison
- Planned resolution: define a compatible checksum implementation or keep the limitation explicit
- Tracking: SP-07, ECO-DCP-02, ECO-CLI-07
- Last verified: [2026-08-15 E3 ecosystem](reports/2026-08-15-e3-distributed-ecosystem.md)

## LIM-005: No-replace rename boundaries

- Status: OPEN
- Affected scope: no-replace rename, legacy boolean rename, directory commits, crash recovery, Spark
  checkpoint cleanup
- Trigger: renaming a directory, crashing after the regular-file hard-link claim, losing a race to an
  existing destination, or having a destination appear between the legacy API's precheck and rename
- Impact: regular-file `FileContext` no-replace is atomic at the claim step, but a crash can leave both
  names; directories do not use the hard-link strategy; the legacy boolean API can overwrite a
  destination created in its race window; a failed Spark competitor can retain a temporary source
- Workaround: use `FileContext` no-replace for regular-file publication; validate both names after a
  crashed commit and remove only the known source; let the owning job or operator clean its temporary
  file; do not assume directory or legacy boolean no-replace atomicity
- Planned resolution: add destination-race, directory, and failure-recovery cases and define cleanup
  ownership
- Tracking: SP-04, ECO-SPK-02, ECO-SPK-03
- Last verified: the regular-file `FileContext` path in
  [2026-08-15 E2 Spark rename](reports/2026-08-15-e2-spark-rename.md); the legacy destination race is
  from source inspection at `6e4e5bc` and has no dated report

## LIM-006: Native session lifecycle

- Status: OPEN
- Affected scope: YARN containers, JobHistoryServer, Hive, Tez, Spark, short-lived CLI clients
- Trigger: client JVMs exit after creating CephFS mounts through Hadoop services
- Impact: dead client sessions can remain at the MDS, retain caps, and delay later native filesystem
  calls for minutes
- Workaround: identify the owning PID and in-flight requests before evicting an exact session; never
  bulk-evict sessions or evict a live service client
- Planned resolution: determine the close/lifecycle owner and pass SOAK-04/SOAK-05
- Tracking: SOAK-04, SOAK-05, ECO-YARN-03
- Last verified: [2026-08-15 E3 Hive Tez](reports/2026-08-15-e3-hive-tez.md)

## LIM-007: Unsupported filesystem capabilities

- Status: OPEN
- Affected scope: `concat`, `truncate`, ACL, XAttr, symlink, snapshot, multi-filesystem selection,
  mixed-version rolling update
- Trigger: an application calls one of these APIs or requires a CephFS filesystem other than the
  configured default
- Impact: the operation is unsupported or lacks compatibility evidence
- Workaround: use supported create/append/rename operations, an external data migration procedure,
  or a separate configured client deployment
- Planned resolution: each capability requires an explicit semantic design and matching tests
- Tracking: NEG-02–NEG-05, NEG-08, OPS-04
- Last verified: source capability declaration; no dated matrix report

## LIM-008: Mixed default filesystem requires explicit Ceph authority

- Status: OPEN
- Affected scope: Hadoop `FileContext` services when HDFS is `fs.defaultFS`
- Trigger: a component opens an authorityless `ceph:///` path while the default filesystem has an
  HDFS authority
- Impact: Hadoop 3.3.6 can inherit the HDFS authority and direct the Ceph request to the wrong endpoint
- Workaround: use an explicit `ceph://<monitor-host>:<monitor-port>/...` URI in mixed deployments
- Planned resolution: isolate the Hadoop URI-resolution behavior and define a compatible connector
  guard or documented support boundary
- Tracking: ECO-MIX-01, ECO-MIX-02
- Last verified: [2026-08-15 E3 ecosystem](reports/2026-08-15-e3-distributed-ecosystem.md)

## LIM-009: Replacing rename is not atomic

- Status: OPEN
- Affected scope: `FileContext` rename with `Options.Rename.OVERWRITE`, commit protocols that publish
  a result by overwriting an existing path
- Trigger: a caller requests an overwriting rename and the destination already exists
- Impact: the connector delegates to the base class, whose default implementation removes the
  destination before renaming the source onto it; the destination is therefore transiently absent, and
  a crash or a failing rename inside that window leaves the destination gone while the source remains
- Workaround: publish through a no-replace rename onto a fresh path, which the connector claims
  atomically, and remove the previous path afterwards; do not treat an overwriting rename as a
  publish-or-nothing step
- Planned resolution: decide whether an overwriting rename warrants a connector-side atomic strategy or
  a documented support boundary, then cover FN-31
- Tracking:
  - CT-02 — sequential functional compatibility after overwriting an existing destination
  - FN-31 — atomic visibility and the delete-to-rename crash window
- Last verified: connector delegation read from source; no dated report covers the base-class window

## LIM-010: Legacy directory self-rename diverges from HDFS

- Status: OPEN
- Affected scope: `FileSystem.rename(dir, dir)` through the legacy boolean API
- Trigger: source and destination name the same existing directory
- Impact: the connector returns `true` before applying destination-directory expansion, while HDFS
  3.3.6 expands the destination to `dir/basename(dir)` and returns `false`; callers therefore observe a
  different success result even though neither filesystem changes the directory
- Workaround: avoid issuing directory self-renames; compare normalized paths before calling rename when
  the return value drives caller control flow
- Planned resolution: move the same-path decision after source-type and destination-directory handling,
  then add an exact unit guard
- Tracking: no stable test ID yet; the uncovered decision is `SEM-RENAME-004`
- Last verified: source inspection at `6e4e5bc`; no dated report covers this condition

## LIM-011: Create flag validation and overwrite race

- Status: OPEN
- Affected scope: `createNonRecursive` flag overloads and concurrent overwriting create
- Trigger: a caller supplies an unsupported `CreateFlag`, or another client creates the destination
  after the connector's existence check
- Impact: unsupported flags can be ignored instead of rejected; an overwriting create can fail with
  `FileAlreadyExistsException` rather than atomically truncating the concurrently created file
- Workaround: use only CREATE and OVERWRITE through the supported entry points, and retry a failed
  overwriting create only after checking destination ownership and content
- Planned resolution: validate the complete flag set before I/O and move overwrite arbitration into an
  atomic native operation or document the final support boundary
- Tracking: `SEM-CREATE-013`, `SEM-CREATE-015`; FN-17 covers no-overwrite contention, while no stable
  test ID currently covers unsupported flag validation or the overwrite precheck race
- Last verified: connector and Hadoop/HDFS 3.3.6 source inspection; no dated report covers either race

## LIM-012: Root and recursive delete differ from HDFS atomicity

- Status: OPEN
- Affected scope: non-empty root deletion and recursive directory deletion
- Trigger: deleting the root through either recursive mode, or encountering an error after a recursive
  traversal has already removed children
- Impact: the connector returns `false` instead of the HDFS non-empty-root exception for a
  non-recursive call; a recursive root call clears children and returns `true` where HDFS preserves
  them and returns `false`; any recursive traversal can expose a partially deleted tree after failure
- Workaround: never use the filesystem root as a cleanup target; delete a test-owned non-root subtree
  and treat recursive deletion as non-atomic
- Planned resolution: align root outcomes with HDFS and define retry/ownership rules for partial
  recursive cleanup
- Tracking: `SEM-DELETE-005` through `SEM-DELETE-009`; FN-16 covers concurrent creation, while no
  stable test ID currently injects recursive-delete failure
- Last verified: connector and HDFS 3.3.6 source inspection; failure injection is not implemented

## LIM-013: Concurrent append writers are not lease-exclusive

- Status: OPEN
- Affected scope: multiple clients appending to one file and `FSDataOutputStream#getPos()` reporting
- Trigger: a second append stream opens while another writer is extending the same file
- Impact: HDFS rejects the second writer through lease ownership, while the connector can open both;
  each stream reports the length snapshot captured before its open, even as native `O_APPEND` writes at
  the current end
- Workaround: enforce one append writer per path outside the connector and do not use `getPos()` as a
  shared-file length under concurrent append
- Planned resolution: add an exact concurrency test and choose either lease-like exclusion or a
  documented multi-writer position model
- Tracking: `SEM-APPEND-004`; no stable test ID currently covers concurrent append writers
- Last verified: connector source and HDFS 3.3.6 append tests; no connector concurrency test exists
