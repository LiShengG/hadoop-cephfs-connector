# Hadoop 生态组件使用场景测试设计

> 配套 [TEST-PLAN.md](TEST-PLAN.md) §11 与 [TEST-CASES.md](TEST-CASES.md) ECO 节。
> 本篇把"生态集成"从 13 条冒烟用例展开为 **78 条按组件真实使用姿势设计的场景用例**。
> 优先级：**P0** 阻断发布 / **P1** 发布前必做 / **P2** 结论化即可。
> 环境：E3 Hadoop 生态集群（除标注外）。

---

## 0. 为什么单独成篇

"能跑通 wordcount"只证明 `create/open/rename/delete` 这条主干可用。生态组件真正
依赖的是**主干之外的一大圈 API 与语义**：所有权与权限的字符串表示、`access()` 鉴权、
`getFileChecksum`、`truncate`、`FileContext` 的 rename 语义、写入中文件的长度可见性、
`getContentSummary`、目录列举的规模行为、委托 Token……

连接器在这些点上要么是基类默认实现，要么带着明确的语义偏差（见 §2）。
**这些偏差不会在冒烟测试里暴露，只会在生产作业里暴露**——所以本篇的设计原则是：
*从组件的实际使用姿势反推它依赖哪些 FS 行为，再对照连接器的实现事实定风险等级*，
而不是"每个组件跑一个 hello world"。

---

## 1. 方法

```
组件的真实使用场景（含配置项与具体目录）
        │
        ▼  反推
依赖的 FileSystem / FileContext API 与语义假设
        │
        ▼  对照
连接器实现事实（§2 支撑面盘点）
        │
        ▼
风险等级 → 用例 + 判定标准 + 预测失败模式
```

高风险且**可在小成本下提前证伪**的，抽成 §3 的 spike 用例，**先于 T08 的大规模建设执行**——
若其中任何一条成立，T11 的工作量与产品路线都会变，越早知道越好。

---

## 2. 连接器 API 支撑面盘点（生态依赖视角）

