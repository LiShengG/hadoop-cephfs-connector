# Ecosystem test cases

This file defines stable ecosystem and spike test IDs. Component support claims require dated reports; current limitations link to `KNOWN-LIMITATIONS.md`.

Priorities: P0 blocks its gate; P1 is required; P2 requires an explicit support conclusion.

**Group: Ecosystem spikes**

## SP-01: MR 作业提交失败 + 属主"谁问就报谁"：ownerName 在 uid == 进程 uid 时返回当前 UGI 名、否则返回数字串

- Purpose: [P0 spike] MR 作业提交失败且属主信息随查询 UGI 变化；非本地 uid 仅报告数字串
- Preconditions: Implementation basis: `CephFileSystem#ownerName`. Observable impact to evaluate: `fs.defaultFS=ceph://` 的 MR 作业提交失败；属主信息不可信（proxy user 尤甚）.
- Steps: `sp01-owner-group.sh`：A 自建目录；**B 换 UGI 读同一目录看属主是否跟着变**；C chown 给系统真实账号看是否只能报数字
- Expected result: Record CONFIRMED, REFUTED, or PARTIAL with the observed behavior and evidence.
- Required environment: E1 for the A-layer; E2/E3 or an isolated security environment for the required B-layer

## SP-02: Hive/YARN 的 access() 预检误判：基类 access 用 owner/group 字符串与 UGI 比对，group 恒为数字 → 组权限永远匹配不上

- Purpose: [P0 spike] Hive/YARN 的 `access()` 预检使用 owner/group 名称比对时误判数字 group
- Preconditions: Implementation basis: 基类 `FileSystem#access` + `groupName()`. Observable impact to evaluate: Hive 授权预检、NM 本地化预检出现"有权限却报无权限".
- Steps: 构造仅靠组权限可访问的目录，调用 `fs.access`
- Expected result: Record CONFIRMED, REFUTED, or PARTIAL with the observed behavior and evidence.
- Required environment: E1 for the A-layer; E2/E3 or an isolated security environment for the required B-layer

## SP-03: chown 静默失效：Hive 建库/建表、YARN 目录准备普遍执行 setOwner("hive","hadoop")，连接器仅 warn 跳过

- Purpose: [P0 spike] Hive/YARN 使用名称调用 `setOwner` 时 chown 可能静默不生效
- Preconditions: Implementation basis: `CephFileSystem#setOwner`. Observable impact to evaluate: 目录属主与组件预期不符，级联触发 SP-01/SP-02.
- Steps: `hadoop fs -chown` + 组件建库流程
- Expected result: Record CONFIRMED, REFUTED, or PARTIAL with the observed behavior and evidence.
- Required environment: E1 for the A-layer; E2/E3 or an isolated security environment for the required B-layer

## SP-04: Spark Structured Streaming checkpoint 原子创建：CheckpointFileManager 依赖目标已存在时 rename 失败

- Purpose: [P0 spike] Spark Structured Streaming checkpoint 依赖目标已存在时 rename 失败的原子创建语义
- Preconditions: Implementation basis: 普通文件 `Rename.NONE` 已用 MDS hard-link 原子抢占；并发 mkdirs EEXIST 复查为目录后幂等成功. Observable impact to evaluate: 多线程、多 JVM、崩溃恢复及 Spark 3.4.4 local checkpoint 已通过；目录 rename 与 E2/E3 仍是边界.
- Steps: 在非 Debug E3 重跑双实例、重启及 Delta/Iceberg 提交
- Expected result: Record CONFIRMED, REFUTED, or PARTIAL with the observed behavior and evidence.
- Required environment: E1 for the A-layer; E2/E3 or an isolated security environment for the required B-layer

## SP-05: HBase WAL 不可用：WAL 回放需读"正在写入"的文件并看到已 hflush 的数据；连接器 reader 在 open 时刻快照长度

