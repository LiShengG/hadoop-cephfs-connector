# Hadoop 生态 spike 实测结论

> 执行日期：2026-08-14；SP-04 E1/E2 修复复验：2026-08-15
> 环境：E1 `10.20.40.26`；E2 三节点 Release Ceph 16.2.14；Hadoop 3.3.6，OpenJDK 11.0.27
> 范围：SP-01～SP-08 的 A 层探针；真实 MR/YARN/Kerberos 组件验证仍待 E3

## 1. 执行与证据

```bash
cd /code/hadoop-cephfs-connector
SPIKE_OUT_DIR=hadoop-cephfs/target/spike-e1-20260814 \
CEPH_BUILD=/code/ceph-v16.2.14/build \
CEPH_CONF_FILE=/code/ceph-v16.2.14/build/ceph.conf \
scripts/spike/run-all.sh
```

- 原始结果：`.26:/code/hadoop-cephfs-connector/hadoop-cephfs/target/spike-e1-20260814/results.tsv`
- 分项日志：`.26:/code/hadoop-cephfs-connector/hadoop-cephfs/target/spike-e1-20260814/logs/`
- 集群：3 mon、1 active mgr、1/1 active MDS、4/4 OSD，17/17 PG `active+clean`
- `HEALTH_WARN` 仅来自 E1 三个 size=1 测试池，符合该单机环境预期
- 探针结束后 `ceph:///` 为空，未残留 `spike-*` 测试目录

## 2. 结论汇总

| ID | 结论 | 实测事实 | 产品影响 |
|---|---|---|---|
| SP-01 | **CONFIRMED** | 同一目录的 owner 随调用 UGI 从 `root` 变为 `spike-other-user`；uid 1 报为数字 `1` | MR staging 属主校验及审计信息不可靠 |
| SP-02 | **CONFIRMED** | POSIX 组成员仍被 `FileSystem.access()` 拒绝，因为状态中 group 为数字 gid `0` | Hive/YARN 的组权限预检会误拒绝 |
| SP-03 | **CONFIRMED** | `setOwner("hive","hadoop")` 无异常但属主不变 | Hive/YARN/运维脚本会误以为 chown 成功 |
| SP-04A | **REFUTED** | 目标已存在时正确抛 `FileAlreadyExistsException` | 该单一分支符合无覆盖语义 |
| SP-04A2 | **REFUTED（已修复）** | URI 默认端口改为 -1 后，目标不存在时普通 `FileContext.rename` 成功 | FileContext rename 主路径恢复，并由专项契约测试保护 |
| SP-04B | **REFUTED（已修复）** | 普通文件改用 MDS 原子 hard-link 抢占后，8 线程连续 5 轮均恰好 1 个成功者、其余为 `FileAlreadyExistsException` | Spark checkpoint 普通文件提交的 no-replace 语义恢复；目录仍不在该增强范围 |
| SP-05 | **CONFIRMED** | 已打开 reader 只能读到首段 1 MiB；新 reader 可读完整 2 MiB | 边写边追读、HBase WAL 类场景不可用 |
| SP-06A | **CONFIRMED** | canonical service 为 null，`addDelegationTokens` 返回 0 个 Token | Kerberos/YARN 只能依赖节点本地 cephx keyring，存在共享凭据边界 |
| SP-07 | **CONFIRMED** | checksum 为 null；DistCp 将 `INCOMPATIBLE` 当作非失败 | `-update`/校验会静默跳过内容校验 |
| SP-08A | **REFUTED** | 顶层日志目录最终权限可达 01777，sticky 位保留 | YARN 顶层目录权限分支可用 |
| SP-08B | **CONFIRMED** | `setOwner("daemon","daemon")` 无异常但仍为 `root:0` | YARN 不会降级或报错，JHS 可能无法读取日志 |
| SP-08C | **REFUTED** | 应用目录权限可从 0755 纠正为 0770 | YARN 应用目录 chmod 分支可用 |

最终修复复验汇总为 7 个 `CONFIRMED`、5 个 `REFUTED`、0 个 `INCONCLUSIVE`。这些是探针子判据数量，
不是“通过率”；`CONFIRMED` 表示风险预测被证实。

## 3. SP-04 修复与并发结论

SP-04 原探针只覆盖“目标已存在”和并发 rename。首次并发结果为 0 成功，但异常详情显示
所有线程实际都在进入 rename 前因 URI 校验失败。加入“目标不存在的普通 rename”基线后，
稳定复现：

