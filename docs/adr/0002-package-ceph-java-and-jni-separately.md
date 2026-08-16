# ADR-0002: Package the Ceph Java and JNI binding separately

- Status: accepted
- Date: 2026-07-07

## Context

The connector depends on Hadoop at runtime and on a Ceph Java/JNI pair that must match the native
client. Embedding those artifacts into one jar would hide version and license boundaries and could
conflict with libraries supplied by a Hadoop or Ceph distribution.

## Decision

Keep Hadoop as a provided dependency. Distribute the connector jar, `libcephfs.jar`, and
`libcephfs_jni.so` as separate files. Require the Ceph Java and JNI files to come from a compatible
build and deploy them together.

## Alternatives considered

- Shade Hadoop and the Ceph Java binding into the connector: rejected because of class conflicts,
  artifact size, and independent dependency ownership.
- Embed only the JNI file in the jar: rejected because native extraction and loader behavior would
  obscure deployment and version pairing.

## Consequences

- Operators must configure both Java and native library paths.
- The distribution can replace a matching Ceph pair without rebuilding Hadoop dependencies.
- Artifact compatibility remains a release gate.

## References

- `31cdd6e`
- [`scripts/make-dist.sh`](../../scripts/make-dist.sh)
