# Project progress

> Updated: 2026-08-16
> Commit: 34748f7
> Active milestone: production-readiness evidence and ecosystem coverage

## Latest verified baseline

| Environment | Commit | Result | Report |
|---|---|---|---|
| E1 | `8ff960a` | Unit, contract, integration, smoke, CLI, and package checks recorded | [2026-08-14 E1 regression](docs/reports/2026-08-14-e1-regression.md) |
| E2 | `6ac3b9d` | Release client and three-replica contract baseline recorded | [2026-08-15 E2 release regression](docs/reports/2026-08-15-e2-release-regression.md) |
| E3 | `34748f7` | Distributed MR, YARN, DistCp, Spark, Hive MR, and Hive Tez evidence recorded | [2026-08-15 E3 ecosystem](docs/reports/2026-08-15-e3-distributed-ecosystem.md) |

## Active work

| ID | State | Current finding | Next action | Exit criteria | Tracking |
|---|---|---|---|---|---|
| T08 | IN_PROGRESS | E2/E3 exist, but repeatable provisioning, CI, coverage, static analysis, and artifact gates are absent | Implement the remaining non-product test infrastructure | T08 acceptance criteria and CT IDs pass with reports | [task](docs/tasks/T08-测试基础设施与质量门禁.md) |
| T11 | IN_PROGRESS | MR/YARN/DistCp and partial Spark/Hive/Tez paths have evidence; the component and compatibility matrices remain incomplete | Execute remaining P0/P1 ecosystem and compatibility cases | Required ECO and COMPAT IDs have reports or approved waivers | [task](docs/tasks/T11-生态集成与兼容矩阵.md) |

## Blockers

| ID | Blocker | Resolution condition | Tracking |
|---|---|---|---|
| T09 | T08 repeatable E2 provisioning and required gates are incomplete | T08 exit criteria met | [task](docs/tasks/T09-功能深化与故障注入.md) |
| T10 | Repeatable E2 fingerprinting is incomplete; T09 has not cleared S1/S2 risk | T08 completes and T09 records no open S1/S2 defect | [task](docs/tasks/T10-性能与容量基准.md) |
| T12 | T08–T11 exit criteria are not all met; CephFS sessions remain after some JVM exits | Dependencies complete and SOAK-05 has an acceptable result | [task](docs/tasks/T12-安全长稳与发布验收.md) |
| ENV-E3-NET | E3 has observed a conflicting IP/MAC identity for one node | Infrastructure owner removes the duplicate identity and the status check remains stable | [E3 environment](docs/environments/E3.md) |

## Current release risks

| Risk | Impact | Required evidence | Tracking |
|---|---|---|---|
| Native session lifecycle | Stale sessions can retain caps and delay later filesystem calls | SOAK-04, SOAK-05 | [LIM-006](docs/KNOWN-LIMITATIONS.md#lim-006-native-session-lifecycle) |
| Shared cephx identity and no delegation token | Hadoop user identity is not an end-to-end authorization boundary | SEC-01–SEC-06, ECO-SEC-01–05 | [LIM-003](docs/KNOWN-LIMITATIONS.md#lim-003-authentication-and-delegation-token-boundary) |
| Missing checksum semantics | DistCp update can skip equal-length changed content | ECO-DCP-02 and an accepted operating policy | [LIM-004](docs/KNOWN-LIMITATIONS.md#lim-004-file-checksum-is-unavailable) |
| Incomplete engineering and reliability gates | Current evidence does not establish production readiness | T08–T12 exit criteria | [readiness](docs/READINESS.md) |

## Required next evidence

| Checkpoint | Owner | Required execution | Result needed | Destination |
|---|---|---|---|---|
| T08-E2-REBUILD | T08 | Provision, validate, destroy, and provision E2 again using only tracked entry points and allowlisted disks | Two equivalent healthy fingerprints with commands and recovery notes | New E2 provisioning report |
| T08-E3-REBUILD | T08 | Restore Hadoop/YARN services from tracked configuration and verify every declared service endpoint | E3 status passes without undocumented manual state | New E3 provisioning report |
| T08-QUALITY | T08 | Run CI, coverage, static analysis, dependency/CVE, artifact, and flaky gates | Thresholds enforced by automation; failures cannot be silently skipped | New quality-gate report |
| T11-ECO-P0 | T11 | Execute every outstanding P0 definition in `TEST-CASES-ECO.md` through its real component entry point | Each P0 has PASS, FAIL, or an explicit blocker with retained data checks | Component-specific E3 reports |
| T11-COMPAT-P0 | T11 | Execute the baseline and artifact-mismatch P0 compatibility definitions in isolation | Supported combinations pass and mismatch fails early with a diagnostic | Compatibility report |
| T11-SUPPORT | T11 | Build the component/version support matrix from dated reports and current limitation IDs | Every support cell links to evidence or an explicit unsupported condition | `READINESS.md` and support report |

## Current operating constraints

| Constraint | Required handling | Source |
|---|---|---|
| E2 disk safety | Resolve the node and exact device against the allowlist before any destructive storage operation | [E2 environment](docs/environments/E2.md) |
| E3 network identity | Treat a MAC/SSH identity mismatch as an infrastructure blocker; do not continue a state-changing test | [E3 environment](docs/environments/E3.md) |
| MDS client eviction | Evict only an exact dead-PID session after confirming no in-flight request; never bulk-evict live service sessions | [LIM-006](docs/KNOWN-LIMITATIONS.md#lim-006-native-session-lifecycle) |
| Evidence status | A representative component run cannot verify scale, failure, security, or long-running variants of that case | [testing policy](docs/TESTING.md) |
| Release statements | Do not describe the connector as production-ready until `READINESS.md` links all required reports or accepted-risk references | [readiness](docs/READINESS.md) |
