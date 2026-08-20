# Documentation map

| Need | Read |
|---|---|
| Current project status | [`../PROGRESS.md`](../PROGRESS.md) |
| Architecture and invariants | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| Build and development | [`DEVELOP.md`](DEVELOP.md) |
| Deployment and operations | [`DEPLOY.md`](DEPLOY.md) |
| Test strategy | [`TESTING.md`](TESTING.md) |
| Interactive project, semantics, and experiment views | [`viewer/`](viewer/) |
| Canonical migrated structured records | [`catalog.ndjson`](catalog.ndjson) |
| Coverage by filesystem operation | [`SEMANTICS-MATRIX.md`](SEMANTICS-MATRIX.md) |
| Core test plan and case IDs | [`TEST-PLAN.md`](TEST-PLAN.md) |
| Ecosystem test cases | [`TEST-CASES-ECO.md`](TEST-CASES-ECO.md) |
| Production readiness | [`READINESS.md`](READINESS.md) |
| Current limitations | [`KNOWN-LIMITATIONS.md`](KNOWN-LIMITATIONS.md) |
| Environment definitions | [`environments/`](environments/) |
| Dated verification evidence | [`reports/`](reports/) |
| Architecture decisions | [`adr/`](adr/) |
| Resolved history index | [`archive/`](archive/) |

## Structured viewer quick start

Run the viewer from the repository root on the `experiment/structured-docs-viewer` branch:

```bash
python3 scripts/docs-catalog.py validate
python3 scripts/docs-catalog.py serve
```

On Windows, use `py scripts/docs-catalog.py serve` (or `python ...`) with the same forward-slash path.
The server prints the local `/docs/viewer/` URL after it starts; do not open `index.html` through
`file://`, because the page fetches `catalog.ndjson` over HTTP.

## Ownership rules

- Maintain each fact in one canonical location. Other documents link to it or provide one sentence
  of context.
- Source code, tests, and `pom.xml` own API signatures, types, configuration defaults, dependency
  versions, and executable behavior.
- `catalog.ndjson` owns migrated project-state, readiness, experiment-journal, and semantic records.
  `PROGRESS.md`, `READINESS.md`, and `EXPERIMENTS.md` are entry points rather than duplicate stores.
- The semantic baseline owns expected behavior, basis, classification, guards, and structural Coverage
  per decision condition. Records for the six migrated operation axes—rename, create, delete, sync,
  append, and mkdirs—live in `catalog.ndjson`; visibility, identity, path-entrypoint, metadata, read,
  status-defaults, and block-location remain in `SEMANTICS-MATRIX.md` until explicitly migrated. The
  case files own case identity, priority, and required environment; run records and dated reports own
  execution observations.
- Environment documents own topology and safety boundaries, not experiment results.
- Reports record dated release-gate executions. Catalog `run` records provide the concise operational
  journal and evidence links. ADRs record architectural reasons. Archives contain one-line
  resolved-item indexes only.
- `archive/` and `reports/` are not part of the default AI context. Read them only when an active
  task or evidence link explicitly refers to them.