| Hadoop FS API / 语义 | 连接器实现事实 | 依赖它的组件场景 | 风险 |
|---|---|---|---|
| `getFileStatus().getOwner()` | uid == 进程 uid 时返回 UGI 短用户名，**否则返回数字字符串**；group **恒为数字字符串** | MR staging 目录属主校验、YARN 日志聚合目录、Hive 仓库目录属主、Ranger/权限审计 | **高** |
| `access(Path, FsAction)` | 未覆写 → 基类用 `getFileStatus` 的 owner/group **字符串**与 UGI 比对 | Hive 授权预检、YARN 本地化预检 | **高** |
| `setOwner(path, user, group)` | **仅接受数字 uid/gid**，非数字仅 warn 后跳过 | Hive 建库建表 chown、YARN 目录准备、运维脚本 | **高** |
| 权限位 | `mode & 01777`（保留 sticky，**丢弃 setuid/setgid**） | YARN 日志聚合目录（1777）、Hive scratchdir（733）、JHS done-dir（1777/770） | 中 |
| 写入中文件的长度对**新** reader 可见性 | 依赖 `lstat`，需实测 | HBase WAL 回放、Spark 读正在写的日志 | **高** |
| 写入中文件对**已打开** reader 可见性 | **不可见**（`CephInputStream` open 时刻快照 `fileLength`） | 流式 tail 读、HBase WAL split | **高** |
| `getFileChecksum` | 未覆写 → 返回 **null** | DistCp `-update`/校验、Hive/Spark 数据校验工具 | 中 |
| `truncate` | 未实现 → 基类 `UnsupportedOperationException` | Flink `RecoverableWriter`、部分 ACID/回滚路径 | 中 |
| `concat` | 未实现 | 小文件合并类工具 | 低 |
| `hasPathCapability` | **仅** `FS_APPEND` / `FS_PERMISSIONS` 为 true，其余（truncate/concat/ACL/XAttr/快照/符号链接/存储策略）**全 false** | 组件按能力探测走分支的场景（Spark/HBase 的能力检查） | 中（多为"正确地退化"） |
| `StreamCapabilities` | 流仅声明 `HFLUSH`/`HSYNC` | `hbase.unsafe.stream.capability.enforce` 校验 | 中 |
| `hflush()`/`hsync()` | 均映射为 **ceph fsync**（每次全量） | HBase WAL、Spark event log、YARN 日志聚合 | 中（正确但可能慢） |
| `FileContext.rename`（无 OVERWRITE） | `CephFs` 仅覆写 `getUriDefaultPort`，其余全委托；走基类 `FileSystem.rename(src,dst,opts)` 的检查逻辑 | **Spark Structured Streaming checkpoint**、Delta/Iceberg 的"原子创建" | **高** |
| `listStatus` | `listdir` + 逐项 `lstat`（N+1 次 RPC） | Hive 分区扫描、Spark `InMemoryFileIndex` 并行列举 | 中（性能） |
| `listStatusIterator`/`listLocatedStatus` | 基类默认（**非增量**，一次性全量） | 超大目录扫描的内存占用 | 中 |
| `getContentSummary` | 基类 Java 侧递归 | Hive 统计与 split 计算、`-du` | 中（性能） |
| `getFileBlockLocations` | 真实 OSD 地址；失败降级 localhost；**topologyPaths 为空**（无机架） | MR/Spark split 本地性与机架感知调度 | 中 |
| `addDelegationTokens` | 基类默认 → **无 Token** | **Kerberos 安全集群**下的 YARN 作业提交 | **高** |
| `getTrashRoot` | 基类默认 `/user/<user>/.Trash` | Hive DROP TABLE、`-rm` | 低 |
| `setReplication` | 恒 false | 依赖设置副本数的运维脚本 | 低 |
| 符号链接 | 不支持 | 少数组件的软链布局 | 低 |
| `msync` / 存储策略 / ACL / XAttr / 快照 | 不支持 | HDFS 特有功能的组件分支 | 低（需"正确地失败"） |

---

## 3. 高风险预测清单（**spike 用例，先做**）

以下最初是基于代码事实的预测。2026-08-14 已在 `.26` E1 完成 A 层实测，正式结论见
[ECO-FINDINGS.md](ECO-FINDINGS.md)；真实组件与 Kerberos B 层仍待 E3。结论直接影响
T11 范围与附录 A。

**已配套可执行探针**：`scripts/spike/`（A 层只需现有 vstart 集群，约 10 分钟跑完；
B 层需 Hadoop 发行版 / Kerberos 集群）。判据自检见 `scripts/spike/control-localfs.sh`。

**Hadoop 侧逻辑已逐条核对 3.3.6 源码**（不是凭记忆），核对结果：

| 对照点 | 3.3.6 源码事实 |
|---|---|
| MR staging 校验 | `JobSubmissionFiles#getStagingDir` 用 `FileStatus#getOwner()` 与 4 个候选名做**字符串**比对，不中即抛 IOException |
| `access()` 鉴权 | `FileSystem#checkAccessPermissions` 用 `user.equals(stat.getOwner())` 与 `ugi.getGroups().contains(stat.getGroup())`，都是**名字**比对 |
| FileContext rename | `DelegateToFileSystem#renameInternal` → `fsImpl.rename(src,dst,Rename.NONE)` → 基类 3 参 rename：dst 存在且无 OVERWRITE → `FileAlreadyExistsException`；`rename()` 返回 false → `IOException("rename from X to Y failed.")` |
| DistCp 校验 | `DistCpUtils#checksumsAreEqual`：任一侧 checksum 为 null → `INCOMPATIBLE`；调用方 `checksumResult = !equals(FALSE)` → **INCOMPATIBLE 被当作通过**，即静默跳过校验 |
| YARN 日志聚合 | `LogAggregationFileController`：TLDIR 01777（不符仅 WARN）、APP_DIR 0770；`setOwner` **只捕获 `UnsupportedOperationException`** |

