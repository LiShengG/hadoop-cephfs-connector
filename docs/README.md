# Documentation map

| Need | Read |
|---|---|
| Current project status | [`../PROGRESS.md`](../PROGRESS.md) |
| Architecture and invariants | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| Build and development | [`DEVELOP.md`](DEVELOP.md) |
| Deployment and operations | [`DEPLOY.md`](DEPLOY.md) |
| Test strategy | [`TESTING.md`](TESTING.md) |
| Coverage by filesystem operation | [`SEMANTICS-MATRIX.md`](SEMANTICS-MATRIX.md) |
| Core test plan and case IDs | [`TEST-PLAN.md`](TEST-PLAN.md) |
| Ecosystem test cases | [`TEST-CASES-ECO.md`](TEST-CASES-ECO.md) |
| Production readiness | [`READINESS.md`](READINESS.md) |
| Current limitations | [`KNOWN-LIMITATIONS.md`](KNOWN-LIMITATIONS.md) |
| Environment definitions | [`environments/`](environments/) |
| Dated verification evidence | [`reports/`](reports/) |
| Architecture decisions | [`adr/`](adr/) |
| Resolved history index | [`archive/`](archive/) |

## Ownership rules

- Maintain each fact in one canonical location. Other documents link to it or provide one sentence
  of context.
- Source code, tests, and `pom.xml` own API signatures, types, configuration defaults, dependency
  versions, and executable behavior.
- `PROGRESS.md` owns active work and blockers. `READINESS.md` owns current release-gate status.
- `SEMANTICS-MATRIX.md` owns expected behavior, basis, classification, guards, and structural Coverage
  per decision condition. The case files own case identity, priority, and required environment;
  reports and `READINESS.md` own execution state.
- Environment documents own topology and safety boundaries, not experiment results.
- Reports record dated executions. ADRs record architectural reasons. Archives contain one-line
  resolved-item indexes only.
- `archive/` and `reports/` are not part of the default AI context. Read them only when an active
  task or evidence link explicitly refers to them.
