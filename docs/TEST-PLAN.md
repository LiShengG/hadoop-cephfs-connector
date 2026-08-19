# Core test plan

This file defines stable core test IDs and the verification each one still requires. It is a plan,
not a record of existing tests: most definitions here have no automated test yet, and an ID carries
no result until a dated report cites it. Execution results belong in dated reports; current gate
status belongs in `READINESS.md`. The detailed ecosystem cases are canonical in `TEST-CASES-ECO.md`.

Priorities: P0 blocks its gate; P1 is required; P2 requires an explicit support conclusion.

A case whose expected result is a decision-level filesystem semantic does not state it here. It
delegates to [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md), which owns that expectation and states it
once, per decision condition.

**Group: Unit semantics**

## UT-01: mapCephException 全矩阵：CephFileAlreadyExistsException/CephNotDirectoryException/裸 IOException/CephNotMountedException [P1]

- Preconditions: Implementation or test basis: `CephFileSystem#mapCephException`.
- Expected result: 异常类型与 cause 链逐一符合规范
- Required environment: No cluster

## UT-02: lstatResolved 查询/变更两语境 × 祖先为文件/目录权限不足/纯 FNF [P1]

- Preconditions: Implementation or test basis: `lstatResolved`.
- Expected result: 变更→`ParentNotDirectoryException`，查询→FNF，权限不足原样抛
- Required environment: No cluster

## UT-03: normalizeLayoutSize 边界：0、1、64K-1、64K、64K+1、Long.MAX_VALUE、负数 [P1]

- Preconditions: Implementation or test basis: `normalizeLayoutSize`.
- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: No cluster

## UT-04: getStatus 的 frsize=0/bsize=0/超大 blocks（PB 级）溢出 [P1]

- Preconditions: Implementation or test basis: `getStatus`.
- Expected result: 无负值、无溢出，`used = capacity - remaining`
- Required environment: No cluster

## UT-05: setOwner：数字 uid+gid / 仅 uid / 非数字 / 空串 / null [P1]

- Preconditions: Implementation or test basis: `setOwner`.
- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: No cluster

## UT-06: toFileStatus 权限位：setuid/setgid/sticky/0777 [P1]

- Preconditions: Implementation or test basis: `toFileStatus`.
- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: No cluster

## UT-07: pathString：null、空、相对路径、含空格/UTF-8/%/#、ceph://host/x [P1]

- Preconditions: Implementation or test basis: `CephTalker#pathString`.
- Expected result: 唯一转换入口行为固化
- Required environment: No cluster

## UT-08: getFileBlockLocations 降级：getFileExtent 抛异常 / extent 长度 ≤0 / OSD 列表空 / getOsdAddress 返回 null [P1]

- Preconditions: Implementation or test basis: `getFileBlockLocations`.
- Expected result: 降级为 localhost 并按 blockSize 步进，块覆盖无空洞无重叠，fd 必被 close
- Required environment: No cluster

## UT-09: getFileBlockLocations 入参：file=null、start<0、len<0、start≥len(file)、len=Long.MAX_VALUE [P1]

- Preconditions: Implementation or test basis: 同上.
- Expected result: 分别 null / IAE / 空数组 / 末端钳制无溢出
- Required environment: No cluster

## UT-10: CephOutputStream.writeFully 部分写循环：底层每次只写 1 字节 / 返回 0 / 返回 > 请求数 [P1]

- Preconditions: Implementation or test basis: `writeFully`.
- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: No cluster

## UT-11: CephOutputStream.close 失败路径：flush 抛异常 + close 也抛 [P1]

- Preconditions: Implementation or test basis: `close`.
- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: No cluster

## UT-12: 流关闭后再 write/read/seek/getPos/hsync [P1]

- Preconditions: Implementation or test basis: `ensureOpen`.
- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: No cluster

## UT-13: CephInputStream 缓冲边界：seek 到缓冲头/尾/尾+1、跨缓冲读、read(pos,...) 越界 [P1]

- Preconditions: Implementation or test basis: `fillBuffer`/`seek`.
- Expected result: 数据正确、缓冲失效逻辑正确、越界 EOF 语义一致
- Required environment: No cluster

