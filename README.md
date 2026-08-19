# Hadoop CephFS connector

This repository provides Hadoop `FileSystem` and `AbstractFileSystem` backends for the `ceph://`
scheme. Hadoop clients use the connector through the Ceph Java/JNI binding and `libcephfs`.

Production readiness is **NOT_VERIFIED**. Current evidence and open gates are maintained in
[docs/READINESS.md](docs/READINESS.md); active work is tracked in [PROGRESS.md](PROGRESS.md).

## Supported scope

- Hadoop `FileSystem` access through `CephFileSystem`.
- Hadoop `FileContext` access through `CephFs`.
- Metadata operations, buffered input/output streams, append, sync, recursive deletion, and block
  location reporting.
- Hadoop 3.3.6 is the current build baseline. Other compatibility claims require a linked report.
- Ceph authentication uses cephx credentials available to the client process.

Unsupported or restricted semantics are listed in
[docs/KNOWN-LIMITATIONS.md](docs/KNOWN-LIMITATIONS.md). In particular, Kerberos delegation tokens,
portable owner/group mapping, file checksums, and several extended Hadoop filesystem APIs are not
currently supported.

## Current capability boundary

| Capability | Current statement |
|---|---|
| Core metadata and I/O | Implemented and covered by unit/contract baselines; current counts and environments are report evidence, not a rolling README metric |
| FileContext | Implemented through `CephFs`; mixed HDFS-default deployments require an explicit Ceph authority |
| No-replace rename | Regular-file destination claiming is atomic in the tested path; directory and crash-cleanup boundaries remain |
| Data locality | Reports Ceph extent/OSD-derived locations as Hadoop scheduling hints |
| Hadoop ecosystem | Distributed MR/YARN/DistCp and representative Spark/Hive/Tez paths have partial evidence; the required support matrix is incomplete |
| Reliability and performance | Required failure, capacity, latency, and long-running matrices are not verified |
| Security | Uses a process-available cephx identity; Hadoop UGI is not an end-to-end Ceph authorization identity |

This table is a stable scope summary. It does not replace the current gate matrix or dated evidence.

## Quick build

The Ceph Java binding must be available in the local Maven repository before building. See
[docs/DEVELOP.md](docs/DEVELOP.md) and [docs/environments/E1.md](docs/environments/E1.md) for the
required native runtime variables.

```bash
cd hadoop-cephfs
mvn -B clean test
```

Build the deployment archive after placing matching `libcephfs.jar` and `libcephfs_jni.so` files in
`dist/native/`:

```bash
scripts/make-dist.sh
```

Cluster-gated tests are disabled by default. After selecting and validating E1 or E2, invoke them
with explicit Ceph configuration and native-library paths as described in
[docs/DEVELOP.md](docs/DEVELOP.md). Do not infer an integration pass from a unit-only build.

## Shortest deployment path

1. Install a Hadoop 3.3.6 runtime and a compatible `libcephfs` client library.
2. Put the connector jar and `libcephfs.jar` on the Hadoop classpath.
3. Put `libcephfs_jni.so` on `java.library.path` and its native dependencies on the dynamic loader
   path.
4. Add the `ceph` filesystem implementations and cephx client settings to `core-site.xml`.
5. Run `hadoop fs -ls ceph:///`.

Use [docs/DEPLOY.md](docs/DEPLOY.md) for the complete procedure, security constraints, validation,
and rollback conditions.

A minimal Hadoop configuration registers both implementations and supplies the Ceph client
configuration and identity. Configuration names and defaults are owned by
[`CephConfigKeys.java`](hadoop-cephfs/src/main/java/org/apache/hadoop/fs/ceph/CephConfigKeys.java)
and [`conf/core-site.xml.example`](conf/core-site.xml.example); README examples intentionally do not
duplicate that complete list.

For production-shaped evaluation:

- use a dedicated, least-privilege cephx identity rather than an admin credential;
- deploy a version-compatible `libcephfs.jar`/JNI/native client set to every process that can load the
  filesystem;
- use only test-owned CephFS paths for validation and never run E1 lifecycle scripts against E2/E3;
- record exact commands and results in a dated report before changing readiness status;
- stop and investigate stale native sessions instead of bulk-evicting active clients.

## Documentation

| Need | Read |
|---|---|
| Documentation map and ownership | [docs/README.md](docs/README.md) |
| Current work and blockers | [PROGRESS.md](PROGRESS.md) |
| Architecture and semantics | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Build and development | [docs/DEVELOP.md](docs/DEVELOP.md) |
| Deployment and operations | [docs/DEPLOY.md](docs/DEPLOY.md) |
| Test strategy and gates | [docs/TESTING.md](docs/TESTING.md) |
| Core test plan | [docs/TEST-PLAN.md](docs/TEST-PLAN.md) |
| Ecosystem test definitions | [docs/TEST-CASES-ECO.md](docs/TEST-CASES-ECO.md) |
| Production readiness | [docs/READINESS.md](docs/READINESS.md) |
| Current limitations | [docs/KNOWN-LIMITATIONS.md](docs/KNOWN-LIMITATIONS.md) |

Historical reports, ADRs, and resolved-item indexes are linked from the documentation map and are
not required for ordinary development tasks.