- Purpose: [P0 spike] 评估 open 时刻长度快照是否阻止 HBase WAL 读取已 `hflush` 的新数据
- Preconditions: Implementation basis: `CephInputStream` 构造器. Observable impact to evaluate: HBase 判定为"不支持"（本就是评估项，但需明确结论）.
- Steps: 一边写一边 `hflush`，另一 JVM 新开 reader 读取
- Expected result: Record CONFIRMED, REFUTED, or PARTIAL with the observed behavior and evidence.
- Required environment: E1 for the A-layer; E2/E3 or an isolated security environment for the required B-layer

## SP-06: 安全（Kerberos）集群不可用：连接器不提供委托 Token，NM 容器内需自带 cephx keyring；意味着 keyring 必须分发到所有节点且对作业用户可读 → 任何用户都能取得该 cephx 身份的全部权限

- Purpose: [P0 spike] 评估无委托 Token 时 Kerberos/YARN 容器依赖节点 cephx keyring 的安全边界
- Preconditions: Implementation basis: 基类 `addDelegationTokens` + 单一 cephx id 模型. Observable impact to evaluate: 安全集群的适用边界需在 DEPLOY.md 明确写死.
- Steps: 在 Kerberized E3 上提交作业
- Expected result: Record CONFIRMED, REFUTED, or PARTIAL with the observed behavior and evidence.
- Required environment: E1 for the A-layer; E2/E3 or an isolated security environment for the required B-layer

## SP-07: DistCp -update 退化为按大小/时间比对：getFileChecksum 返回 null

- Purpose: [P0 spike] 评估 `getFileChecksum` 返回 null 时 DistCp `-update` 的内容校验退化
- Preconditions: Implementation basis: 基类默认. Observable impact to evaluate: 跨集群校验能力缺失，需给出使用口径.
- Steps: HDFS→Ceph `-update` + 人为构造同长度不同内容
- Expected result: Record CONFIRMED, REFUTED, or PARTIAL with the observed behavior and evidence.
- Required environment: E1 for the A-layer; E2/E3 or an isolated security environment for the required B-layer

## SP-08: YARN 日志聚合目录属主静默错位：权限 01777/0770 可达（sticky 保留），但 setOwner(用户名,组名) 静默跳过且不抛 UnsupportedOperationException → YARN 既不降级也不报错

- Purpose: [P0 spike] 评估 YARN 日志聚合目录权限成功但名称型 `setOwner` 静默跳过的属主错位
- Preconditions: Implementation basis: `setOwner` 仅认数字. Observable impact to evaluate: JHS 可能读不到日志目录（Hadoop 源码注释自己警告过）.
- Steps: `sp08-logagg-dirs.sh`：复刻 `verifyAndCreateRemoteLogDir` 序列，chown 目标取另一真实账号
- Expected result: Record CONFIRMED, REFUTED, or PARTIAL with the observed behavior and evidence.
- Required environment: E1 for the A-layer; E2/E3 or an isolated security environment for the required B-layer

**Group: FsShell and CLI**

## ECO-CLI-01: 真实发行版 bin/hadoop fs 全命令面（现有 21 项断言在发行版上重跑）

- Purpose: [P0] 真实发行版 `bin/hadoop fs` 全命令面（现有 21 项断言在发行版上重跑）
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 全部退出码与输出一致
- Required environment: E2 or E3

## ECO-CLI-02: -chown / -chgrp / -chmod 三件套

- Purpose: [P1] `-chown` / `-chgrp` / `-chmod` 三件套
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 与 §3 SP-03 结论一致；文档写明口径
- Required environment: E2 or E3

## ECO-CLI-03: -du / -count / -df（含配额列）

- Purpose: [P1] `-du` / `-count` / `-df`（含配额列）
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 数值与 `ceph df` 交叉校验一致
- Required environment: E2 or E3

## ECO-CLI-04: -getmerge / -appendToFile / -touchz / -truncate

- Purpose: [P1] `-getmerge` / `-appendToFile` / `-touchz` / `-truncate`
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 前三成功；`-truncate` **明确失败且消息可读**
- Required environment: E2 or E3

## ECO-CLI-05: -setrep

- Purpose: [P1] `-setrep`
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 返回"不支持"语义，不误导用户
- Required environment: E2 or E3