> **判据设计原则**（开发探针时踩坑总结）：判据必须能区分"连接器的问题"与
> "任何 FS 都会如此"。例如"目录属于别人 → 属主校验失败"在 HDFS 上同样成立，
> 不能作为判据；决定性判据是**同一目录换个 UGI 去读，报告的属主跟着变**，
> 以及**属于系统真实账号的目录被报成数字串**（名字型 FS 会报出用户名）。

| ID | 预测的失败模式 | 依据 | 若成立的影响 | 判定方式 |
|---|---|---|---|---|
| **SP-01** | **MR 作业提交失败 + 属主"谁问就报谁"**：`ownerName` 在 uid == 进程 uid 时返回**当前 UGI 名**、否则返回**数字串** | `CephFileSystem#ownerName` | `fs.defaultFS=ceph://` 的 MR 作业提交失败；属主信息不可信（proxy user 尤甚） | `sp01-owner-group.sh`：A 自建目录；**B 换 UGI 读同一目录看属主是否跟着变**；C chown 给系统真实账号看是否只能报数字 |
| **SP-02** | **Hive/YARN 的 `access()` 预检误判**：基类 `access` 用 owner/group 字符串与 UGI 比对，group 恒为数字 → 组权限永远匹配不上 | 基类 `FileSystem#access` + `groupName()` | Hive 授权预检、NM 本地化预检出现"有权限却报无权限" | 构造仅靠组权限可访问的目录，调用 `fs.access` |
| **SP-03** | **chown 静默失效**：Hive 建库/建表、YARN 目录准备普遍执行 `setOwner("hive","hadoop")`，连接器仅 warn 跳过 | `CephFileSystem#setOwner` | 目录属主与组件预期不符，级联触发 SP-01/SP-02 | `hadoop fs -chown` + 组件建库流程 |
| **SP-04** | **Spark Structured Streaming checkpoint 语义不符**：`CheckpointFileManager` 依赖"目标已存在时 rename 失败"的原子创建；`CephFs` 全量委托，该路径走基类 `FileSystem.rename(src,dst,opts)` | `CephFs` 仅覆写 `getUriDefaultPort` | 流作业 checkpoint 不可靠或直接失败 | A 层已发现普通 rename 因默认端口 6789/-1 不一致直接失败；修复后重跑并发与真实流作业 |
| **SP-05** | **HBase WAL 不可用**：WAL 回放需读"正在写入"的文件并看到已 `hflush` 的数据；连接器 reader 在 open 时刻快照长度 | `CephInputStream` 构造器 | HBase 判定为"不支持"（本就是评估项，但需明确结论） | 一边写一边 `hflush`，另一 JVM 新开 reader 读取 |
| **SP-06** | **安全（Kerberos）集群不可用**：连接器不提供委托 Token，NM 容器内需自带 cephx keyring；意味着 keyring 必须分发到所有节点且对作业用户可读 → **任何用户都能取得该 cephx 身份的全部权限** | 基类 `addDelegationTokens` + 单一 cephx id 模型 | 安全集群的适用边界需在 DEPLOY.md 明确写死 | 在 Kerberized E3 上提交作业 |
| **SP-07** | **DistCp `-update` 退化为按大小/时间比对**：`getFileChecksum` 返回 null | 基类默认 | 跨集群校验能力缺失，需给出使用口径 | HDFS→Ceph `-update` + 人为构造同长度不同内容 |
| **SP-08** | **YARN 日志聚合目录属主静默错位**：权限 01777/0770 可达（sticky 保留），但 `setOwner(用户名,组名)` 静默跳过且不抛 `UnsupportedOperationException` → YARN 既不降级也不报错 | `setOwner` 仅认数字 | JHS 可能读不到日志目录（Hadoop 源码注释自己警告过） | `sp08-logagg-dirs.sh`：复刻 `verifyAndCreateRemoteLogDir` 序列，chown 目标取另一真实账号 |

