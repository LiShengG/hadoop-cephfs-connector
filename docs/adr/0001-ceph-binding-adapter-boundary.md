# ADR-0001: Isolate the Ceph binding behind an adapter

- Status: accepted
- Date: 2026-07-05

## Context

Hadoop-visible filesystem semantics require extensive unit testing, while the Ceph Java binding
requires JNI and a live cluster. Direct calls from every filesystem and stream class would couple
semantic tests to native infrastructure.

## Decision

Define the connector-required Ceph operations behind `CephFsProto`. Use `CephTalker` as the production
adapter for the official Ceph Java binding and mock the boundary in unit tests. Keep Hadoop semantic
translation in `CephFileSystem` and stream lifecycle in the stream classes.

## Alternatives considered

- Call `CephMount` directly from every class: rejected because it spreads binding details and makes
  fast semantic tests depend on JNI.
- Implement a new JNI binding: rejected because Ceph already supplies a version-matched binding.

## Consequences

- Binding signatures and exception mapping are centralized.
- Unit tests can run without a cluster.
- New binding operations require an intentional boundary change and corresponding tests.

## References

- `7c19e03`
- [`CephFsProto.java`](../../hadoop-cephfs/src/main/java/org/apache/hadoop/fs/ceph/CephFsProto.java)