## ECO-CLI-06: -rm 走 Trash + -expunge

- Purpose: [P1] `-rm` 走 Trash + `-expunge`
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: Trash 目录创建与清理正常
- Required environment: E2 or E3

## ECO-CLI-07: -checksum

- Purpose: [P2] `-checksum`
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 输出 null/空的表现可接受且文档化
- Required environment: E2 or E3

## ECO-CLI-08: hadoop archive(HAR) 创建与读取

- Purpose: [P2] `hadoop archive`(HAR) 创建与读取
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 结论化（成功/不支持）
- Required environment: E2 or E3

**Group: MapReduce**

## ECO-MR-01: fs.defaultFS=ceph:// 提交 wordcount

- Purpose: [P0] `fs.defaultFS=ceph://` 提交 wordcount
- Preconditions: Required filesystem behavior: staging 目录属主/权限校验（SP-01）.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 提交成功，作业完成
- Required environment: E3

## ECO-MR-02: HDFS 为默认 FS，仅输入输出用 ceph://

- Purpose: [P0] HDFS 为默认 FS，仅输入输出用 `ceph://`
- Preconditions: Required filesystem behavior: 跨 FS 读写.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 成功（这是更可能的生产形态，须双形态都测）
- Required environment: E3

## ECO-MR-03: FileOutputCommitter v1 提交

- Purpose: [P0] FileOutputCommitter **v1** 提交
- Preconditions: Required filesystem behavior: `_temporary` 两级目录 + 目录 rename.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 结果正确；无残留 `_temporary`
- Required environment: E3

## ECO-MR-04: FileOutputCommitter v2 提交

- Purpose: [P0] FileOutputCommitter **v2** 提交
- Preconditions: Required filesystem behavior: 任务级 rename.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 同上
- Required environment: E3

## ECO-MR-05: 推测执行开启（mapreduce.map.speculative=true）

- Purpose: [P1] 推测执行开启（`mapreduce.map.speculative=true`）
- Preconditions: Required filesystem behavior: 并发 attempt 目录 + 竞态 rename.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 无重复/丢失输出
- Required environment: E3

## ECO-MR-06: 任务失败重试 / AM 重启恢复

- Purpose: [P1] 任务失败重试 / AM 重启恢复
- Preconditions: Required filesystem behavior: 残留 attempt 目录清理.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 作业最终成功，输出唯一
- Required environment: E3

## ECO-MR-07: 大量小文件输入（1 万个）split 计算

- Purpose: [P1] 大量小文件输入（1 万个）split 计算
- Preconditions: Required filesystem behavior: `listStatus` + `getFileBlockLocations`.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 提交耗时可接受（数据进 T10 基线）
- Required environment: E3

## ECO-MR-08: 大文件输入 split 本地性

- Purpose: [P1] 大文件输入 split 本地性
- Preconditions: Required filesystem behavior: BlockLocation 真实 OSD 主机.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 本地性命中率记录；对照全 localhost 降级
- Required environment: E3

## ECO-MR-09: JobHistory 中间/完成目录（1777/770 权限）

- Purpose: [P1] JobHistory 中间/完成目录（1777/770 权限）
- Preconditions: Required filesystem behavior: 权限位 + 属主.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: JHS 可写、作业历史可查
- Required environment: E3

## ECO-MR-10: DistributedCache（-files/-archives 指向 ceph://）

- Purpose: [P1] DistributedCache（`-files`/`-archives` 指向 `ceph://`）
- Preconditions: Required filesystem behavior: 本地化下载 + mtime/size 一致性判定.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 资源正确分发；缓存命中正常
- Required environment: E3

## ECO-MR-11: 输出目录已存在的负向场景

- Purpose: [P1] 输出目录已存在的负向场景
- Preconditions: Required filesystem behavior: `FileAlreadyExistsException`.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 报错与 HDFS 一致
- Required environment: E3

## ECO-MR-12: TeraGen/TeraSort/TeraValidate 100GB

- Purpose: [P1] TeraGen/TeraSort/TeraValidate 100GB
- Preconditions: Required filesystem behavior: 全链路.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: Validate 通过；耗时进 T10
- Required environment: E3

