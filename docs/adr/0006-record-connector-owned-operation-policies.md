# ADR-0006: Record connector-owned operation policies

- Status: accepted
- Date: 2026-08-20

## Context

The Hadoop FileSystem specification and HDFS 3.3.6 define most externally visible outcomes for
create, delete, sync, append, and mkdirs. They do not define Ceph layout selection, selection among
configured Ceph data pools, or the adapter's behavior after partial native I/O and multiple cleanup
failures. Treating those implementation choices as if they came from Hadoop or HDFS would make the
semantic baseline misleading.

These policies already exist in connector source at the structured-catalog migration baseline. This
ADR records their rationale; it does not change runtime behavior or replace the exact decision records
in `catalog.ndjson`.

## Decision

- Treat a caller's create block size as a scheduling/layout hint. Convert it to a valid Ceph layout
  without arithmetic overflow rather than forwarding a value that the native layer will reject.
- When multiple data pools are configured, select the first non-empty entry. Configuration source and
  defaults remain owned by `CephConfigKeys.java`.
- Report an empty file as length zero and immediate EOF. When an external POSIX writer creates a
  sparse file, expose the hole as zero bytes, include it in file length, and keep block-location
  queries usable for both empty and sparse files.
- Complete partial positive native writes before reporting the Java write complete. Reject zero,
  negative, or oversized native write results as I/O failures.
- Let ordinary `flush()` drain the connector buffer without claiming persistence, and let `hsync()`
  issue the native sync even when that buffer is already empty.
- On stream close, preserve the first write/sync failure, always attempt descriptor release, and retain
  a later release failure as suppressed detail.
- Keep crash consistency, performance, and backend namespace-boundary probes as explicit validation
  goals. A pre-sync crash may lose data, but any recovered length and content must describe one
  internally consistent state. These goals do not become claims of Hadoop or HDFS compatibility until
  their catalog records have evidence.

## Alternatives considered

- Label these policies `HADOOP-SPEC`: rejected because the referenced Hadoop material does not define
  the Ceph-specific choice.
- Use connector source as the expected-behavior authority: rejected because source owns current
  behavior, not the reason a project-specific expectation was selected.
- Omit the decisions from the semantic baseline: rejected because callers can observe layout errors,
  output positions, failure precedence, and resource cleanup.

## Consequences

- Project-owned expectations have an explicit Basis distinct from upstream compatibility claims.
- Changing one of these policies requires updating the corresponding semantic record and this ADR or
  a successor decision.
- Tests can separately guard adapter mechanics and end-to-end Hadoop-visible outcomes without
  overstating what a mock assertion proves.

## References

- [`catalog.ndjson`](../catalog.ndjson)
- [`CephFileSystem.java`](../../hadoop-cephfs/src/main/java/org/apache/hadoop/fs/ceph/CephFileSystem.java)
- [`CephOutputStream.java`](../../hadoop-cephfs/src/main/java/org/apache/hadoop/fs/ceph/CephOutputStream.java)
