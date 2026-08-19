# Testing

## Purpose

This document defines stable test layers, environments, entry points, and release gates. Current
status and the concise operational journal are structured records in
[catalog.ndjson](catalog.ndjson); release-gate evidence remains in dated [reports/](reports/).

## Test layers

| Layer | Scope | Default environment | Entry point |
|---|---|---|---|
| Unit | Path, error, metadata, stream, and configuration semantics with a mocked Ceph boundary | No cluster | `mvn -B clean test` |
| Contract | Hadoop `FileSystem` and `FileContext` contract behavior | E1 or E2 | `CEPH_CONTRACT_TEST=1 mvn -B verify` |
| Integration | JNI, real CephFS I/O, CLI, concurrency, and block locations | E1 or E2 | Referenced `ITest*` or repository script |
| Reliability | Failure injection, recovery, and integrity | E2 | REL test IDs |
| Performance | Throughput, latency, scale, and resource comparison | E2 | PERF test IDs |
| Ecosystem | Hadoop, YARN, Hive, Spark, Tez, DistCp, and related components | E3 | ECO test IDs |
| Security and soak | Identity boundary, credential handling, lifecycle, and long-running load | Isolated E3/E2 as specified | SEC and SOAK test IDs |
| Compatibility | Hadoop, JDK, Ceph, OS, and artifact combinations | Isolated matrix environment | COMPAT test IDs |

## Environment responsibilities

- [E1](environments/E1.md): single-node development, JNI smoke checks, fast contract regression,
  and packaging checks. E1 cannot establish redundancy, failover, performance, or production safety.
- [E2](environments/E2.md): three-node release CephFS for contract, reliability, performance, and
  minimum-cephx testing. Device safety rules are mandatory.
- [E3](environments/E3.md): Hadoop/YARN ecosystem integration using the E2 CephFS backend. E3 owns
  component topology, not test results.
- Compatibility cases that alter security mode or dependency versions require isolation from the
  current E3 services. No reusable compatibility-matrix environment is currently documented.

## Test definitions

- Core test IDs are defined in [TEST-PLAN.md](TEST-PLAN.md).
- Ecosystem and spike IDs are defined in [TEST-CASES-ECO.md](TEST-CASES-ECO.md).
- An execution report cites the IDs it ran; it does not reproduce the complete steps.
- An ID is defined exactly once and remains stable when its implementation or evidence changes.
- A definition carries its heading, its gate priority tag, an expected result, and a required
  environment. It adds preconditions or steps only where they are not implied by the heading.
- A definition without explicit steps is executed as: establish the required environment, perform the
  operation named by the heading, then record inputs, outputs, errors, resource state, and cleanup.
- The semantic baseline owns expected behavior, its basis, current-behavior classification, exact
  guards, and structural Coverage per decision condition. Migrated records live in
  [catalog.ndjson](catalog.ndjson), while not-yet-migrated axes remain in
  [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md). Coverage does not carry execution state. A case whose
  expectation is decision-level delegates to the baseline instead of restating it, and its expected
  result is then the union of the records or rows citing that case.
- Suite-level, performance, spike, and ecosystem cases state their own expected results, because
  their expectations are not decision-level.

## Execution rules

1. Run the smallest unit or integration class covering the change.
2. Confirm the target environment with its read-only status command before cluster-gated work.
3. Append a concise `run` record with the tested revision, commands, result, and evidence links. Use
   `commit` for committed code, or `worktree_base` with `dirty: true` for an uncommitted experiment;
   only migrated historical records may use `revision_unknown: true`. For a release-gate execution,
   retain component versions, per-ID results, observations, and cleanup in a dated report as well.
4. Preserve failed results. Do not convert an environment failure into a connector pass.
5. Verify data-bearing failure cases with an integrity assertion appropriate to the test definition.
6. Restore the environment and remove only test-owned paths after execution.

## Gates

| Gate | Required evidence |
|---|---|
| Change | Relevant unit tests and documentation checks |
| Behavioral change | Relevant contract/integration IDs and an updated dated report |
| Beta candidate | T08 and T09 acceptance criteria; no open S1 data-loss or silent-corruption issue |
| RC candidate | T10 and T11 acceptance criteria plus compatibility and performance reports |
| Release candidate | T12 security, soak, operations, negative-path, and waiver review |

Numeric thresholds are defined by the applicable test case or active task. Status is determined only
by explicit `readiness_area` records linked to evidence; the viewer never infers readiness from counts.

## Failure handling

- Mark a case `FAIL` when the observed connector behavior violates its expected result.
- Mark a case `BLOCKED` when a prerequisite or safe environment is unavailable; record the exact
  unblock condition.
- Mark a case `NOT_RUN` when it was outside the execution scope.
- A waiver identifies the affected test ID, impact, expiry or review trigger, and approval reference.
- Do not rerun automatically to hide flaky behavior. Record reproducibility and create tracking before
  accepting a flaky gate.

## Report storage

Store release-gate executions as `reports/YYYY-MM-DD-<environment>-<topic>.md`. Reports contain only
actual commands and observations. Architecture explanations link to [ARCHITECTURE.md](ARCHITECTURE.md),
current limitations link to [KNOWN-LIMITATIONS.md](KNOWN-LIMITATIONS.md), and resolved work is indexed
once in [archive/](archive/). The corresponding catalog `run` record is a concise index and does not
replace the report's detailed evidence.