> **要求**：每条 spike 的结论（成立/不成立/部分成立）连同复现命令与原始输出，写入
> `docs/FAULT-BEHAVIOR.md` 的同体例文件 `docs/ECO-FINDINGS.md`，并回写附录 A。

---

## 4. 分组件场景用例

### 4.1 FsShell / 运维 CLI（E2 亦可）

| ID | P | 场景 | 判定 |
|---|---|---|---|
| ECO-CLI-01 | P0 | 真实发行版 `bin/hadoop fs` 全命令面（现有 21 项断言在发行版上重跑） | 全部退出码与输出一致 |
| ECO-CLI-02 | P1 | `-chown` / `-chgrp` / `-chmod` 三件套 | 与 §3 SP-03 结论一致；文档写明口径 |
| ECO-CLI-03 | P1 | `-du` / `-count` / `-df`（含配额列） | 数值与 `ceph df` 交叉校验一致 |
| ECO-CLI-04 | P1 | `-getmerge` / `-appendToFile` / `-touchz` / `-truncate` | 前三成功；`-truncate` **明确失败且消息可读** |
| ECO-CLI-05 | P1 | `-setrep` | 返回"不支持"语义，不误导用户 |
| ECO-CLI-06 | P1 | `-rm` 走 Trash + `-expunge` | Trash 目录创建与清理正常 |
| ECO-CLI-07 | P2 | `-checksum` | 输出 null/空的表现可接受且文档化 |
| ECO-CLI-08 | P2 | `hadoop archive`(HAR) 创建与读取 | 结论化（成功/不支持） |

### 4.2 MapReduce

| ID | P | 场景 | 依赖的 FS 行为 | 判定 |
|---|---|---|---|---|
| ECO-MR-01 | P0 | `fs.defaultFS=ceph://` 提交 wordcount | staging 目录属主/权限校验（SP-01） | 提交成功，作业完成 |
| ECO-MR-02 | P0 | HDFS 为默认 FS，仅输入输出用 `ceph://` | 跨 FS 读写 | 成功（这是更可能的生产形态，须双形态都测） |
| ECO-MR-03 | P0 | FileOutputCommitter **v1** 提交 | `_temporary` 两级目录 + 目录 rename | 结果正确；无残留 `_temporary` |
| ECO-MR-04 | P0 | FileOutputCommitter **v2** 提交 | 任务级 rename | 同上 |
| ECO-MR-05 | P1 | 推测执行开启（`mapreduce.map.speculative=true`） | 并发 attempt 目录 + 竞态 rename | 无重复/丢失输出 |
| ECO-MR-06 | P1 | 任务失败重试 / AM 重启恢复 | 残留 attempt 目录清理 | 作业最终成功，输出唯一 |
| ECO-MR-07 | P1 | 大量小文件输入（1 万个）split 计算 | `listStatus` + `getFileBlockLocations` | 提交耗时可接受（数据进 T10 基线） |
| ECO-MR-08 | P1 | 大文件输入 split 本地性 | BlockLocation 真实 OSD 主机 | 本地性命中率记录；对照全 localhost 降级 |
| ECO-MR-09 | P1 | JobHistory 中间/完成目录（1777/770 权限） | 权限位 + 属主 | JHS 可写、作业历史可查 |
| ECO-MR-10 | P1 | DistributedCache（`-files`/`-archives` 指向 `ceph://`） | 本地化下载 + mtime/size 一致性判定 | 资源正确分发；缓存命中正常 |
| ECO-MR-11 | P1 | 输出目录已存在的负向场景 | `FileAlreadyExistsException` | 报错与 HDFS 一致 |
| ECO-MR-12 | P1 | TeraGen/TeraSort/TeraValidate 100GB | 全链路 | Validate 通过；耗时进 T10 |
| ECO-MR-13 | P1 | **作业运行中注入 MDS failover**（与 REL-F01 交叉） | 恢复能力 | 作业成功或可重试成功，输出正确 |
| ECO-MR-14 | P2 | 压缩输出（gzip/snappy/bzip2）+ 可切分性 | seek/available | 读回一致；bzip2 切分正常 |

