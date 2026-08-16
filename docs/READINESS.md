# Production readiness

> Updated: 2026-08-16
> Evidence policy: only linked dated reports count as verified

Status values are `VERIFIED`, `PARTIAL`, `NOT_VERIFIED`, `BLOCKED`, and `ACCEPTED_RISK`.

| Area | Status | Required evidence | Latest report | Open items |
|---|---|---|---|---|
| Correctness | PARTIAL | UT, CT, FN, and NEG definitions applicable to supported behavior | [E2 release regression](reports/2026-08-15-e2-release-regression.md) | FN and NEG matrices are incomplete; see T09/T12 |
| Reliability | NOT_VERIFIED | REL-F01–REL-F15 with integrity checks and recovery evidence | — | T09 is blocked by T08 |
| Ecosystem | PARTIAL | Required ECO P0/P1 cases and component support conclusions | [E3 distributed ecosystem](reports/2026-08-15-e3-distributed-ecosystem.md) | Spark format/commit, Kerberos, HBase, Flink, scale, and failure cases remain |
| Performance | NOT_VERIFIED | PERF-01–PERF-15 with an environment fingerprint | — | T10 is blocked |
| Long-running behavior | NOT_VERIFIED | SOAK-01–SOAK-05, including stable native sessions and resources | [E3 Hive Tez](reports/2026-08-15-e3-hive-tez.md) | Existing evidence observes stale sessions; it is not a pass |
| Security | NOT_VERIFIED | SEC-01–SEC-10 and ECO-SEC-01–05 in an isolated security environment | [E3 distributed ecosystem](reports/2026-08-15-e3-distributed-ecosystem.md) | Kerberos and multi-tenant authorization are not verified |
| Compatibility | NOT_VERIFIED | COMPAT-01–COMPAT-12 | [E2 release regression](reports/2026-08-15-e2-release-regression.md) | One baseline combination is evidence, not the required matrix |
| Engineering gates | PARTIAL | Repeatable E1/E2/E3 supply, CI, coverage, static/CVE, artifact, and flaky gates | [E1 regression](reports/2026-08-14-e1-regression.md) | CI, repeatable provisioning, coverage, static/CVE, artifact, and flaky gates remain |
| Deployment and operations | PARTIAL | OPS-01–OPS-10 | [E3 distributed ecosystem](reports/2026-08-15-e3-distributed-ecosystem.md) | Upgrade, rollback, monitoring, diagnostic, and reproducibility cases remain |

## Required release evidence

| Milestone | Requirement | Tracking |
|---|---|---|
| Beta candidate | T08 and T09 acceptance criteria; no open S1 data-loss or silent-corruption issue | [T08](tasks/T08-测试基础设施与质量门禁.md), [T09](tasks/T09-功能深化与故障注入.md) |
| RC candidate | Beta evidence plus T10 and T11 acceptance criteria | [T10](tasks/T10-性能与容量基准.md), [T11](tasks/T11-生态集成与兼容矩阵.md) |
| Release candidate | RC evidence plus T12 security, soak, operations, negative-path, and waiver review | [T12](tasks/T12-安全长稳与发布验收.md) |

No milestone above is currently marked verified. Active sequencing and environment blockers are
maintained in [`../PROGRESS.md`](../PROGRESS.md). Current product restrictions are maintained in
[KNOWN-LIMITATIONS.md](KNOWN-LIMITATIONS.md).

## Update rules

- Change a status only when adding or linking a report that identifies the environment, commit,
  commands, test IDs, and result.
- A partial execution does not verify an entire area.
- A failure remains visible until a later report reruns the same test ID and records the applicable
  resolution or an approved accepted-risk reference.
- Do not copy test steps, environment topology, task history, or report output into this matrix.
