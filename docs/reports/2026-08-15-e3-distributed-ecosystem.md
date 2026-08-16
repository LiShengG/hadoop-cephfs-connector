# 2026-08-15 E3 distributed ecosystem

## Scope

Validate the connector through distributed Hadoop/YARN, MapReduce, DistributedCache, DistCp, Spark
checkpointing, YARN log aggregation, and the current credential boundary.

## Environment

- Environment ID: E3
- Commit: `19935b6` for the initial distributed run; `1a63fb1` for the second round; `095754c`
  for the credential-boundary evidence
- Component versions: Hadoop 3.3.6; Spark 3.4.4; OpenJDK 11; E2 CephFS backend
- Connector jar SHA-256: `6a3e308eae3a27b6341c2f3b00fa5316bc1ea512cf55acace65073e65152b874`

## Commands

`UNKNOWN`: exact submission command lines were not retained in tracked evidence. The run used the tracked E3
configuration under `conf/e3/` and the speculation probe in `scripts/spike/java/`.

## Results

| Test ID | Result | Evidence |
|---|---|---|
| ECO-MR-01 | PASS | `application_1786771004480_0003`; CephFS default filesystem with CephFS staging/input/output completed correctly |
| ECO-MR-02 | PASS | `application_1786771004480_0002`; HDFS default filesystem with CephFS input/output completed correctly |
| ECO-MR-03 | PASS | `application_1786771004480_0008`; FileOutputCommitter v1 produced correct output and no `_temporary` residue |
| ECO-MR-04 | PASS | `application_1786771004480_0009`; FileOutputCommitter v2 produced correct output and no `_temporary` residue |
| ECO-MR-05 | PASS | `application_1786771004480_0015`; seven attempts for six logical maps produced no duplicate or missing output |
| ECO-MR-10 | PARTIAL | `application_1786771004480_0010` and `application_1786771004480_0011` localized correct content; cross-application cache-key reuse was not demonstrated |
| ECO-YARN-01 | PASS | Aggregated container logs were retrievable and history paths were readable |
| ECO-DCP-01 | PASS | `application_1786771004480_0012`; HDFS-to-CephFS copy preserved the tested directory contents |
| ECO-DCP-04 | PASS | `application_1786771004480_0013`; CephFS-to-HDFS copy preserved the tested directory contents |
| ECO-DCP-02 | FAIL | `application_1786771004480_0014`; `-update` skipped an equal-length file whose content differed |
| ECO-SPK-03 | PASS | `application_1786771004480_0005`, `application_1786771004480_0006`, and `application_1786771004480_0007`; a failed driver committed the next batch and a later driver recovered |
| SP-06 | PARTIAL | Simple-auth run returned no Ceph delegation token; Kerberos was not tested |

## Findings

- Mixed HDFS-default deployments require explicit Ceph authorities.
- Null checksum behavior makes DistCp update unsuitable as an integrity check.
- Some exited distributed client JVMs left MDS sessions and caps.
- The E3 network identity and monitor-store latency require infrastructure remediation before
  reliability results can be interpreted cleanly.

## Limitations

Application IDs and detailed output remain historical evidence only. This run did not complete
Kerberos, the Spark format/commit matrix, HBase, Flink, scale, or failure recovery.

## Follow-up

Continue T11 after the T08 environment and engineering prerequisites are complete.