### 4.3 YARN

| ID | P | 场景 | 依赖的 FS 行为 | 判定 |
|---|---|---|---|---|
| ECO-YARN-01 | P0 | 日志聚合到 `ceph://`（`yarn.nodemanager.remote-app-log-dir`） | `FileContext` + 权限 1777/770 + 属主 + `hflush` | `yarn logs -applicationId` 可查全 |
| ECO-YARN-02 | P1 | 日志聚合 file-controller = TFile / IFile 两种 | 流写入与随机读 | 两种均可查 |
| ECO-YARN-03 | P1 | NM 长跑 24h 的 mount/会话累积（呼应 SOAK-05） | `CephFs` 无 close 钩子 | 会话数稳态或给出运维口径 |
| ECO-YARN-04 | P1 | 公共资源本地化（public cache） | 世界可读 + 祖先可执行的权限判定 | 资源按 PUBLIC 可见性分发（而非静默降级为 PRIVATE） |
| ECO-YARN-05 | P1 | 私有/应用级资源本地化 | `copyToLocalFile` | 正常 |
| ECO-YARN-06 | P1 | 容器以非提交用户（`yarn` 用户）读写 `ceph://` | 单一 cephx id 模型 | 行为固化，写入安全模型章节 |
| ECO-YARN-07 | P2 | RM 状态存储指向 `ceph://`（若配置支持） | 原子写 + rename | 结论化（推荐/不推荐） |
| ECO-YARN-08 | P2 | 节点标签/资源文件在 `ceph://` | 读取 | 结论化 |

### 4.4 Hive

| ID | P | 场景 | 依赖的 FS 行为 | 判定 |
|---|---|---|---|---|
| ECO-HIVE-01 | P0 | 建库建表（warehouse 在 `ceph://`） | `mkdirs` + `setOwner`（SP-03） + 权限 | 成功；属主符合预期或差异文档化 |
| ECO-HIVE-02 | P0 | 外部表读（TEXT/ORC/Parquet） | `listStatus` + split + seek | 结果正确 |
| ECO-HIVE-03 | P0 | `INSERT INTO` / `INSERT OVERWRITE` | staging 目录 + 目录 rename + delete | 数据正确；无残留 `.hive-staging` |
| ECO-HIVE-04 | P0 | 动态分区插入（≥ 500 分区） | 大量 mkdirs/rename、小文件 | 成功；耗时记录；MDS 无告警 |
| ECO-HIVE-05 | P1 | 分区表扫描（1 万分区） | `listStatus` N+1 | 耗时可接受（对照 T10 结论） |
| ECO-HIVE-06 | P1 | `ANALYZE TABLE` 统计 | `getContentSummary` | 统计正确；耗时记录 |
| ECO-HIVE-07 | P1 | `MSCK REPAIR TABLE` | 递归列举 | 分区恢复正确 |
| ECO-HIVE-08 | P1 | `DROP TABLE`（含 Trash 开启） | delete / Trash rename | 数据按预期进 Trash |
| ECO-HIVE-09 | P1 | `TRUNCATE TABLE`（managed） | delete+mkdir 或 `truncate()` | 成功；若走 `truncate()` 则须明确失败模式 |
| ECO-HIVE-10 | P1 | CTAS（`CREATE TABLE AS SELECT`） | 全链路 | 成功 |
| ECO-HIVE-11 | P1 | scratchdir（`/tmp/hive`，权限 733）与会话清理 | 权限位 + 递归 delete | 无残留 |
| ECO-HIVE-12 | P2 | ACID/事务表（base/delta + compaction） | 目录 rename + 列举 + 可能的 `truncate` | 结论化：支持/不支持/受限 |
| ECO-HIVE-13 | P2 | 授权（SQL Standard / Ranger）预检 | `access()`（SP-02） | 无"有权限却报无权限" |
| ECO-HIVE-14 | P1 | 引擎 = MR 与 Tez 两种执行引擎 | 各自 committer | 均通过（Tez 见 4.7） |