## ECO-MR-13: 作业运行中注入 MDS failover（与 REL-F01 交叉）

- Purpose: [P1] **作业运行中注入 MDS failover**（与 REL-F01 交叉）
- Preconditions: Required filesystem behavior: 恢复能力.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 作业成功或可重试成功，输出正确
- Required environment: E3

## ECO-MR-14: 压缩输出（gzip/snappy/bzip2）+ 可切分性

- Purpose: [P2] 压缩输出（gzip/snappy/bzip2）+ 可切分性
- Preconditions: Required filesystem behavior: seek/available.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 读回一致；bzip2 切分正常
- Required environment: E3

**Group: YARN**

## ECO-YARN-01: 日志聚合到 ceph://（yarn.nodemanager.remote-app-log-dir）

- Purpose: [P0] 日志聚合到 `ceph://`（`yarn.nodemanager.remote-app-log-dir`）
- Preconditions: Required filesystem behavior: `FileContext` + 权限 1777/770 + 属主 + `hflush`.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: `yarn logs -applicationId` 可查全
- Required environment: E3

## ECO-YARN-02: 日志聚合 file-controller = TFile / IFile 两种

- Purpose: [P1] 日志聚合 file-controller = TFile / IFile 两种
- Preconditions: Required filesystem behavior: 流写入与随机读.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 两种均可查
- Required environment: E3

## ECO-YARN-03: NM 长跑 24h 的 mount/会话累积（呼应 SOAK-05）

- Purpose: [P1] NM 长跑 24h 的 mount/会话累积（呼应 SOAK-05）
- Preconditions: Required filesystem behavior: `CephFs` 无 close 钩子.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 会话数稳态或给出运维口径
- Required environment: E3

## ECO-YARN-04: 公共资源本地化（public cache）

- Purpose: [P1] 公共资源本地化（public cache）
- Preconditions: Required filesystem behavior: 世界可读 + 祖先可执行的权限判定.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 资源按 PUBLIC 可见性分发（而非静默降级为 PRIVATE）
- Required environment: E3

## ECO-YARN-05: 私有/应用级资源本地化

- Purpose: [P1] 私有/应用级资源本地化
- Preconditions: Required filesystem behavior: `copyToLocalFile`.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 正常
- Required environment: E3

## ECO-YARN-06: 容器以非提交用户（yarn 用户）读写 ceph://

- Purpose: [P1] 容器以非提交用户（`yarn` 用户）读写 `ceph://`
- Preconditions: Required filesystem behavior: 单一 cephx id 模型.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 行为固化，写入安全模型章节
- Required environment: E3

## ECO-YARN-07: RM 状态存储指向 ceph://（若配置支持）

- Purpose: [P2] RM 状态存储指向 `ceph://`（若配置支持）
- Preconditions: Required filesystem behavior: 原子写 + rename.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 结论化（推荐/不推荐）
- Required environment: E3

## ECO-YARN-08: 节点标签/资源文件在 ceph://

- Purpose: [P2] 节点标签/资源文件在 `ceph://`
- Preconditions: Required filesystem behavior: 读取.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 结论化
- Required environment: E3

**Group: Hive**

## ECO-HIVE-01: 建库建表（warehouse 在 ceph://）

- Purpose: [P0] 建库建表（warehouse 在 `ceph://`）
- Preconditions: Required filesystem behavior: `mkdirs` + `setOwner`（SP-03） + 权限.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 成功；属主符合预期或差异文档化
- Required environment: E3

## ECO-HIVE-02: 外部表读（TEXT/ORC/Parquet）

- Purpose: [P0] 外部表读（TEXT/ORC/Parquet）
- Preconditions: Required filesystem behavior: `listStatus` + split + seek.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 结果正确
- Required environment: E3

## ECO-HIVE-03: INSERT INTO / INSERT OVERWRITE

- Purpose: [P0] `INSERT INTO` / `INSERT OVERWRITE`
- Preconditions: Required filesystem behavior: staging 目录 + 目录 rename + delete.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 数据正确；无残留 `.hive-staging`
- Required environment: E3

