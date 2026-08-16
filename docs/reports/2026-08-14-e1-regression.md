# 2026-08-14 E1 regression

## Scope

Rebuild the E1 development baseline and run unit, contract/integration, smoke, CLI, and distribution
checks.

## Environment

- Environment ID: E1
- Commit: `8ff960a`
- Component versions: Ceph 16.2.14 debug build; Hadoop 3.3.6; OpenJDK 11.0.27; Maven 3.6.3

## Commands

The tracked evidence records these executed entry points:

```bash
cd hadoop-cephfs
mvn clean test
CEPH_CONTRACT_TEST=1 mvn verify
cd ..
scripts/smoke-test.sh
scripts/e2e-cli-test.sh
scripts/make-dist.sh
```

## Results

| Test ID | Result | Evidence |
|---|---|---|
| Unit suite | PASS | 122 tests recorded as passing |
| Contract/integration suite | PASS | 140 gated tests recorded as passing |
| E1 smoke | PASS | Java → JNI → libcephfs → CephFS write/read/delete completed |
| CLI E2E | PASS | Assertions passed; 200 MiB put/get digest matched |
| Distribution build | PASS | Archive produced; recorded SHA-256 `3e4ec5d8fcc4cab6d0fa345b0528c3b9a2443ad8f05b2762393814184b595910` |

## Findings

E1 was restored as a fast development baseline. Its size-1 pools and debug client mean these results
do not verify redundancy, release-library behavior, performance, reliability, or security.

## Limitations

- The historical detailed console output is not tracked.
- Counts are execution evidence for this date and are not maintained as current documentation facts.

## Follow-up

Use E2 for release-library and three-replica contract evidence.
