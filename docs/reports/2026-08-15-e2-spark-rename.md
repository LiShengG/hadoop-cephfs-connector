# 2026-08-15 E2 Spark rename

## Scope

Rerun regular-file no-replace concurrency and Spark Structured Streaming checkpoint recovery using
release Ceph libraries.

## Environment

- Environment ID: E2
- Commit: `6ac3b9d`
- Component versions: Ceph server 16.2.14; Pacific client 16.2.15; Spark 3.4.4; OpenJDK 11

## Commands

`UNKNOWN`: the exact historical shell invocation is not present in tracked evidence. The run used the tracked
SP-04 probe and Spark checkpoint workload under `scripts/spike/`.

## Results

| Test ID | Result | Evidence |
|---|---|---|
| SP-04 | PASS | Five rounds each produced one winner and seven existing-destination failures |
| ECO-SPK-03 | PASS | Checkpoint restart retained the query identity and advanced from 40 to 80 rows |
| ECO-SPK-03 concurrency | PASS | One competing Spark process succeeded, one failed with `SparkConcurrentModificationException`, and a third process recovered |
| Crash residue | OBSERVED | A failed competitor retained one `.metadata.<uuid>.tmp` source |

## Findings

Regular-file destination claiming is atomic for the tested no-replace path. Cleanup ownership and
directory rename behavior remain limited as described by
[LIM-005](../KNOWN-LIMITATIONS.md#lim-005-no-replace-rename-boundaries).

## Limitations

This report does not establish atomic no-replace behavior for directories or automatic cleanup after
all crash points.

## Follow-up

Cover directory and failure-recovery cases before broadening the guarantee.
