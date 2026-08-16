# ADR-0003: Use no default port for authorityless Ceph URIs

- Status: accepted
- Date: 2026-08-15

## Context

The connector supports `ceph:///path`, where monitor endpoints come from the Ceph configuration. A
synthetic URI default port caused Hadoop `FileContext` to compare an authorityless filesystem URI
against paths carrying an implied port and reject valid renames as the wrong filesystem.

## Decision

Return no default port for the `ceph` AbstractFileSystem. Continue to obtain monitor endpoints from
an explicit URI authority or the Ceph client configuration.

## Alternatives considered

- Keep a fixed monitor port: rejected because Ceph monitor topology is configuration data and the
  synthetic authority breaks authorityless URI equality.
- Require an authority in every URI: rejected because authorityless configuration-driven mounts are
  an established connector path.

## Consequences

- Authorityless `FileContext` paths compare consistently with `ceph:///`.
- Mixed-default-filesystem behavior still requires the limitation documented in LIM-008.

## References

- `5bee7d1`
- [E2 rename report](../reports/2026-08-15-e2-spark-rename.md)
