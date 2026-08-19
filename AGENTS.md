# Repository instructions

## Scope

- This repository implements a Hadoop FileSystem backend for CephFS.
- Keep changes limited to the tracked task.
- Do not modify external Ceph or Hadoop source trees.

## Required context

- Always read this file and the active task or Issue.
- Read `docs/ARCHITECTURE.md` only for architecture or public semantic changes.
- Read `docs/TESTING.md` and referenced test IDs for behavior or test changes.
- Read `docs/DEPLOY.md` only for deployment changes.
- Do not read `docs/archive/` or `docs/reports/` unless explicitly referenced.

## Change rules

- Prefer the smallest task-complete diff.
- Do not duplicate API signatures or configuration defaults in prose.
- Add or update tests for behavior changes.
- Public semantic or architectural changes require an ADR.
- Reproducible procedures belong in scripts, not only in prose.
- Do not add local absolute paths or temporary environment identifiers to long-lived documents.

## Documentation lifecycle

- `PROGRESS.md` contains active work only.
- Remove resolved items from `PROGRESS.md` in the same change that archives them.
- Record resolved items in `docs/archive/YYYY-MM.md`.
- Record current limitations in `docs/KNOWN-LIMITATIONS.md`.
- Record test executions in `docs/reports/`.
- Do not copy complete historical text into the archive.

## Structured catalog and experiment journal

- `docs/catalog.ndjson` is the canonical structured source for migrated project state, readiness,
  semantics, and experiment-journal records. Use `scripts/docs-catalog.py show` or `query` instead of
  reading the whole catalog when only a few records are needed.
- After each experiment, append one concise `run` record to `docs/catalog.ndjson` using `apply_patch`,
  then run `python3 scripts/docs-catalog.py validate` immediately.
- Identify the tested revision with `commit`, or use `worktree_base` plus `dirty: true` before the
  experiment is committed. Only migrated historical runs may use `revision_unknown: true`.
- Do not read earlier run records merely to append a new one. Query them only when the active task
  explicitly requires historical comparison or analysis.
- Run records are the operational journal. Release-gate evidence still requires the dated report
  defined by `docs/TESTING.md`.

## Verification

- Run the smallest relevant test set first.
- Unit tests: `cd hadoop-cephfs && mvn -B clean test`.
- Cluster-gated tests: configure an environment from `docs/environments/`, then run
  `cd hadoop-cephfs && CEPH_CONTRACT_TEST=1 mvn -B verify` with the required native-library overrides.
- Distribution build: `scripts/make-dist.sh`.
- Documentation gate: `bash scripts/check-docs.sh`.
- Run required repository gates before declaring completion.
- Report commands actually executed and unresolved failures.