## UT-14: Statistics 计数：写失败后计数是否虚增；write(int) 单字节路径 [P2]

- Preconditions: Implementation or test basis: `incrementBytesWritten`.
- Expected result: 计数口径书面固化（当前先计数后落盘）
- Required environment: No cluster

## UT-15: initialize 配置解析：ceph.conf.options 非法项（无 =、空段、多 =） [P1]

- Preconditions: Implementation or test basis: `CephTalker#initialize`.
- Expected result: 非法抛 IAE 且消息含原文；空段跳过
- Required environment: No cluster

## UT-16: URI authority 覆盖 mon：有 host 有 port / 有 host 无 port / 无 authority / 含逗号的多 mon 写法 [P1]

- Preconditions: Implementation or test basis: 同上.
- Expected result: 前三种符合设计；多 mon 逗号写法 `getHost()` 为 null → 静默回退 ceph.conf，须固化并写入文档
- Required environment: No cluster

## UT-17: firstDataPool：空串、逗号开头、全空白 [P2]

- Preconditions: Implementation or test basis: `firstDataPool`.
- Expected result: 返回 null 或首个非空
- Required environment: No cluster

## UT-18: streamBufferSize 非法值（0、负、超大） [P2]

- Preconditions: Implementation or test basis: `streamBufferSize`.
- Expected result: 回退默认值
- Required environment: No cluster

## UT-19: close() 幂等 + close 后所有 FS 操作 [P1]

- Preconditions: Implementation or test basis: `CephFileSystem#close`.
- Expected result: 二次 close 无副作用；后续操作抛 `CephNotMountedException`
- Required environment: No cluster

## UT-20: initialize 二次调用 [P2]

- Preconditions: Implementation or test basis: `CephTalker#initialize`.
- Expected result: `IllegalStateException("already initialized")`
- Required environment: No cluster

**Group: Hadoop contracts**

## CT-01: 接入 FSMainOperationsBaseTest → ITestCephFSMainOperations [P0]

- Expected result: 全绿；排除项有书面理由
- Required environment: E1 and E2

## CT-02: 接入 FileContextMainOperationsBaseTest → ITestCephFileContextMainOperations [P0]

- Expected result: 同上（补强 `CephFs`/YARN 路径）
- Required environment: E1 and E2

## CT-03: FileContextCreateMkdirBaseTest [P1]

- Expected result: 全绿
- Required environment: E1 and E2

## CT-04: FileContextPermissionBase [P1]

- Expected result: 全绿或差异书面化（uid/gid 数字化影响）
- Required environment: E1 and E2

## CT-05: FileContextURIBase [P1]

- Expected result: 全绿
- Required environment: E1 and E2

## CT-06: 现有 9 套契约在 E2 三副本非调试构建上重跑 [P0]

- Expected result: 与 E1 结果一致；`lockdep` workaround 不需要
- Required environment: E1 and E2

## CT-07: 契约套件在 ceph.root.dir=/subdir 子目录挂载下重跑 [P1]

- Expected result: 结果一致（根语义相对子目录成立）
- Required environment: E1 and E2

## CT-08: 契约套件在非 admin、最小 caps 用户下重跑 [P1]

- Expected result: 除 root 目录类外全绿
- Required environment: E1 and E2

## CT-09: 契约套件连续 20 轮稳定性 [P2]

- Expected result: flaky < 1%，不稳定用例建单
- Required environment: E1 and E2

**Group: Functional depth**

## FN-01: 单文件 1GB / 10GB 写入读回 [P0]

- Expected result: md5 一致；耗时记录
- Required environment: E2

## FN-02: 单文件 > 2^31 字节的 seek/pread（偏移超 int） [P0]

- Expected result: 无溢出，随机 100 点 pread 全部正确
- Required environment: E2

## FN-03: 单次 write(byte[]) 传入 > 缓冲区的大数组（16MB/64MB） [P1]

- Expected result: 走直写分支正确，无越界
- Required environment: E2

## FN-04: 目录 1 万 / 10 万条目 listStatus/-ls [P1]

- Expected result: 结果完整、无 OOM；耗时进性能基线（N+1 RPC 观察点）
- Required environment: E2

## FN-05: 100 层深目录的 mkdirs/递归 delete/rename [P1]

- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: E2

## FN-06: 特殊文件名：空格、UTF-8 中文、%20、#、+、:、单引号 [P1]

- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: E2

## FN-07: 文件名 255 字节上限与超限 [P1]

- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: E2

## FN-08: 路径总长接近 4096 [P1]

- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: E2

## FN-09: 空文件、稀疏写（seek 后写，中间空洞） [P2]

- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: E2

## FN-10: 5 万文件并发打开（不关闭）后统一关闭 [P1]

- Expected result: 无 fd 泄漏（`openFileDescriptorCount` 归零），无 MDS 告警残留
- Required environment: E2

## FN-11: 写方 append + 读方已打开流：读方能否看到新数据 [P0]

- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: E2

## FN-12: 写方 hflush/hsync 后，新开的 reader 能否读到 [P0]

- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: E2

## FN-13: hsync 后 kill -9 客户端 [P0]

- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: E2

## FN-14: 未 flush 即 kill -9 [P1]

- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: E2

## FN-15: 并发 rename 竞态：两线程同时 rename 同一 src [P1]

- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: E2

## FN-16: 递归 delete 期间并发创建子文件 [P1]

- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: E2

## FN-17: 并发 create 同一路径（overwrite=false） [P1]

- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: E2

## FN-18: rename 目录到自身子树 / 到已存在目录 / 到已存在文件 [P1]

- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: E2

## FN-19: 32 线程共享同一 CephFileSystem 混合读写 30 min [P1]

- Expected result: 无死锁、无数据错乱、fd 归零（现有 4 线程冒烟的加强版）
- Required environment: E2

## FN-20: FileSystem.CACHE 行为：多次 FileSystem.get 复用同一 mount；fs.ceph.impl.disable.cache=true 时每次新 mount [P2]

- Expected result: 会话数变化符合预期（`ceph tell mds session ls`）
- Required environment: E2

## FN-21: 多 UGI（doAs 三个用户）访问 [P1]

- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: E2

## FN-22: ceph.root.dir=/tenantA 子目录挂载全功能 [P1]

- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: E2

## FN-23: Trash：fs.trash.interval>0 下 -rm [P1]

- Expected result: `FsShell` 经 `Trash` 包装层将路径移入 `/user/<user>/.Trash`；家目录不存在时行为可接受
- Required environment: E2

## FN-24: getContentSummary 深/宽目录树 [P1]

- Expected result: 结果正确；耗时进性能基线
- Required environment: E2

## FN-25: getFileBlockLocations 在多 OSD 多主机下 [P1]

- Expected result: hosts 为真实 OSD 主机 IP（与 `ceph osd dump` 可比），块切分与 object 边界一致
- Required environment: E2

## FN-26: Statistics 计数 vs 实际字节数（读/写各 1GB） [P1]

- Expected result: 误差为 0 或书面解释
- Required environment: E2

## FN-27: 13 个配置键逐个改值 → 生效性验证 + 缺省回退 [P1]

- Expected result: 每键至少 1 条断言（含 `ceph.localize.reads`、`ceph.replication`、`ceph.data.pools`）
- Required environment: E2

## FN-28: ceph.data.pools 指定非默认 data pool [P2]

- Expected result: 文件确实落在指定 pool（`ceph osd map` 校验）
- Required environment: E2

## FN-29: -df / getStatus 与 ceph df 交叉校验 [P1]

- Expected result: 容量/可用量偏差可解释
- Required environment: E2

## FN-30: setTimes / mtime 精度（纳秒→毫秒截断） [P2]

- Expected result: 与 `stat` 一致，无回退/漂移
- Required environment: E2

## FN-31: FileContext rename 带 OVERWRITE 的原子可见性与 crash window [P1]

- Preconditions: Implementation or test basis: `CephFileSystem#rename(Path, Path, Options.Rename...)` 在 overwrite 分支委托 `super.rename`。
- Steps: 让独立 observer 连续读取 dst，并在基类 delete 与后续 rename 之间注入失败或终止
  客户端；分别记录顺序最终状态、transient ENOENT 与恢复后的 src/dst 状态。
- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: E2

**Group: Reliability and failure injection**

## REL-F01: active MDS 崩溃（有 standby） [P0]

