# E3 Hadoop/YARN/Spark 三节点环境

> 建成及首轮验证日期：2026-08-15。E3 与 [E2-ENV.md](E2-ENV.md) 共用
> `.44/.26/.28` 三节点和 `cephfs-e2`，用于真实发行版 MR、YARN 与 Spark 分布式验证。

## 1. 固定指纹与拓扑

| 项 | 值 |
|---|---|
| 节点 | `node-44` (`10.20.40.44`)、`node-26` (`10.20.40.26`)、`node-28` (`10.20.40.28`) |
| Hadoop | Apache Hadoop 3.3.6，`/opt/hadoop-e3` |
| Spark | Apache Spark 3.4.4，`/opt/spark-e3` |
| Java | OpenJDK 11 |
| 服务用户 | `hadoope3`，UID/GID 2001 |
| NameNode / ResourceManager / JHS | `node-44` |
| DataNode / NodeManager | 三节点各 1 个 |
| HDFS | `hdfs://10.20.40.44:8020`，3 DataNode，默认副本 2 |
| CephFS | `cephfs-e2`，路径前缀 `/e3`，`client.hadoop` |
| 连接器 | `hadoop-cephfs-1.0.0.jar`，SHA-256 `6a3e308eae3a27b6341c2f3b00fa5316bc1ea512cf55acace65073e65152b874` |
| Spark archive | HDFS `/e3/spark/spark-jars.zip`，副本 2 |

Hadoop 配置模板位于 [`conf/e3/`](../conf/e3/)。E3 采用混合模式：HDFS 是
`fs.defaultFS`，业务数据、YARN 聚合日志、JHS 历史、Spark event log 和 checkpoint
通过 `ceph://10.20.40.44:6789/...` 显式访问 CephFS。

## 2. 服务与目录

- NameNode：RPC `8020`，Web `9870`；ResourceManager：RPC `8032`，Web `8088`；
  JobHistoryServer：RPC `10020`，Web `19888`。
- 本地状态目录：`/var/lib/hadoop-e3`；日志：`/var/log/hadoop-e3`。
- CephFS `/e3` 权限为 01777；服务目录包括 `yarn-logs`、`jobhistory/{intermediate,done}`、
  `staging`、`spark-events`、`spark-checkpoints` 与 `data`。
- `hadoop-cephfs-1.0.0.jar` 和 `libcephfs.jar` 位于 Hadoop common lib；keyring 权限为
  `0640 root:hadoope3`。容器 JVM 显式设置 `CEPH_JNI_PATH=/usr/lib/jni/libcephfs_jni.so`
  和 `LD_LIBRARY_PATH=/usr/lib:/usr/lib/x86_64-linux-gnu:/usr/lib/jni`。

在 `node-44` 执行只读检查：

```bash
scripts/env/e3-status.sh
```

正常终行：

```text
[e3-status] PASS: HDFS 3 DataNodes, YARN 3 NodeManagers, RM/NN/JHS endpoints ready
```

## 3. 已完成的真实分布式验证

### MapReduce 与 YARN

| 应用 | 部署形态 | 结果 |
|---|---|---|
| `application_1786771004480_0002` | HDFS defaultFS，CephFS input/output | `SUCCEEDED`；Map 在 `.28`，Reduce 与 AM 在 `.26`；结果 `alpha=3 beta=2 gamma=3` |
| `application_1786771004480_0003` | CephFS defaultFS，staging/input/output 均为 CephFS | `SUCCEEDED`；结果 `blue=2 green=3 red=3` |

两轮均生成 `_SUCCESS`，没有临时输出残留。`yarn logs` 能从 CephFS 远端日志目录取回
`.26/.28` 容器日志，JHS 能读取 CephFS intermediate/done 目录。

### Spark Structured Streaming checkpoint

3 个 executor 分别落在 `.44/.26/.28`，CephFS 同时承载 Parquet sink、event log 与
checkpoint：

| 应用 | 最终状态 | checkpoint 结果 |
|---|---|---|
| `application_1786771004480_0005` | `SUCCEEDED` | query `9d1aacd2-2b90-4b67-a3c3-f314a670273a`，batch 0，40 行 |
| `application_1786771004480_0006` | `FAILED`（应用 120 秒超时） | 在最终失败前已原子提交 `offsets/1`、`commits/1` 和 batch 1，累计 80 行 |
| `application_1786771004480_0007` | `SUCCEEDED` | 从失败应用恢复，同一 query ID，batch 2，累计 120 行；run ID 改变 |

`0006` 的失败由首轮已退出 JVM 的 CephFS caps 等待 MDS session timeout 引起；仅回收已确认
PID 不存在的旧 session 后，`0007` 约 35 秒完成。这同时给出了 checkpoint 原子提交和
driver 失败后继续恢复的真实分布式证据，但也暴露了 mount 生命周期/会话归零问题，必须在
T12 SOAK-05 定量验证，不能据此宣称长稳通过。

## 4. 环境限制与运维注意事项

1. **混合 defaultFS 必须写显式 Ceph authority。** JHS/FileContext 使用 `ceph:///...` 时，
   Hadoop 3.3.6 会错误继承 HDFS authority，并把 Ceph 协议发往 `10.20.40.44:8020`。
   E3 因此统一使用 `ceph://10.20.40.44:6789/...`；这是当前集成限制，不是推荐的通用 URI。
2. **`10.20.40.26` 存在外部重复 IP/MAC 风险。** 正确节点 MAC 是
   `00:0c:29:dc:2f:46`，曾观察到错误 MAC `9e:c6:13:71:42:aa` 和另一套 SSH host key。
   `node-44` 当前用 runtime 静态 neighbor 固定正确 MAC；状态脚本会校验但不会修改。
   根因需由交换机/DHCP/虚拟化平台处理，当前 workaround 不是生产方案。
3. `.44/.26` 使用 Pacific 16.2.15 client runtime；`.28` 为 Ubuntu Quincy 17.2.9 client。
   E3 已实测互通，但完整四维兼容矩阵仍未完成。
4. `.28` 的 `/usr/local/bin/ceph` 是不完整实验版，缺 `ceph_argparse`；集群管理应使用
   cephadm 容器内匹配版本工具，不能依赖该路径。
5. app0001 曾因 YARN 容器缺 `HADOOP_MAPRED_HOME` 失败，模板已在 AM/map/reduce 三类环境
   显式设置；删除该配置会复发 classpath 错误。

## 5. 尚未覆盖

E3 已完成环境建成、真实发行版 CLI 基础路径、两种 MR 部署形态、YARN 日志聚合与 Spark
checkpoint/失败恢复。committer v1/v2 全矩阵、推测执行、DistributedCache、DistCp、Hive、
Kerberos、Spark ORC/提交协议、NM 长跑 session 归零及故障注入仍属于后续 T11/T12 工作。
