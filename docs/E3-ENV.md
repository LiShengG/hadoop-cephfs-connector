# E3 Hadoop/YARN/Hive/Spark 三节点环境

> 建成及首轮验证日期：2026-08-15。E3 与 [E2-ENV.md](E2-ENV.md) 共用
> `.44/.26/.28` 三节点和 `cephfs-e2`，用于真实发行版 MR、YARN 与 Spark 分布式验证。

## 1. 固定指纹与拓扑

| 项 | 值 |
|---|---|
| 节点 | `node-44` (`10.20.40.44`)、`node-26` (`10.20.40.26`)、`node-28` (`10.20.40.28`) |
| Hadoop | Apache Hadoop 3.3.6，`/opt/hadoop-e3` |
| Spark | Apache Spark 3.4.4，`/opt/spark-e3` |
| Hive | Apache Hive 4.0.1，`/opt/hive-e3`；embedded Derby 元数据仓库 |
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
| `application_1786771004480_0008` | FileOutputCommitter v1 | `SUCCEEDED`；结果正确，有 `_SUCCESS`、无 `_temporary` |
| `application_1786771004480_0009` | FileOutputCommitter v2 | `SUCCEEDED`；结果正确，有 `_SUCCESS`、无 `_temporary` |
| `application_1786771004480_0015` | 6 map、开启推测执行、committer v2 | `SUCCEEDED`；启动 7 个 map attempt、杀死慢 attempt 1 个，输出无重复/丢失 |

两轮均生成 `_SUCCESS`，没有临时输出残留。`yarn logs` 能从 CephFS 远端日志目录取回
`.26/.28` 容器日志，JHS 能读取 CephFS intermediate/done 目录。

推测执行探针让 `slow.txt` 的 attempt 0 等待 90 秒；YARN 在另一容器启动 attempt 1 并先完成。
聚合日志同时出现 `...m_000000_0 slow=true` 与 `...m_000000_1 slow=false`，最终结果中每个
输入文件名计数均为 1、`alpha/beta/gamma` 均为 6。探针源码为
[`scripts/spike/java/SpikeMrSpeculation.java`](../scripts/spike/java/SpikeMrSpeculation.java)，运行日志
保存在 `.44:/var/log/hadoop-e3/e3-mr-speculation.log`。

### DistributedCache 与 DistCp

- 两轮 DistributedCache 应用 `0010/0011` 均成功，三个节点本地化文件内容一致，MD5 为
  `4af90ab73f8deb71b4fdc6c76b01e2b6`。MR 提交器会先把 `ceph://` 资源复制到每个 job 的
  HDFS staging，再以不同 HDFS URI 交给 NodeManager；因此正确分发已验证，但跨应用 cache
  key 复用没有被这条 MR 提交路径直接验证。
- DistCp HDFS→CephFS（`0012`）和 CephFS→HDFS（`0013`）均成功，嵌套目录和文件内容往返
  一致。`-update` 负向应用 `0014` 构造 16 字节、内容不同的同名文件；作业计数器报告
  `Files Skipped=1`、`Bytes Skipped=16`，目标 MD5 保持不变，实证 SP-07：checksum 为 null
  时会静默跳过等长不同内容，不能把该模式用于内容一致性校验。

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

### Hive 4.0.1 on MapReduce

Hive 3.1.3 的旧 CLI 在 Java 11 上因 `URLClassLoader` 强制转换失败，且其依赖栈与当前
Hadoop 3.3.6/JDK 11 不匹配。本轮因此把 E3 基线调整为 Hive 4.0.1，保留旧安装在
`/opt/hive-3.1.3-e3`，系统 Java 仍为 OpenJDK 11。Hive 4 使用 embedded Beeline、Derby
metastore 和 MR 引擎，配置模板见 [`conf/e3/hive-site.xml`](../conf/e3/hive-site.xml) 与
[`conf/e3/hive-env.sh`](../conf/e3/hive-env.sh)。

[`scripts/eco/e3-hive-suite.sql`](../scripts/eco/e3-hive-suite.sql) 在真实 YARN 上运行
application `0019`–`0028`，全部 `FINISHED/SUCCEEDED`：

- 外部 TextFile 聚合得到 `p0=(2,3)`、`p1=(2,7)`、`p2=(2,11)`；
- ORC 动态分区 `INSERT OVERWRITE` 写入 6 行和 3 个分区，回读聚合完全一致；
- Parquet CTAS、`ANALYZE TABLE`、`MSCK REPAIR`（发现 `p3/p4`）与 `TRUNCATE TABLE`
  均成功，truncate 后计数为 0；