- Expected result: 进行中 IO 阻塞后自动恢复；阻塞 ≤ 阈值；md5 一致
- Required environment: E2

## REL-F02: MDS 全灭 [P0]

- Expected result: 固化挂起或报错行为，并给出恢复后是否需重建 FileSystem 的操作口径
- Required environment: E2

## REL-F03: 单 OSD down（副本充足） [P0]

- Expected result: IO 无中断，无错误
- Required environment: E2

## REL-F04: PG inactive（多 OSD down） [P0]

- Expected result: 写阻塞可控；恢复后 md5 一致，无损坏
- Required environment: E2

## REL-F05: mon 部分/全部不可达 [P1]

- Expected result: mount 期与运行期超时行为、报错文本可诊断
- Required environment: E2

## REL-F06: 客户端被 evict/blocklist [P0]

- Expected result: 错误可诊断；已打开流的行为固化；恢复路径明确
- Required environment: E2

## REL-F07: 网络 100ms 延迟 + 1% 丢包 [P1]

- Expected result: 吞吐退化曲线记录；无错误放大，无损坏
- Required environment: E2

## REL-F08: 集群整体重启 [P1]

- Expected result: 客户端恢复行为固化；运行中的 MR 作业结局记录
- Required environment: E2

## REL-F09: 池写满（ENOSPC） [P1]

- Expected result: 异常可诊断；不产生半截损坏文件；记录安全恢复与清理口径
- Required environment: E2

## REL-F10: 目录配额触发 [P1]

- Expected result: 同上
- Required environment: E2

## REL-F11: caps 矩阵：r / rw(无 p) / 路径受限 / root_squash [P0]

- Expected result: 每格记录可用与不可用操作及错误文本
- Required environment: E2

## REL-F12: 文件系统置只读 [P1]

- Expected result: 写失败语义可诊断
- Required environment: E2

## REL-F13: 写入中 kill -9 客户端 [P0]

- Expected result: 同 FN-13/14
- Required environment: E2

## REL-F14: 双客户端并发写同一文件 [P1]

- Expected result: 语义结论写入"已知限制"
- Required environment: E2

## REL-F15: MDS cache 压力（5 万打开文件） [P1]

- Expected result: 客户端内存与 MDS 健康可控，无 OOM
- Required environment: E2

**Group: Performance and capacity**

## PERF-01: 单流顺序写吞吐（1GB/10GB） [P0]

- Expected result: ≥ 内核 mount 70%
- Required environment: E2

## PERF-02: 单流顺序读吞吐 [P0]

- Expected result: ≥ 内核 mount 70%
- Required environment: E2

## PERF-03: 8/16/64 并发聚合读写 [P0]

- Expected result: 8 并发 ≥ 85%；16/64 记录扩展性曲线
- Required environment: E2

## PERF-04: 元数据 OPS：create/stat/list/rename/delete [P0]

- Expected result: ≥ 内核 mount 60%；p99 < 100 ms
- Required environment: E2

## PERF-05: 随机 pread（4KB/64KB/1MB，QD 1/16） [P1]

- Expected result: 延迟分位记录，与内核 mount 对比
- Required environment: E2

## PERF-06: ceph.client.buffer.size ∈ {1,4,16} MB 敏感度 [P1]

- Expected result: 给出推荐默认值（含结论依据）
- Required environment: E2

## PERF-07: ceph.object.size ∈ {4,64,256} MB 敏感度 [P1]

- Expected result: 同上
- Required environment: E2

## PERF-08: ceph.localize.reads on/off [P1]

- Expected result: 量化本地读收益
- Required environment: E2

## PERF-09: 10 万条目目录 listStatus 耗时 [P1]

- Expected result: 与内核 `ls -l` 对比；记录是否需要优化或限制超大目录扫描
- Required environment: E2

## PERF-10: 小文件高频 hflush（4KB × 1 万次） [P1]

- Expected result: 延迟分布；判断 HBase 类工作负载可行性（ECO-7）
- Required environment: E2

## PERF-11: getFileBlockLocations 大文件 split 计算耗时 [P1]

- Expected result: 每次开关 fd 的开销量化
- Required environment: E2