## ECO-HIVE-04: 动态分区插入（≥ 500 分区）

- Purpose: [P0] 动态分区插入（≥ 500 分区）
- Preconditions: Required filesystem behavior: 大量 mkdirs/rename、小文件.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 成功；耗时记录；MDS 无告警
- Required environment: E3

## ECO-HIVE-05: 分区表扫描（1 万分区）

- Purpose: [P1] 分区表扫描（1 万分区）
- Preconditions: Required filesystem behavior: `listStatus` N+1.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 耗时可接受（对照 T10 结论）
- Required environment: E3

## ECO-HIVE-06: ANALYZE TABLE 统计

- Purpose: [P1] `ANALYZE TABLE` 统计
- Preconditions: Required filesystem behavior: `getContentSummary`.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 统计正确；耗时记录
- Required environment: E3

## ECO-HIVE-07: MSCK REPAIR TABLE

- Purpose: [P1] `MSCK REPAIR TABLE`
- Preconditions: Required filesystem behavior: 递归列举.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 分区恢复正确
- Required environment: E3

## ECO-HIVE-08: DROP TABLE（含 Trash 开启）

- Purpose: [P1] `DROP TABLE`（含 Trash 开启）
- Preconditions: Required filesystem behavior: delete / Trash rename.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 数据按预期进 Trash
- Required environment: E3

## ECO-HIVE-09: TRUNCATE TABLE（managed）

- Purpose: [P1] `TRUNCATE TABLE`（managed）
- Preconditions: Required filesystem behavior: delete+mkdir 或 `truncate()`.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 成功；若走 `truncate()` 则须明确失败模式
- Required environment: E3

## ECO-HIVE-10: CTAS（CREATE TABLE AS SELECT）

- Purpose: [P1] CTAS（`CREATE TABLE AS SELECT`）
- Preconditions: Required filesystem behavior: 全链路.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 成功
- Required environment: E3

## ECO-HIVE-11: scratchdir（/tmp/hive，权限 733）与会话清理

- Purpose: [P1] scratchdir（`/tmp/hive`，权限 733）与会话清理
- Preconditions: Required filesystem behavior: 权限位 + 递归 delete.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 无残留
- Required environment: E3

## ECO-HIVE-12: ACID/事务表（base/delta + compaction）

- Purpose: [P2] ACID/事务表（base/delta + compaction）
- Preconditions: Required filesystem behavior: 目录 rename + 列举 + 可能的 `truncate`.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 结论化：支持/不支持/受限
- Required environment: E3

## ECO-HIVE-13: 授权（SQL Standard / Ranger）预检

- Purpose: [P2] 授权（SQL Standard / Ranger）预检
- Preconditions: Required filesystem behavior: `access()`（SP-02）.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 无"有权限却报无权限"
- Required environment: E3

## ECO-HIVE-14: 引擎 = MR 与 Tez 两种执行引擎

- Purpose: [P1] 引擎 = MR 与 Tez 两种执行引擎
- Preconditions: Required filesystem behavior: 各自 committer.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 均通过（Tez 见 4.7）
- Required environment: E3

**Group: Spark**

## ECO-SPK-01: 批作业读写 Parquet/ORC（ceph://）

- Purpose: [P0] 批作业读写 Parquet/ORC（`ceph://`）
- Preconditions: Required filesystem behavior: 列举 + seek + 提交协议.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 数据正确
- Required environment: E3

## ECO-SPK-02: HadoopMapReduceCommitProtocol v1/v2 提交

- Purpose: [P0] `HadoopMapReduceCommitProtocol` v1/v2 提交
- Preconditions: Required filesystem behavior: `_temporary` + rename.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 无重复/丢失；`_SUCCESS` 生成
- Required environment: E3

## ECO-SPK-03: Structured Streaming checkpoint（SP-04）

- Purpose: [P0] **Structured Streaming checkpoint**（SP-04）
- Preconditions: Required filesystem behavior: `FileContext` 原子创建语义.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 流作业可恢复；重启后不重复不丢
- Required environment: E3

