# ADR-0004: Provide atomic no-replace rename for regular files

- Status: accepted
- Date: 2026-08-15

## Context

A pre-check followed by POSIX rename has a race: multiple writers can observe a missing destination,
then overwrite it. Spark checkpoint commits require one winner and an existing-destination failure
for other writers.

## Decision

For a regular-file no-replace operation, create an MDS hard link at the destination as the atomic
claim, then remove the source name. Preserve the existing directory path because directories cannot
be hard-linked. If the source was concurrently removed after a successful claim, keep the destination
and treat the operation as successful. For any other source-removal failure, attempt to remove the
new destination and surface the original failure, retaining any rollback failure as suppressed detail.

## Alternatives considered

- Pre-check then rename: rejected because it is not atomic under contention.
- Delete the losing writer's source automatically: rejected because the connector cannot prove that
  it owns the caller's source path.

## Consequences

- Concurrent regular-file commits have one destination winner.
- A crash after the link and before source removal can leave two names for the same inode.
- Directory behavior and cleanup ownership remain explicit limitations.

## References

- `7e9bc22`
- [E2 rename report](../reports/2026-08-15-e2-spark-rename.md)