## PERF-12: TestDFSIO write/read（-nrFiles 16 -fileSize 1GB） [P1]

- Expected result: 记录基线；与 HDFS 同规模对照
- Required environment: E2

## PERF-13: TeraSort 100GB [P1]

- Expected result: 完成时间与失败率；本地性命中率
- Required environment: E2

## PERF-14: getContentSummary 10 万文件树 [P2]

- Expected result: 耗时基线
- Required environment: E2

## PERF-15: 与上一版本基线的回归比较 [P0]

- Expected result: 无 > 10% 退化
- Required environment: E2

**Group: Soak and resources**

## SOAK-01: 72h 混合负载（读写 7:3 + 元数据 + 每 6h 故障注入） [P0]

- Expected result: 0 失败；每小时 md5 抽样一致
- Required environment: E2

## SOAK-02: JVM heap / native RSS 趋势 [P0]

- Expected result: 增长 < 5%/24h（NMT + pmap 佐证）
- Required environment: E2

## SOAK-03: fd 数量趋势 [P0]

- Expected result: 稳态；负载结束后归零
- Required environment: E2

## SOAK-04: 线程数与 CephFS 会话数趋势 [P1]

- Expected result: 稳态不增（重点：`CephFs` 无 close 钩子的 mount 累积）
- Required environment: E2

## SOAK-05: 长跑 NodeManager 模拟（FileContext 路径 24h） [P1]

- Expected result: mount/会话不累积或给出运维口径
- Required environment: E2

**Group: Compatibility**

## COMPAT-01: Hadoop 3.3.6 + JDK 11 + Ceph 16.2.14（基线） [P0]

- Expected result: 全绿
- Required environment: Isolated compatibility matrix environment

## COMPAT-02: Hadoop 3.3.9 [P1]

- Expected result: L2 契约全绿
- Required environment: Isolated compatibility matrix environment

## COMPAT-03: Hadoop 3.4.x [P2]

- Expected result: 结论化（差异/不支持写入文档）
- Required environment: Isolated compatibility matrix environment

## COMPAT-04: JDK 8 运行（字节码目标已是 8） [P1]

- Expected result: L2 契约全绿
- Required environment: Isolated compatibility matrix environment

## COMPAT-05: JDK 17 运行 [P2]

- Expected result: 结论化（Hadoop 3.3 已知限制）
- Required environment: Isolated compatibility matrix environment

## COMPAT-06: Ceph 服务端 16.2 最新小版本 [P0]

- Expected result: 全绿
- Required environment: Isolated compatibility matrix environment

## COMPAT-07: Ceph 17.2 Quincy 服务端 + 16.2 客户端 [P1]

- Expected result: 结论化
- Required environment: Isolated compatibility matrix environment

## COMPAT-08: Ceph 18.2 Reef 服务端 [P2]

- Expected result: 结论化
- Required environment: Isolated compatibility matrix environment

## COMPAT-09: libcephfs.jar 与 libcephfs_jni.so 版本错配 [P0]

- Expected result: 失败必须早且可诊断（不得运行到中途才崩）
- Required environment: Isolated compatibility matrix environment

## COMPAT-10: Ubuntu 22.04 / Rocky 8（glibc 差异） [P1]

- Expected result: 加载与全链路正常
- Required environment: Isolated compatibility matrix environment

## COMPAT-11: 发行版自带 libcephfs2 的 jni 库替换本项目 so [P1]

- Expected result: 可用（DEPLOY.md §1 声称的路径必须成立）
- Required environment: Isolated compatibility matrix environment

## COMPAT-12: 32 位/异构架构（arm64） [P2]

- Expected result: 结论化（不支持则明确声明）
- Required environment: Isolated compatibility matrix environment

**Group: Security**

## SEC-01: Hadoop 用户身份是否下传 MDS（多 UGI 对照） [P0]

- Expected result: 结论化：不下传 → DEPLOY.md 新增"安全模型与适用边界"章节
- Required environment: E2

## SEC-02: 最小 caps 矩阵（含 mds rwp 必要性复核） [P0]

- Expected result: 每格可用操作表
- Required environment: E2

## SEC-03: 路径受限 caps（ceph fs authorize a client.x /tenantA rwp）+ ceph.root.dir [P1]