### 4.5 Spark

| ID | P | 场景 | 依赖的 FS 行为 | 判定 |
|---|---|---|---|---|
| ECO-SPK-01 | P0 | 批作业读写 Parquet/ORC（`ceph://`） | 列举 + seek + 提交协议 | 数据正确 |
| ECO-SPK-02 | P0 | `HadoopMapReduceCommitProtocol` v1/v2 提交 | `_temporary` + rename | 无重复/丢失；`_SUCCESS` 生成 |
| ECO-SPK-03 | P0 | **Structured Streaming checkpoint**（SP-04） | `FileContext` 原子创建语义 | 流作业可恢复；重启后不重复不丢 |
| ECO-SPK-04 | P1 | Event log（`spark.eventLog.dir=ceph://`）+ rolling | `hflush` + 追加 | History Server 可正常展示（含运行中作业） |
| ECO-SPK-05 | P1 | `InMemoryFileIndex` 并行分区发现（1 万分区目录） | 并发 `listStatus`（共享 mount 线程安全） | 无错乱；耗时记录 |
| ECO-SPK-06 | P1 | 动态分区覆盖（`partitionOverwriteMode=dynamic`） | 目录 rename + delete | 数据正确 |
| ECO-SPK-07 | P1 | 推测执行 + task 重试 | 竞态写 | 输出唯一 |
| ECO-SPK-08 | P1 | Spark on YARN（cluster 模式）+ 依赖分发 | 本地化 + staging | 成功 |
| ECO-SPK-09 | P2 | Delta Lake / Iceberg 表（依赖原子创建） | 同 SP-04 | 结论化：支持/不支持 |
| ECO-SPK-10 | P2 | RDD checkpoint / `saveAsTextFile` | 基础路径 | 成功 |

### 4.6 HBase（评估项，非承诺）

| ID | P | 场景 | 判定 |
|---|---|---|---|
| ECO-HB-01 | P1 | `hbase.unsafe.stream.capability.enforce` 的能力检查 | 通过（流声明 hflush/hsync） |
| ECO-HB-02 | P1 | WAL 写入 + `hflush` 持久性（kill -9 后回放） | 已 hflush 数据必须可回放（结合 SP-05） |
| ECO-HB-03 | P1 | 读"正在写入"的 WAL（split 场景） | SP-05 结论 |
| ECO-HB-04 | P2 | Region flush / compaction（大量 rename+delete） | 正确性 |
| ECO-HB-05 | P1 | **最终结论** | 明确"支持 / 不支持 / 受限支持（附条件）"，写入 README 与 DEPLOY.md |

### 4.7 Tez / Flink（评估项）

| ID | P | 场景 | 判定 |
|---|---|---|---|
| ECO-TEZ-01 | P1 | Tez 作为 Hive 执行引擎跑典型查询 | 成功；`tez.lib.uris` 指向 `ceph://` 亦可 |
| ECO-TEZ-02 | P2 | Tez DAG 恢复数据（依赖 hflush） | 结论化 |
| ECO-FLK-01 | P2 | Flink 批作业读写 `ceph://` | 成功 |
| ECO-FLK-02 | P2 | Flink checkpoint / StreamingFileSink（依赖 `truncate` 的 RecoverableWriter） | 结论化：不支持则明确降级路径 |

### 4.8 DistCp 与数据搬迁

| ID | P | 场景 | 判定 |
|---|---|---|---|
| ECO-DCP-01 | P0 | HDFS → Ceph 全量 | 逐文件一致 |
| ECO-DCP-02 | P0 | `-update` 增量（SP-07） | 语义结论明确；同长度不同内容的行为记录 |
| ECO-DCP-03 | P1 | `-p`（保留权限/属主/时间戳） | 属主保留受 `setOwner` 限制的实际表现 |
| ECO-DCP-04 | P1 | Ceph → HDFS 反向 | 一致 |
| ECO-DCP-05 | P1 | Ceph → Ceph（跨子目录/跨集群） | 一致 |
| ECO-DCP-06 | P1 | 大规模（10 万文件）+ 中途失败重跑 | 幂等；无损坏 |
| ECO-DCP-07 | P2 | `-diff` / 快照相关选项 | 明确不支持（无快照能力） |

