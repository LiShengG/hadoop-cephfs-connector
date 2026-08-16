# Architecture

## 1. System boundary

The connector exposes CephFS through Hadoop's `FileSystem` and `AbstractFileSystem` APIs. It adapts
Hadoop path, error, stream, and rename semantics to the Ceph Java binding. It does not implement a
Ceph server, modify Hadoop, or replace Ceph authentication and placement policy.

The current dependency and bytecode baselines are defined by
[`hadoop-cephfs/pom.xml`](../hadoop-cephfs/pom.xml). Compatibility beyond a linked test report is not
implied.

## 2. Components

```text
Hadoop applications and CLI
        |
        +-- FileSystem API ------ CephFileSystem
        |
        +-- FileContext API ----- CephFs (DelegateToFileSystem)
                                      |
                              CephFsProto boundary
                                      |
                                  CephTalker
                                      |
                  libcephfs.jar / JNI / libcephfs
                                      |
                              Ceph MON/MDS/OSD
```

- `CephFileSystem` owns Hadoop-visible filesystem semantics and path translation.
- `CephFs` supplies the `FileContext` entry point and delegates behavior to `CephFileSystem`.
- `CephFsProto` isolates the implementation from the concrete Ceph binding and provides the mock
  boundary used by unit tests.
- `CephTalker` is the production adapter for the Ceph Java binding.
- `CephInputStream` and `CephOutputStream` own file-descriptor and buffered I/O behavior.

See [ADR-0001](adr/0001-ceph-binding-adapter-boundary.md) for the boundary decision.

## 3. Data flow

### 3.1 Adapter outcomes

The adapter preserves the Ceph binding's primitive results for the Hadoop semantic layer. In
particular, listing an existing regular file returns `null`, while missing paths and invalid ancestor
types retain their distinct exceptions. Exact signatures and exception declarations remain owned by
`CephFsProto.java` and the binding source.

### 3.2 Initialization

1. Hadoop resolves the `ceph` implementation from its configuration.
2. The connector normalizes the URI and reads the configured Ceph client identity and mount root.
3. `CephTalker` configures and mounts the Ceph client.
4. The connector maintains a Hadoop working directory independently of the Ceph process working
   directory.

An authority in the URI supplies the monitor endpoint. An authorityless URI relies on the Ceph
configuration. Authorityless URIs have no synthetic default port; see
[ADR-0003](adr/0003-use-no-default-port-for-authorityless-uri.md).

### 3.3 Metadata operations

The Hadoop layer converts paths to mount-relative absolute paths and maps Ceph/POSIX outcomes to
Hadoop-visible return values or exceptions. Recursive delete is implemented above the Ceph empty
directory removal primitive. Status results expose POSIX metadata with the limitations documented
in [KNOWN-LIMITATIONS.md](KNOWN-LIMITATIONS.md).

### 3.4 Data operations

Each input or output stream owns a Ceph file descriptor. Reads support Hadoop seek and positioned
read behavior. Writes use a buffer; `hflush` and `hsync` call the Ceph flush primitive. Close is
idempotent and must release the descriptor even when an earlier operation fails.

Block locations are derived from Ceph file extents and OSD addresses. They are scheduling hints,
not a statement that Hadoop controls Ceph replication.

## 4. Public semantics and invariants

- The filesystem scheme is `ceph`.
- `FileSystem` and `FileContext` must observe compatible path and error semantics.
- The connector never silently overwrites an existing destination for a no-replace rename.
- No-replace rename for regular files uses an atomic MDS hard-link claim followed by source removal.
  Directory and crash-residue boundaries are documented in
  [LIM-005](KNOWN-LIMITATIONS.md#lim-005-no-replace-rename-boundaries). See
  [ADR-0004](adr/0004-atomic-no-replace-for-regular-files.md).
- Recursive deletion never follows a request to remove a non-empty directory unless the caller
  explicitly requested recursion.
- Stream positions and lengths use 64-bit values. Exact method signatures are owned by
  [`CephFsProto.java`](../hadoop-cephfs/src/main/java/org/apache/hadoop/fs/ceph/CephFsProto.java).
- Configuration names and defaults are owned by
  [`CephConfigKeys.java`](../hadoop-cephfs/src/main/java/org/apache/hadoop/fs/ceph/CephConfigKeys.java)
  and the example configuration.
- Reported replication is metadata for Hadoop clients; actual durability is controlled by the Ceph
  pool.
- Ceph credentials are process configuration. Hadoop UGI identities are not automatically
  propagated to CephFS.

## 5. Error and consistency semantics

- Missing paths, existing destinations, non-directory ancestors, and non-empty directories are
  translated to Hadoop-compatible exceptions or documented boolean results.
- Unsupported APIs must fail explicitly or return the Hadoop-defined unsupported value; they must
  not silently claim success.
- `hflush`/`hsync` flush data already accepted by the connector. Visibility to a reader that opened
  the file earlier remains limited; see
  [LIM-002](KNOWN-LIMITATIONS.md#lim-002-open-reader-append-visibility).
- File checksum support is absent and must not be used as an integrity assertion; see
  [LIM-004](KNOWN-LIMITATIONS.md#lim-004-file-checksum-is-unavailable).

Behavioral details are protected by source tests. Stable test IDs and execution environments are
defined in [TEST-CASES.md](TEST-CASES.md) and [TEST-CASES-ECO.md](TEST-CASES-ECO.md).

## 6. Packaging boundary

The connector jar does not embed Hadoop or the Ceph Java binding. `libcephfs.jar` and
`libcephfs_jni.so` are deployed as a matching pair beside the connector. See
[ADR-0002](adr/0002-package-ceph-java-and-jni-separately.md) and [DEPLOY.md](DEPLOY.md).

## 7. Architecture decisions

- [ADR-0001: Isolate the Ceph binding behind an adapter](adr/0001-ceph-binding-adapter-boundary.md)
- [ADR-0002: Package the Ceph Java and JNI binding separately](adr/0002-package-ceph-java-and-jni-separately.md)
- [ADR-0003: Use no default port for authorityless Ceph URIs](adr/0003-use-no-default-port-for-authorityless-uri.md)
- [ADR-0004: Provide atomic no-replace rename for regular files](adr/0004-atomic-no-replace-for-regular-files.md)