- Expected result: 越界访问必失败
- Required environment: E2

## SEC-04: root_squash caps [P1]

- Expected result: 行为与错误文本固化
- Required environment: E2

## SEC-05: 日志/异常栈不得含 cephx key [P0]

- Expected result: 自动 grep 断言全套测试日志，0 命中
- Required environment: E2

## SEC-06: keyring 文件权限异常（644/不可读/不存在） [P1]

- Expected result: 报错可诊断，不打印密钥内容
- Required environment: E2

## SEC-07: hadoop fs -chown user:group（非数字） [P1]

- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: E2

## SEC-08: -chmod 全权限位（含 setuid/setgid/sticky） [P1]

- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: E2

## SEC-09: 依赖 CVE 扫描 [P1]

- Expected result: 无 CVSS ≥ 7.0 未豁免
- Required environment: E2

## SEC-10: ceph.conf.options 传入敏感项（如 key=） [P2]

- Expected result: 不落日志
- Required environment: E2

**Group: Deployment and operations**

## OPS-01: 用真实 Hadoop 3.3.6 发行版按 DEPLOY.md 全流程安装验证 [P0]

- Expected result: `hadoop fs -ls ceph:///` 起全链路通过（补上 T07 遗留 4）
- Required environment: E2 and E3

## OPS-02: native 库三种配置方式（java.library.path / LD_LIBRARY_PATH / 发行版 native 目录） [P0]

- Expected result: 三种均实测有效
- Required environment: E2 and E3

## OPS-03: 1.0.0 → 1.1.0 升级与回滚 [P1]

- Expected result: 双向可行；无残留冲突
- Required environment: E2 and E3

## OPS-04: 集群内滚动更新连接器 jar（部分节点新版） [P1]

- Expected result: 混版可用或明确禁止
- Required environment: E2 and E3

## OPS-05: DEPLOY.md 排查表 6 类症状在 E2 上复现 [P1]

- Expected result: 症状文本与生产构建一致（调试构建专属项须标注）
- Required environment: E2 and E3

## OPS-06: 错误可诊断性抽查：10 个典型误配 [P1]

- Expected result: 均能从日志定位根因
- Required environment: E2 and E3

## OPS-07: 监控采集点清单（fd/会话/吞吐/错误率） [P1]

- Expected result: 给出 fd、会话、吞吐和错误率的可采集方案
- Required environment: E2 and E3

## OPS-08: 制品可重现性 + sha256 + so 纯净度 [P1]

- Expected result: L0 阈值
- Required environment: E2 and E3

## OPS-09: 容量规划建议（客户端内存 vs 并发流数） [P2]

- Expected result: 给出经验公式与实测依据
- Required environment: E2 and E3

## OPS-10: 干净 shell 终验脚本在 E2/E3 重跑 [P1]

- Expected result: 退出码 0
- Required environment: E2 and E3

**Group: Unsupported and negative paths**

## NEG-01: setReplication [P1]

- Expected result: Defined by [SEMANTICS-MATRIX.md](SEMANTICS-MATRIX.md) rows citing this ID.
- Required environment: E2

## NEG-02: concat / truncate [P1]

- Expected result: `UnsupportedOperationException` 或明确 IOException，消息可读
- Required environment: E2

## NEG-03: XAttr / ACL API 调用 [P1]

- Expected result: 明确不支持异常，非 NPE/静默成功
- Required environment: E2

## NEG-04: 符号链接创建/解析 [P1]

- Expected result: 明确不支持；`supportsSymlinks()` 声明一致
- Required environment: E2

## NEG-05: 快照 API [P1]

- Expected result: 明确不支持
- Required environment: E2

## NEG-06: Kerberos 环境下使用（hadoop.security.authentication=kerberos） [P1]

- Expected result: 行为固化：不支持委托 Token，错误可诊断
- Required environment: E2

## NEG-07: fs.AbstractFileSystem.ceph.impl 未配置 [P1]

- Expected result: `UnsupportedFileSystemException`（已有 ITest，纳入回归）
- Required environment: E2

## NEG-08: CephFS multi-fs 环境指定非默认 fs [P2]

- Expected result: 明确不支持并给出规避方式（`ceph.conf.options`）
- Required environment: E2
