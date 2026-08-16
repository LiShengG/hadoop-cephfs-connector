# 2026-08-15 E2 release regression

## Scope

Run the connector baseline against a three-node, three-replica CephFS using release client libraries
and a restricted cephx identity.

## Environment

- Environment ID: E2
- Commit: `6ac3b9d`
- Component versions: Ceph server 16.2.14; Pacific client 16.2.15; Hadoop 3.3.6; OpenJDK 11
- Ceph FSID: `b8bcd906-984c-11f1-9f8c-e37fec9805df`
- Server image digest: `sha256:9ca1fe4ec4643bbe5ab8b895cca0d54fb8edae70fcca189919177db1cdd91745`

## Commands

```bash
cd /code/hadoop-cephfs-connector/hadoop-cephfs
CEPH_CONTRACT_TEST=1 \
CEPH_CONF_FILE=/etc/ceph/ceph.conf \
CEPH_AUTH_ID=hadoop \
CEPH_AUTH_KEYRING=/etc/ceph/ceph.client.hadoop.keyring \
mvn -Dceph.lib.dir=/usr/lib \
    -Dceph.jni.dir=/usr/lib/jni \
    -Dceph.test.args= verify
```

## Results

| Test ID | Result | Evidence |
|---|---|---|
| Unit suite | PASS | 128 tests recorded as passing |
| CT baseline | PASS | 142 contract/integration tests recorded as passing |
| CT-08 | PASS | Restricted `client.hadoop` used without an admin credential |
| Block locations | PASS | Three replica addresses resolved to the three E2 nodes |
| Release runtime | PASS | Empty Ceph test arguments; no debug lock-dependency workaround used |

## Findings

The E2 run removes the E1 debug/single-replica caveat for the tested contract baseline. It does not
complete the compatibility, reliability, performance, or soak matrices.

## Limitations

The exact per-class test list and console output were not retained in tracked evidence.

## Follow-up

Complete repeatable E2 provisioning and the remaining T08 gates before starting destructive failure
injection.
