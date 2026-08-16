# 2026-08-14 E1 ecosystem spikes

## Scope

Execute the SP-01–SP-08 A-layer probes against a real E1 CephFS client and classify the predicted
ecosystem semantic risks.

## Environment

- Environment ID: E1
- Commit: `235be55`
- Component versions: Ceph 16.2.14 debug build; Hadoop 3.3.6; OpenJDK 11

## Commands

```bash
cd /code/hadoop-cephfs-connector
SPIKE_OUT_DIR=hadoop-cephfs/target/spike-e1-20260814 \
CEPH_BUILD=/code/ceph-v16.2.14/build \
CEPH_CONF_FILE=/code/ceph-v16.2.14/build/ceph.conf \
scripts/spike/run-all.sh
```

## Results

| Test ID | Result | Evidence |
|---|---|---|
| SP-01 | CONFIRMED | Owner string followed the calling UGI while an unrelated uid was reported numerically |
| SP-02 | CONFIRMED | POSIX group membership was rejected by Hadoop `access()` because group metadata was numeric |
| SP-03 | CONFIRMED | Non-numeric owner/group update returned without changing ownership |
| SP-04 | INCONCLUSIVE | The original concurrency probe was invalidated by the default-port defect; later evidence is in the E2 rename report |
| SP-05 | CONFIRMED | An already-open reader did not observe the appended segment; a new reader did |
| SP-06 | CONFIRMED | The connector produced no delegation token in the tested simple-auth environment |
| SP-07 | CONFIRMED | Hadoop checksum was null and did not provide a content-integrity signal |
| SP-08 | PARTIAL | Sticky/chmod paths worked; non-numeric owner changes did not take effect |

## Findings

The run established current owner/group, append visibility, credential, checksum, and YARN directory
limitations. SP-04 required a corrected implementation and later rerun.

## Limitations

This E1 run did not test a real distributed Hadoop component or Kerberos.

## Follow-up

See [KNOWN-LIMITATIONS.md](../KNOWN-LIMITATIONS.md) and the E2/E3 reports for later evidence.