- warehouse、source、scratch、ORC/Parquet 数据及 MR 临时目录均使用显式 authority 的
  CephFS URI；结束后无 `.hive-staging` 残留。

Hive 4 的 protobuf 3.x 曾被 Hadoop protobuf 2.5.0 抢先加载并触发 `NoSuchMethodError`；套件
必须设置 `mapreduce.job.user.classpath.first=true`。此外 Hive 4 严格 managed-table 规则会把
当前建表转换为带 `external.table.purge=TRUE` 的 `EXTERNAL_TABLE`，因此这轮证明的是
Text/ORC/Parquet、分区与 DDL/DML 路径，不等同于 ACID 表验证。Hive-on-MR 在 Hive 4 中已是
兼容路径；Tez 仍需单独覆盖。

实验也再次暴露 native mount 生命周期问题：一次失败的 Hive JVM 留下 14 条死 PID MDS
session，精确确认无请求/caps 使用者后才逐条 evict；最终成功套件结束时 session 从 6 条服务
基线升至 13 条，新增 7 条均属于仍存活的 NodeManager/JHS。它们不是脏输出，但不满足
SOAK-05 的会话归零门禁。

### 委托 Token 与当前安全边界

以 `hadoope3` 用户在 E3 运行 `SpikeDelegationToken`：集群为 `simple` 认证，CephFS 的
canonical service 是 `10.20.40.44:6789`，`addDelegationTokens("yarn")` 返回 0，Credentials
中也为 0。三节点 `/etc/ceph/ceph.client.hadoop.keyring` 的 SHA-256 均为
`cf4cdbb740844c4be21bc13df729043e53cde10d3dfdc086511c43c388eaa5f0`，权限均为
`0640 root:hadoope3`，作业用户可读。这确认当前分布式作业依赖节点本地的同一静态 cephx
身份，而非 delegation token。HDFS 在 simple 模式下同样不签发 token，不能作为阳性对照；
只有建成隔离的 Kerberized E3/E4 后，才能完成 SP-06B，当前不得宣称支持 Kerberos 集群。

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
6. 本轮发现 `.26` 的 OSD.2/OSD.3 服务曾被外部停止，集群降为 4/6 OSD、289 PG undersized。
   原 BlueStore/LVM 数据完整，启动原 cephadm systemd unit 后恢复为 6/6、289 PG
   `active+clean`。重新加入的短暂收敛期内 OSD up/down 与 MON 选举反复发生，CephFS 新挂载
   返回 `Connection timed out`；稳定后同一 MR 成功。这属于 E3 环境/故障恢复证据，停止原因
   尚未归因，不能计为连接器重连通过。
7. 应用 `0015` 结束且 PID 已不存在超过 400 秒后，active MDS 会话由基线 8 增至 20；新增
   12 条会话均无 in-flight request、但仍持有 caps。后续 Hive mkdir 被这些已确认死 PID
   session 阻塞时才精确 evict；Hive 失败 JVM 又复现 14 条死 session，成功套件结束仍由
   长期 NodeManager/JHS 保留 13 条服务 session。E4/SOAK-05 继续保持未达标。
8. OSD 恢复后 MON 仍出现间歇选举。三节点 NTP 偏差均小于 0.5 ms、Ceph 集群网 20/20
   ping 无丢包；选举日志显示 `.26/.28` 同时 `lease_timeout`，leader `.44` 有 11 次 Paxos
   `accept_timeout`。`.44` MON RocksDB `submit_sync_latency` 平均约 233 ms，虚拟根盘长期写
   await 约 441 ms，并曾出现 9.39 秒过期 lease，证据更指向 leader 本地盘尾延迟。为继续
   E3 实验，三 MON 仅在运行时把 `mon_lease` 从 5 秒调到 15 秒（重启即恢复默认）；这会把
   故障检测变慢，只是测试床 workaround。调整后观察 122 秒未再选举、PG 保持全 clean；
   最终方案仍应把 MON store 迁到低延迟盘。

## 5. 尚未覆盖

E3 已完成环境建成、真实发行版 CLI 基础路径、两种 MR 部署形态、committer v1/v2、map
推测执行、DistributedCache 正确分发、DistCp 双向与 `-update` 负向、YARN 日志聚合、Hive
4.0.1 Text/ORC/Parquet/分区/DDL-DML 以及 Spark checkpoint/失败恢复。DistributedCache
跨应用复用、DistCp 其余矩阵、Hive Tez/ACID/授权、Kerberos SP-06B、Spark ORC/提交协议、
NM 长跑 session 归零和系统化故障注入仍属于后续 T11/T12 工作。