```text
org.apache.hadoop.fs.InvalidPathException: Invalid path name Wrong FS:
ceph:/... and port=6789, expected: ceph:/// with port=-1
```

代码侧对应关系是：原 `CephFs#getUriDefaultPort()` 返回 6789，而委托的
`CephFileSystem#getUri()` 对无 authority 的 URI 返回 `ceph:///`（端口 -1）。修复后
`CephFs` 的 URI 默认端口也为 -1：monitor 列表继续由 `ceph.conf` 提供，不把单一 MON
端口混入 Hadoop URI 身份。新增的 FileContext 契约用例分别覆盖“目标不存在时成功”和
“目标存在时拒绝且两端内容不变”；`.26` 上 122/122 单测、专项集成 6/6 通过。

首轮端口修复后以 8 线程重跑 SP-04B，连续 5 轮均有 2 个成功者，确认原实现的 `lstat`
与 POSIX rename 之间存在 TOCTOU。最终修复针对 FileContext 的 `Rename.NONE` 普通文件：
先用 CephFS MDS 的 hard-link 原子抢占目标，再移除源目录项。8 线程连续 5 轮、8 个独立
JVM/独立 mount 均为恰好 1 个成功者，其余统一得到 `FileAlreadyExistsException`。

崩溃注入在 link 后执行 `Runtime.halt(77)`：新 JVM 可识别源/目标双名残留，清理源后目标
保持完整。该方案保证目标原子发布，但不是目录的通用 rename；进程崩溃时允许残留临时源。

Spark 3.4.4 `local[2]` 真实 Structured Streaming 验证使用 CephFS checkpoint 和 Parquet
sink：首次 batch 0/40 行，重启后同 query ID 推进至 batch 1/80 行。双 `spark-submit`
竞争同一新 checkpoint 时，一个成功，另一个在 metadata 原子提交处得到
`SparkConcurrentModificationException`；第三个 JVM 可继续恢复。并发暴露的
`mkdirs` EEXIST 竞态也已修复为“复查最终目标为目录则幂等成功，为文件仍失败”。

- 复验结果：`.26:/code/hadoop-cephfs-connector/hadoop-cephfs/target/spike-sp04-fix-20260815/results.tsv`
- 分项日志：`.26:/code/hadoop-cephfs-connector/hadoop-cephfs/target/spike-sp04-fix-20260815/logs/sp04-filecontext-rename.log`
- Spark 日志：`.26:/code/hadoop-cephfs-connector/hadoop-cephfs/target/spike-spark/`

### 3.1 E2 Release 复验

三节点 E2 使用 3 MON、2 MGR、6 OSD、3 MDS，CephFS 池为 `size=3/min_size=2`，客户端
使用受限 `client.hadoop`。Pacific 16.2.15 Release JNI/libcephfs 在 16.2.14 Release 服务端上、
不设置 lockdep workaround 时，SP-04B 连续 5 轮均严格为 1 成功 + 7 EEXIST。

Spark 顺序重启保持 query ID 并从 batch 0/40 行推进到 batch 1/80 行；双实例竞争为一个成功、
一个 `SparkConcurrentModificationException`，第三 JVM恢复到 batch 1/80 行。未再出现 E1
Debug 客户端曾观察到的 `boost::bad_get`/abort。失败竞争者会留下一个
`.metadata.<uuid>.tmp`，不影响正式 checkpoint 恢复，但需纳入清理口径。

- E2 环境与复现命令：[E2-ENV.md](E2-ENV.md)
- SP-04 日志：`.26:/code/hadoop-cephfs-connector/hadoop-cephfs/target/spike-e2-release/`
- Spark 日志：`.26:/code/hadoop-cephfs-connector/hadoop-cephfs/target/spike-spark-e2-release/`

## 4. 后续门禁

1. E2 Release checkpoint 已完成；下一步在 E3 分布式 Spark 覆盖目录 rename、失败/崩溃后的
   临时源清理。当前结论仅支持普通文件的原子目标发布，不外推到 Delta/Iceberg 全部提交路径。
2. 明确 owner/group 名字映射与 `setOwner` 失败语义；SP-01/02/03/08B 未解决前，不声明
   MR staging、Hive 多用户或 YARN 日志聚合受支持。
3. 在 E3 运行真实 MR 提交、YARN 日志聚合、Spark checkpoint、DistCp 与 Kerberos 容器验证。
4. 将无 checksum、无委托 Token和已打开 reader 长度快照写入支持矩阵与安全边界。