## ECO-SPK-04: Event log（spark.eventLog.dir=ceph://）+ rolling

- Purpose: [P1] Event log（`spark.eventLog.dir=ceph://`）+ rolling
- Preconditions: Required filesystem behavior: `hflush` + 追加.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: History Server 可正常展示（含运行中作业）
- Required environment: E3

## ECO-SPK-05: InMemoryFileIndex 并行分区发现（1 万分区目录）

- Purpose: [P1] `InMemoryFileIndex` 并行分区发现（1 万分区目录）
- Preconditions: Required filesystem behavior: 并发 `listStatus`（共享 mount 线程安全）.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 无错乱；耗时记录
- Required environment: E3

## ECO-SPK-06: 动态分区覆盖（partitionOverwriteMode=dynamic）

- Purpose: [P1] 动态分区覆盖（`partitionOverwriteMode=dynamic`）
- Preconditions: Required filesystem behavior: 目录 rename + delete.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 数据正确
- Required environment: E3

## ECO-SPK-07: 推测执行 + task 重试

- Purpose: [P1] 推测执行 + task 重试
- Preconditions: Required filesystem behavior: 竞态写.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 输出唯一
- Required environment: E3

## ECO-SPK-08: Spark on YARN（cluster 模式）+ 依赖分发

- Purpose: [P1] Spark on YARN（cluster 模式）+ 依赖分发
- Preconditions: Required filesystem behavior: 本地化 + staging.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 成功
- Required environment: E3

## ECO-SPK-09: Delta Lake / Iceberg 表（依赖原子创建）

- Purpose: [P2] Delta Lake / Iceberg 表（依赖原子创建）
- Preconditions: Required filesystem behavior: 同 SP-04.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 结论化：支持/不支持
- Required environment: E3

## ECO-SPK-10: RDD checkpoint / saveAsTextFile

- Purpose: [P2] RDD checkpoint / `saveAsTextFile`
- Preconditions: Required filesystem behavior: 基础路径.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 成功
- Required environment: E3

**Group: HBase assessment**

## ECO-HB-01: hbase.unsafe.stream.capability.enforce 的能力检查

- Purpose: [P1] `hbase.unsafe.stream.capability.enforce` 的能力检查
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 通过（流声明 hflush/hsync）
- Required environment: E3

## ECO-HB-02: WAL 写入 + hflush 持久性（kill -9 后回放）

- Purpose: [P1] WAL 写入 + `hflush` 持久性（kill -9 后回放）
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 已 hflush 数据必须可回放（结合 SP-05）
- Required environment: E3

## ECO-HB-03: 读"正在写入"的 WAL（split 场景）

- Purpose: [P1] 读"正在写入"的 WAL（split 场景）
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: SP-05 结论
- Required environment: E3

## ECO-HB-04: Region flush / compaction（大量 rename+delete）

- Purpose: [P2] Region flush / compaction（大量 rename+delete）
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 正确性
- Required environment: E3

## ECO-HB-05: 最终结论

- Purpose: [P1] 最终结论
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 明确"支持 / 不支持 / 受限支持（附条件）"，写入 README 与 DEPLOY.md
- Required environment: E3

**Group: Tez and Flink assessment**

## ECO-TEZ-01: Tez 作为 Hive 执行引擎跑典型查询

- Purpose: [P1] Tez 作为 Hive 执行引擎跑典型查询
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 成功；`tez.lib.uris` 指向 `ceph://` 亦可
- Required environment: E3

## ECO-TEZ-02: Tez DAG 恢复数据（依赖 hflush）

- Purpose: [P2] Tez DAG 恢复数据（依赖 hflush）
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 结论化
- Required environment: E3

## ECO-FLK-01: Flink 批作业读写 ceph://

- Purpose: [P2] Flink 批作业读写 `ceph://`
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 成功
- Required environment: E3

## ECO-FLK-02: Flink checkpoint / StreamingFileSink（依赖 truncate 的 RecoverableWriter）

- Purpose: [P2] Flink checkpoint / StreamingFileSink（依赖 `truncate` 的 RecoverableWriter）
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 结论化：不支持则明确降级路径
- Required environment: E3

