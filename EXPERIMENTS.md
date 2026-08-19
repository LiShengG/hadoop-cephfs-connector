# Experiment journal

This is the append-only operational record for experiments. Add each completed experiment directly
above the stable marker at the end of the file. Prior entries do not need to be read before appending.
Dated release-gate evidence remains in `docs/reports/`.

## 2026-08-16 — ECO-CLI-01 — E3 Hadoop CLI

- Environment: E3; Hadoop 3.3.6; OpenJDK 11; E2 CephFS backend
- Artifact: deployed connector SHA-256
  `6a3e308eae3a27b6341c2f3b00fa5316bc1ea512cf55acace65073e65152b874`
- Result: PASS after correcting an output-filter predicate; 23/23 assertions passed
- Integrity: 200 MiB upload/download MD5 matched
  (`866a22d834cc44b20a85eed3cbe3b3f3`)
- Cleanup: both experiment-specific CephFS paths were absent after execution
- Environment after execution: Ceph `HEALTH_OK`
- Initial observation: the first run returned 1 because JNI loader messages on stdout invalidated
  three exact-output predicates; the initial harness failure remains recorded
- Evidence: [dated report](docs/reports/2026-08-16-e3-cli.md)

## 2026-08-16 — ECO-SPK-01/02 — E3 Spark P0

- Environment: E3; Spark 3.4.4 on YARN; Hadoop 3.3.6; E2 CephFS backend
- Applications: `application_1786771004480_0034` (committer v1) and
  `application_1786771004480_0035` (committer v2)
- Result: PASS; both applications finished successfully
- Integrity: Parquet and ORC each returned 10,000 rows, 10,000 distinct IDs, ID sum 49,995,000,
  and range 0–9,999 under both committer versions
- Commit checks: every output had `_SUCCESS`, no `_temporary`, and 12 part files
- Cleanup: the experiment-specific CephFS root was absent after execution
- Reliability observation: each application retried one task after CephFS mount timed out; a
  post-run `ceph fs status` also timed out after 20 seconds, so no clean session comparison was made
- Evidence: [dated report](docs/reports/2026-08-16-e3-spark-p0.md)

## 2026-08-20 — Semantic Matrix schema documentation gate

- Command: `bash scripts/check-docs.sh`
- Result: PASS; 223 unique test IDs and 25 delegated cases validated, including the migrated
  Basis/Classification/Guard/Coverage schema.

## 2026-08-20 — Semantic Matrix count audit

- Command: read-only parser over `docs/SEMANTICS-MATRIX.md` grouped by semantic axis.
- Result: 169 rows; rename classification is 20 MATCH, 5 DIFFERENT, 2 UNKNOWN. Coverage totals are
  UNIT 114, CONTRACT 14, CLUSTER 23, SPIKE 8, NONE 46; the draft overview's NONE total was one high.

## 2026-08-20 — Documentation gate after overview validation

- Command: `bash scripts/check-docs.sh`
- Result: FAIL before validation; the new counter code raised `NameError: Counter is not defined`
  because its import landed in a different embedded Python block.

## 2026-08-20 — Documentation gate with computed matrix overviews

- Command: `bash scripts/check-docs.sh`
- Result: PASS; schema, exact-guard requirements, case delegation, and per-axis Classification/Coverage
  overview counts all validated.

## 2026-08-20 — Semantic Matrix diff hygiene

- Command: `git diff --check`
- Result: PASS; no whitespace errors in the schema, limitation, test-plan, ADR, or gate changes.

## 2026-08-20 — Final Semantic Matrix documentation gate

- Command: `bash scripts/check-docs.sh`
- Result: PASS on the final schema and evidence-boundary changes; 223 IDs and 25 delegated cases
  validated.

## 2026-08-20 — Final documentation gate after migration-scope clarification

- Command: `bash scripts/check-docs.sh`
- Result: PASS; the final text explicitly limits exact `Class#method` enforcement to migrated axes.

<!-- APPEND NEW EXPERIMENTS IMMEDIATELY ABOVE THIS LINE. DO NOT MOVE OR REMOVE THIS MARKER. -->
