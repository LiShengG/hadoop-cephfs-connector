# Development

## Requirements

- A JDK and Maven compatible with the versions declared in
  [`pom.xml`](../hadoop-cephfs/pom.xml).
- A matching `libcephfs.jar` installed in the local Maven repository.
- Matching `libcephfs_jni.so` and native Ceph client libraries for cluster-gated tests.
- An E1 or E2 environment for integration tests. Unit tests do not require a cluster.

Do not rely on the machine-specific defaults in the current Maven build. Set the native library
properties explicitly when running on another host.

## Layout

| Path | Role |
|---|---|
| `hadoop-cephfs/src/main/java/` | Connector implementation |
| `hadoop-cephfs/src/test/java/` | Unit, contract, and integration tests |
| `hadoop-cephfs/src/test/resources/contract/` | Hadoop contract capability declaration |
| `conf/` | Configuration examples and the tracked E3 templates |
| `scripts/` | Reproducible build, environment, smoke, and validation entry points |
| `dist/native/` | Local matching Ceph Java/JNI artifacts; binaries are not tracked |

## Build and unit tests

```bash
cd hadoop-cephfs
mvn -B clean test
```

Build without repeating the clean phase:

```bash
cd hadoop-cephfs
mvn -B package
```

The exact artifact version and dependency versions come from `pom.xml`. Historical test counts and
results belong in [reports/](reports/), not in this document.

## Contract and integration tests

Choose an environment from [environments/](environments/) and export its Ceph configuration and
native paths. For a release-library environment, a complete invocation has this form:

```bash
cd hadoop-cephfs
CEPH_CONTRACT_TEST=1 \
CEPH_CONF_FILE=/etc/ceph/ceph.conf \
CEPH_AUTH_ID=hadoop \
CEPH_AUTH_KEYRING=/etc/ceph/ceph.client.hadoop.keyring \
mvn -B \
  -Dceph.lib.dir=/usr/lib \
  -Dceph.jni.dir=/usr/lib/jni \
  -Dceph.test.args= \
  verify
```

Use `-Dit.test=<class>` for the smallest relevant integration suite. The test gate skips `ITest*`
classes unless `CEPH_CONTRACT_TEST=1` is present.

## Smoke and CLI validation

For the E1 development environment:

```bash
scripts/cluster-up.sh
scripts/smoke-test.sh
scripts/e2e-cli-test.sh
```

These scripts accept environment overrides described in [environments/E1.md](environments/E1.md).
Do not run cluster lifecycle scripts against E2 or E3.

## Distribution build

Place a matching Ceph Java/JNI pair in `dist/native/`, then run:

```bash
scripts/make-dist.sh
```

The packaging rationale is recorded in
[ADR-0002](adr/0002-package-ceph-java-and-jni-separately.md).

## Debugging entry points

- Start with `scripts/smoke-test.sh` when JNI loading or cluster connectivity fails.
- Run a single `Test*` class for Hadoop semantic failures.
- Run a single `ITest*` class after confirming the selected environment's read-only status command.
- Compare unsupported or restricted behavior with [KNOWN-LIMITATIONS.md](KNOWN-LIMITATIONS.md)
  before treating it as a regression.

## Pre-commit checks

```bash
cd hadoop-cephfs
mvn -B clean test
cd ..
bash scripts/check-docs.sh
git diff --check
git status --short
```

Run the relevant cluster-gated test IDs for changes that affect observable filesystem behavior.
