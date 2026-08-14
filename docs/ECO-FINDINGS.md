# Hadoop 生态 spike 实测结论

> 执行日期：2026-08-14  
> 环境：E1 `10.20.40.26`，Ceph 16.2.14，Hadoop 3.3.6，OpenJDK 11.0.27  
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
| SP-04A2 | **CONFIRMED** | 目标不存在时普通 `FileContext.rename` 抛 `InvalidPathException`：实际路径端口 6789，而 `CephFs` 期望 -1 | FileContext rename 主路径不可用，直接阻断依赖它的组件 |
| SP-04B | **INCONCLUSIVE** | 普通 rename 已失败，不能用 0 个并发成功者判定竞态原子性 | 修复 SP-04A2 后必须重跑并发竞态 |
| SP-05 | **CONFIRMED** | 已打开 reader 只能读到首段 1 MiB；新 reader 可读完整 2 MiB | 边写边追读、HBase WAL 类场景不可用 |
| SP-06A | **CONFIRMED** | canonical service 为 null，`addDelegationTokens` 返回 0 个 Token | Kerberos/YARN 只能依赖节点本地 cephx keyring，存在共享凭据边界 |
| SP-07 | **CONFIRMED** | checksum 为 null；DistCp 将 `INCOMPATIBLE` 当作非失败 | `-update`/校验会静默跳过内容校验 |
| SP-08A | **REFUTED** | 顶层日志目录最终权限可达 01777，sticky 位保留 | YARN 顶层目录权限分支可用 |
| SP-08B | **CONFIRMED** | `setOwner("daemon","daemon")` 无异常但仍为 `root:0` | YARN 不会降级或报错，JHS 可能无法读取日志 |
| SP-08C | **REFUTED** | 应用目录权限可从 0755 纠正为 0770 | YARN 应用目录 chmod 分支可用 |

汇总为 8 个 `CONFIRMED`、3 个 `REFUTED`、1 个 `INCONCLUSIVE`。这些是探针子判据数量，
不是“通过率”；`CONFIRMED` 表示风险预测被证实。

## 3. 新发现：FileContext URI 默认端口不一致

SP-04 原探针只覆盖“目标已存在”和并发 rename。首次并发结果为 0 成功，但异常详情显示
所有线程实际都在进入 rename 前因 URI 校验失败。加入“目标不存在的普通 rename”基线后，
稳定复现：

```text
org.apache.hadoop.fs.InvalidPathException: Invalid path name Wrong FS:
ceph:/... and port=6789, expected: ceph:/// with port=-1
```

代码侧对应关系是：`CephFs#getUriDefaultPort()` 返回 6789，而委托的
`CephFileSystem#getUri()` 对无 authority 的 URI 返回 `ceph:///`（端口 -1）。这是当前
最优先的产品缺陷；修复并补齐 FileContext 契约测试后，才能重跑 SP-04B 判断并发语义。

## 4. 后续门禁

1. 修复 SP-04A2，并接入官方 FileContext 契约套件，随后重跑 SP-04A/A2/B。
2. 明确 owner/group 名字映射与 `setOwner` 失败语义；SP-01/02/03/08B 未解决前，不声明
   MR staging、Hive 多用户或 YARN 日志聚合受支持。
3. 在 E3 运行真实 MR 提交、YARN 日志聚合、Spark checkpoint、DistCp 与 Kerberos 容器验证。
4. 将无 checksum、无委托 Token和已打开 reader 长度快照写入支持矩阵与安全边界。