### 4.9 安全集群与多租户部署形态

| ID | P | 场景 | 判定 |
|---|---|---|---|
| ECO-SEC-01 | P0 | **Kerberized 集群**提交作业（SP-06） | 结论化 + DEPLOY.md《安全模型与适用边界》 |
| ECO-SEC-02 | P0 | keyring 在集群内的分发方式与可读性风险 | 明确风险与推荐做法（每租户独立 cephx + `ceph.root.dir`） |
| ECO-SEC-03 | P1 | proxy user（Hive/Oozie 代提交）场景 | 与 SP-01/SP-02 结论一致 |
| ECO-SEC-04 | P1 | 多租户：两租户各自 `ceph.root.dir` + 受限 caps | 越界访问必失败 |
| ECO-SEC-05 | P1 | 作业日志/异常栈不含 cephx key | grep 断言 0 命中 |

### 4.10 混合部署与迁移

| ID | P | 场景 | 判定 |
|---|---|---|---|
| ECO-MIX-01 | P0 | HDFS 与 `ceph://` 双 scheme 共存 | 无 classpath / native 冲突 |
| ECO-MIX-02 | P1 | 同一作业跨 FS 读写（HDFS 读 → Ceph 写，反向亦然） | 成功 |
| ECO-MIX-03 | P1 | 逐步迁移：Hive 表分区分布在两种 FS | 查询正确 |
| ECO-MIX-04 | P1 | 连接器 jar 与发行版自带 libcephfs 共存/冲突 | 明确加载优先级与冲突表现 |
| ECO-MIX-05 | P2 | 集群内滚动升级连接器（混版） | 可行或明确禁止 |

---

## 5. 判定与准出

1. **所有 P0 场景通过**；P1 通过或有书面豁免；P2 至少有书面结论；
2. **每个"不支持"的组件能力都要有明确失败与可读消息**，不得静默产生错误数据
   （这是比"支持"更重要的判定——生态组件最怕的是"看起来成功了但数据不对"）；
3. `docs/ECO-FINDINGS.md` 记录全部 spike 与场景结论（复现命令 + 原始输出）；
4. README 与 DEPLOY.md 更新**《组件支持矩阵》**：每个组件标注
   支持 / 受限支持（附条件）/ 不支持，并给出配置示例；
5. 附录 A 中由本篇触发的待决项（A-2 可见性、A-5 checksum、
   新增的"owner/group 字符串映射"与"委托 Token/安全模型"）逐项结论化。

---

## 6. 自动化与执行成本

| 分组 | 用例数 | 自动化 | 说明 |
|---|---|---|---|
| spike（§3） | 8 | 半自动 | 优先执行，3–5 天 |
| CLI | 8 | 100% | 复用现有 e2e 脚本扩展 |
| MapReduce | 14 | 90% | `scripts/eco/mr-suite.sh` |
| YARN | 8 | 70% | 日志聚合/本地化需集群级配置切换 |
| Hive | 14 | 80% | `scripts/eco/hive-suite.sh`（SQL 驱动） |
| Spark | 10 | 90% | `scripts/eco/spark-suite.sh` |
| HBase | 5 | 50% | 评估项 |
| Tez/Flink | 4 | 50% | 评估项 |
| DistCp | 7 | 100% | |
| 安全/多租户 | 5 | 60% | Kerberos 环境准备成本高 |
| 混合部署 | 5 | 70% | |
| **合计** | **88** | **≈ 80%** | 其中 P0 共 20 条 |

> 组件支持矩阵是本篇的最终对外交付物——用户读它来判断"我的技术栈能不能用这个连接器"，
> 它比任何一条用例都重要。
