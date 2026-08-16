# ECO spike 套件 —— 高风险预测的低成本证伪

对应 [docs/TEST-CASES-ECO.md](../../docs/TEST-CASES-ECO.md) §3 的 SP-01–SP-08。
**在 T08 大规模测试建设之前执行**：这些预测若成立，T11 的范围乃至产品路线都要改，
越早知道越省。全部 A 层用例只需现有 vstart 集群，跑完约 10 分钟。

## 为什么是"预测"

SP-01–SP-08 来自对连接器代码事实与 Hadoop 3.3.6 源码的**对照推断**，不是实测结论。
Hadoop 侧的判定逻辑已逐条核对源码（见各 Java 探针的类注释，标注了源码位置与原文），
连接器侧的行为则由本套件在真实集群上实测。

**证伪与证实同样有价值。** 判据写死在探针里，不允许为了"符合预期"事后调整；
跑出 REFUTED 就照实记 REFUTED。

## 判据自检（写完/改完判据先跑这个）

```bash
scripts/spike/control-localfs.sh     # 不需要 Ceph 集群
```

把**判别型**探针跑在 LocalFileSystem 上，期望**全部 REFUTED**——正常的文件系统不该
命中这些判据。本项目开发本套件时被它抓到过两次假阳性：

1. SP-01 初版判据是"把目录 chown 给某个 uid 后属主校验失败"。可这在**任何** FS
   （含 HDFS）上都会失败，且随手挑的 uid（4242）无账号时，本地 FS 同样只能报数字。
   改成"用系统上真实存在的账号（如 daemon）"后，本地 FS 报出 `daemon` → REFUTED，
   判据才具备区分力；
2. SP-08 初版把 `setOwner` 的目标设成当前用户，"变更前后属主相同"既可能是静默无效、
   也可能本来就一样。改成 chown 到另一个真实账号才判得准。

非判别型探针不参与自检：SP-05（本地 FS 本就无法让 reader 看到未成块的尾部数据）、
SP-06/SP-07（本地 FS 同样无 Token、同样返回 null checksum，属事实确认型）。

## 运行

```bash
scripts/cluster-up.sh                 # 需要 vstart 集群
scripts/spike/run-all.sh              # A 层全部（约 10 min）
scripts/spike/sp05-visibility.sh      # 也可单跑某一条
```

B 层（需要额外环境，单独执行）：

```bash
HADOOP_HOME=/opt/hadoop-3.3.6 scripts/spike/sp01b-mr-submit.sh   # 真实 MR 提交
scripts/spike/sp06-kerberos.sh                                    # 文末附 Kerberos 清单
```

环境变量：`CEPH_BUILD`、`CEPH_CONF_FILE`、`CEPH_LIB_DIR`、`CEPH_JNI_DIR`、
`CEPH_AUTH_ID`、`CEPH_AUTH_KEYRING`、`CEPH_ARGS`、`SPIKE_OUT_DIR`、
`SPIKE_ALIEN_UID`、`SPIKE_RENAME_THREADS`、`SPIKE_CHUNK_BYTES`。E1 Debug 未设置
`CEPH_ARGS` 时默认使用 `--lockdep=false`；E2 Release 可传空值 `CEPH_ARGS=`，并将
lib/JNI/keyring 三个路径指向发行版客户端与受限 cephx 身份。

## 产出

| 路径 | 内容 |
|---|---|
| `hadoop-cephfs/target/spike/results.tsv` | 结论表（ID / VERDICT / 说明 / 时间） |
| `hadoop-cephfs/target/spike/logs/*.log` | 每个探针的完整 stdout（含人读的详情） |
| `hadoop-cephfs/target/spike/ECO-FINDINGS-draft.md` | 待人工整理的结论草稿 |

脚本**不直接写 `docs/`**——结论需人工整理进 `docs/reports/` 的日期化报告，补上复现命令与
原始输出片段（体例同 `docs/FAULT-BEHAVIOR.md`）。

## VERDICT 口径

| 值 | 含义 |
|---|---|
| `CONFIRMED` | 预测成立：连接器行为确实会造成该后果 |
| `REFUTED` | 预测不成立 |
| `INCONCLUSIVE` | 环境或前置条件不满足，未能判定（需补环境重跑，**不可当作通过**） |

`run-all.sh` 恒以退出码 0 结束：spike 的产出是结论而非通过/失败，
CONFIRMED 不应被 CI 当作红灯。

## 覆盖清单

| 探针 | 覆盖 | 层 | Hadoop 侧对照（均已核对 3.3.6 源码） |
|---|---|---|---|
| `sp01-owner-group.sh` | SP-01/02/03 | A | `JobSubmissionFiles#getStagingDir`、`FileSystem#checkAccessPermissions` |
| `sp01b-mr-submit.sh` | SP-01 | B | 真实 MR 提交（两种部署形态） |
| `sp04-filecontext-rename.sh` | SP-04 | A | `DelegateToFileSystem#renameInternal` → `FileSystem#rename(…,Rename…)` |
| `sp05-visibility.sh` | SP-05 | A | —（纯连接器行为：`CephInputStream` 长度快照） |
| `sp06-kerberos.sh` | SP-06 | A+清单 | `FileSystem#addDelegationTokens` 基类默认 |
| `sp07-checksum-caps.sh` | SP-07 + 能力面清单 | A | `DistCpUtils#checksumsAreEqual` 的三态判定 |
| `sp08-logagg-dirs.sh` | SP-08 | A | `LogAggregationFileController`（TLDIR 01777 / APP_DIR 0770） |
| `control-localfs.sh` | 判据自检 | — | 判别型探针在 LocalFileSystem 上应全部 REFUTED |

> `sp07` 顺带把 TEST-CASES-ECO.md §2「API 支撑面盘点」里的纸面推断替换为实测清单
> （`hasPathCapability` 全常量、`StreamCapabilities`、`truncate`/`concat`/`setReplication`
> /`getTrashRoot` 的真实表现），跑一次即可更新该表。