**Group: DistCp and migration**

## ECO-DCP-01: HDFS → Ceph 全量

- Purpose: [P0] HDFS → Ceph 全量
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 逐文件一致
- Required environment: E3

## ECO-DCP-02: -update 增量（SP-07）

- Purpose: [P0] `-update` 增量（SP-07）
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 语义结论明确；同长度不同内容的行为记录
- Required environment: E3

## ECO-DCP-03: -p（保留权限/属主/时间戳）

- Purpose: [P1] `-p`（保留权限/属主/时间戳）
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 属主保留受 `setOwner` 限制的实际表现
- Required environment: E3

## ECO-DCP-04: Ceph → HDFS 反向

- Purpose: [P1] Ceph → HDFS 反向
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 一致
- Required environment: E3

## ECO-DCP-05: Ceph → Ceph（跨子目录/跨集群）

- Purpose: [P1] Ceph → Ceph（跨子目录/跨集群）
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 一致
- Required environment: E3

## ECO-DCP-06: 大规模（10 万文件）+ 中途失败重跑

- Purpose: [P1] 大规模（10 万文件）+ 中途失败重跑
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 幂等；无损坏
- Required environment: E3

## ECO-DCP-07: -diff / 快照相关选项

- Purpose: [P2] `-diff` / 快照相关选项
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 明确不支持（无快照能力）
- Required environment: E3

**Group: Secure and multi-tenant deployments**

## ECO-SEC-01: Kerberized 集群提交作业（SP-06）

- Purpose: [P0] **Kerberized 集群**提交作业（SP-06）
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 结论化 + DEPLOY.md《安全模型与适用边界》
- Required environment: Isolated E3 security environment

## ECO-SEC-02: keyring 在集群内的分发方式与可读性风险

- Purpose: [P0] keyring 在集群内的分发方式与可读性风险
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 明确风险与推荐做法（每租户独立 cephx + `ceph.root.dir`）
- Required environment: Isolated E3 security environment

## ECO-SEC-03: proxy user（Hive/Oozie 代提交）场景

- Purpose: [P1] proxy user（Hive/Oozie 代提交）场景
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 与 SP-01/SP-02 结论一致
- Required environment: Isolated E3 security environment

## ECO-SEC-04: 多租户：两租户各自 ceph.root.dir + 受限 caps

- Purpose: [P1] 多租户：两租户各自 `ceph.root.dir` + 受限 caps
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 越界访问必失败
- Required environment: Isolated E3 security environment

## ECO-SEC-05: 作业日志/异常栈不含 cephx key

- Purpose: [P1] 作业日志/异常栈不含 cephx key
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: grep 断言 0 命中
- Required environment: Isolated E3 security environment

**Group: Mixed filesystems and migration**

## ECO-MIX-01: HDFS 与 ceph:// 双 scheme 共存

- Purpose: [P0] HDFS 与 `ceph://` 双 scheme 共存
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 无 classpath / native 冲突
- Required environment: E3

## ECO-MIX-02: 同一作业跨 FS 读写（HDFS 读 → Ceph 写，反向亦然）

- Purpose: [P1] 同一作业跨 FS 读写（HDFS 读 → Ceph 写，反向亦然）
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 成功
- Required environment: E3

## ECO-MIX-03: 逐步迁移：Hive 表分区分布在两种 FS

- Purpose: [P1] 逐步迁移：Hive 表分区分布在两种 FS
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 查询正确
- Required environment: E3

## ECO-MIX-04: 连接器 jar 与发行版自带 libcephfs 共存/冲突

- Purpose: [P1] 连接器 jar 与发行版自带 libcephfs 共存/冲突
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 明确加载优先级与冲突表现
- Required environment: E3

## ECO-MIX-05: 集群内滚动升级连接器（混版）

- Purpose: [P2] 集群内滚动升级连接器（混版）
- Preconditions: The component and its test data are ready.
- Steps: Run the component scenario; record the result, data checks, and residue.
- Expected result: 可行或明确禁止
- Required environment: E3
