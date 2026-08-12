# hadoop-cephfs 生产级用例清单

> 配套 [TEST-PLAN.md](TEST-PLAN.md)。本清单为**待建用例**（现有 122 单测 + 116 契约 +
> 24 集成不重复列出，它们是回归底座）。
> 优先级：**P0** 阻断发布 / **P1** 发布前必做 / **P2** 可延期但须登记。
> 环境：E1 vstart / E2 生产仿真 Ceph / E3 Hadoop 生态 / E4 兼容矩阵机 / — 无需集群。
> 「实现依据」列指向被测的具体代码事实，避免用例写成泛泛而谈。

---

## 1. UT — 单元测试补强（mock `CephFsProto`，无集群）

| ID | P | 用例 | 判定 | 实现依据 |
|---|---|---|---|---|
| UT-01 | P1 | `mapCephException` 全矩阵：`CephFileAlreadyExistsException`/`CephNotDirectoryException`/裸 IOException/`CephNotMountedException` | 异常类型与 cause 链逐一符合规范 | `CephFileSystem#mapCephException` |
| UT-02 | P1 | `lstatResolved` 查询/变更两语境 × 祖先为文件/目录权限不足/纯 FNF | 变更→`ParentNotDirectoryException`，查询→FNF，权限不足原样抛 | `lstatResolved` |
| UT-03 | P1 | `normalizeLayoutSize` 边界：0、1、64K-1、64K、64K+1、`Long.MAX_VALUE`、负数 | 恒为 64KB 整数倍且 ≤ `CEPH_MAX_LAYOUT_SIZE`，无溢出 | `normalizeLayoutSize` |
| UT-04 | P1 | `getStatus` 的 `frsize=0`/`bsize=0`/超大 blocks（PB 级）溢出 | 无负值、无溢出，`used = capacity - remaining` | `getStatus` |
| UT-05 | P1 | `setOwner`：数字 uid+gid / 仅 uid / 非数字 / 空串 / null | 非数字仅 warn 且 mask 不置位；mask=0 时不调 `setattr` | `setOwner` |
| UT-06 | P1 | `toFileStatus` 权限位：setuid/setgid/sticky/0777 | `mode & 01777` 口径固化（setuid/setgid 丢弃）并附注释说明 | `toFileStatus` |
| UT-07 | P1 | `pathString`：null、空、相对路径、含空格/UTF-8/`%`/`#`、`ceph://host/x` | 唯一转换入口行为固化 | `CephTalker#pathString` |
| UT-08 | P1 | `getFileBlockLocations` 降级：`getFileExtent` 抛异常 / extent 长度 ≤0 / OSD 列表空 / `getOsdAddress` 返回 null | 降级为 localhost 并按 blockSize 步进，块覆盖无空洞无重叠，fd 必被 close | `getFileBlockLocations` |
| UT-09 | P1 | `getFileBlockLocations` 入参：`file=null`、start<0、len<0、start≥len(file)、`len=Long.MAX_VALUE` | 分别 null / IAE / 空数组 / 末端钳制无溢出 | 同上 |
| UT-10 | P1 | `CephOutputStream.writeFully` 部分写循环：底层每次只写 1 字节 / 返回 0 / 返回 > 请求数 | 循环直到写完；0 或超量→IOException | `writeFully` |
| UT-11 | P1 | `CephOutputStream.close` 失败路径：flush 抛异常 + close 也抛 | 首异常抛出，第二个进 suppressed，fd 不泄漏 | `close` |
| UT-12 | P1 | 流关闭后再 write/read/seek/getPos/hsync | 一律 IOException("Stream is closed")，close 幂等 | `ensureOpen` |
| UT-13 | P1 | `CephInputStream` 缓冲边界：seek 到缓冲头/尾/尾+1、跨缓冲读、`read(pos,...)` 越界 | 数据正确、缓冲失效逻辑正确、越界 EOF 语义一致 | `fillBuffer`/`seek` |
| UT-14 | P2 | `Statistics` 计数：写失败后计数是否虚增；`write(int)` 单字节路径 | 计数口径书面固化（当前先计数后落盘） | `incrementBytesWritten` |
| UT-15 | P1 | `initialize` 配置解析：`ceph.conf.options` 非法项（无 `=`、空段、多 `=`） | 非法抛 IAE 且消息含原文；空段跳过 | `CephTalker#initialize` |
| UT-16 | P1 | URI authority 覆盖 mon：有 host 有 port / 有 host 无 port / 无 authority / **含逗号的多 mon 写法** | 前三种符合设计；多 mon 逗号写法 `getHost()` 为 null → 静默回退 ceph.conf，须固化并写入文档 | 同上 |
| UT-17 | P2 | `firstDataPool`：空串、逗号开头、全空白 | 返回 null 或首个非空 | `firstDataPool` |
| UT-18 | P2 | `streamBufferSize` 非法值（0、负、超大） | 回退默认值 | `streamBufferSize` |
| UT-19 | P1 | `close()` 幂等 + close 后所有 FS 操作 | 二次 close 无副作用；后续操作抛 `CephNotMountedException` | `CephFileSystem#close` |
| UT-20 | P2 | `initialize` 二次调用 | `IllegalStateException("already initialized")` | `CephTalker#initialize` |

---

## 2. CT — 契约测试扩展（Hadoop 官方基类，E1+E2 双跑）

| ID | P | 用例 | 判定 |
|---|---|---|---|
| CT-01 | P0 | 接入 `FSMainOperationsBaseTest` → `ITestCephFSMainOperations` | 全绿；排除项有书面理由 |
| CT-02 | P0 | 接入 `FileContextMainOperationsBaseTest` → `ITestCephFileContextMainOperations` | 同上（补强 `CephFs`/YARN 路径） |
| CT-03 | P1 | `FileContextCreateMkdirBaseTest` | 全绿 |
| CT-04 | P1 | `FileContextPermissionBase` | 全绿或差异书面化（uid/gid 数字化影响） |
| CT-05 | P1 | `FileContextURIBase` | 全绿 |
| CT-06 | P0 | 现有 9 套契约在 **E2 三副本非调试构建**上重跑 | 与 E1 结果一致；`lockdep` workaround 不需要 |
| CT-07 | P1 | 契约套件在 `ceph.root.dir=/subdir` 子目录挂载下重跑 | 结果一致（根语义相对子目录成立） |
| CT-08 | P1 | 契约套件在**非 admin、最小 caps** 用户下重跑 | 除 root 目录类外全绿 |
| CT-09 | P2 | 契约套件连续 20 轮稳定性 | flaky < 1%，不稳定用例建单 |

---

## 3. FN — 功能深化（E2）

### 3.1 规模与边界

| ID | P | 用例 | 判定 |
|---|---|---|---|
| FN-01 | P0 | 单文件 1GB / 10GB 写入读回 | md5 一致；耗时记录 |
| FN-02 | P0 | 单文件 > 2^31 字节的 seek/pread（偏移超 int） | 无溢出，随机 100 点 pread 全部正确 |
| FN-03 | P1 | 单次 `write(byte[])` 传入 > 缓冲区的大数组（16MB/64MB） | 走直写分支正确，无越界 |
| FN-04 | P1 | 目录 1 万 / 10 万条目 `listStatus`/`-ls` | 结果完整、无 OOM；耗时进性能基线（N+1 RPC 观察点） |
| FN-05 | P1 | 100 层深目录的 mkdirs/递归 delete/rename | 正确；无栈溢出（递归实现 `deleteSubtree`） |
| FN-06 | P1 | 特殊文件名：空格、UTF-8 中文、`%20`、`#`、`+`、`:`、单引号 | 创建/列举/读写/删除全链路一致（含 FsShell 层） |
| FN-07 | P1 | 文件名 255 字节上限与超限 | 上限成功；超限失败且异常可诊断 |
| FN-08 | P1 | 路径总长接近 4096 | 行为固化 |
| FN-09 | P2 | 空文件、稀疏写（seek 后写，中间空洞） | 长度与内容符合 POSIX，`getFileBlockLocations` 不崩 |
| FN-10 | P1 | 5 万文件并发打开（不关闭）后统一关闭 | 无 fd 泄漏（`openFileDescriptorCount` 归零），无 MDS 告警残留 |

### 3.2 语义与可见性

| ID | P | 用例 | 判定 |
|---|---|---|---|
| FN-11 | P0 | **写方 append + 读方已打开流**：读方能否看到新数据 | 固化结论（当前为 open 时刻长度快照，预期读不到），写入"已知限制"并评估附录 A-2 |
| FN-12 | P0 | 写方 `hflush`/`hsync` 后，**新开**的 reader 能否读到 | 必须读到全部已 sync 数据 |
| FN-13 | P0 | `hsync` 后 `kill -9` 客户端 | 已 sync 数据 100% 存活，文件可正常再 append/覆盖 |
| FN-14 | P1 | 未 flush 即 `kill -9` | 允许丢数据，但不得出现长度与内容不一致的损坏文件 |
| FN-15 | P1 | 并发 rename 竞态：两线程同时 rename 同一 src | 恰一个成功，另一个返回 false 或 FNF，无中间态 |
| FN-16 | P1 | 递归 delete 期间并发创建子文件 | 不抛非预期异常；结果状态可解释（代码已有竞态分支） |
| FN-17 | P1 | 并发 create 同一路径（overwrite=false） | 恰一个成功，其余 `FileAlreadyExistsException` |
| FN-18 | P1 | rename 目录到自身子树 / 到已存在目录 / 到已存在文件 | 分别 false / 移入其下 / false（与 HDFS 一致） |
| FN-19 | P1 | 32 线程共享同一 `CephFileSystem` 混合读写 30 min | 无死锁、无数据错乱、fd 归零（现有 4 线程冒烟的加强版） |
| FN-20 | P2 | `FileSystem.CACHE` 行为：多次 `FileSystem.get` 复用同一 mount；`fs.ceph.impl.disable.cache=true` 时每次新 mount | 会话数变化符合预期（`ceph tell mds session ls`） |
| FN-21 | P1 | 多 UGI（`doAs` 三个用户）访问 | 每 UGI 一个 mount；CephFS 侧身份均为同一 cephx id（SEC-1 的功能面证据） |

### 3.3 集成周边

| ID | P | 用例 | 判定 |
|---|---|---|---|
| FN-22 | P1 | `ceph.root.dir=/tenantA` 子目录挂载全功能 | 根语义、`getStatus`、BlockLocation 均正确；无法越界访问上级 |
| FN-23 | P1 | Trash：`fs.trash.interval>0` 下 `-rm` | 移入 `/user/<user>/.Trash`；家目录不存在时行为可接受 |
| FN-24 | P1 | `getContentSummary` 深/宽目录树 | 结果正确；耗时进性能基线 |
| FN-25 | P1 | `getFileBlockLocations` 在**多 OSD 多主机**下 | hosts 为真实 OSD 主机 IP（与 `ceph osd dump` 可比），块切分与 object 边界一致 |
| FN-26 | P1 | `Statistics` 计数 vs 实际字节数（读/写各 1GB） | 误差为 0 或书面解释 |
| FN-27 | P1 | 13 个配置键逐个改值 → 生效性验证 + 缺省回退 | 每键至少 1 条断言（含 `ceph.localize.reads`、`ceph.replication`、`ceph.data.pools`） |
| FN-28 | P2 | `ceph.data.pools` 指定非默认 data pool | 文件确实落在指定 pool（`ceph osd map` 校验） |
| FN-29 | P1 | `-df` / `getStatus` 与 `ceph df` 交叉校验 | 容量/可用量偏差可解释 |
| FN-30 | P2 | `setTimes` / mtime 精度（纳秒→毫秒截断） | 与 `stat` 一致，无回退/漂移 |

---

## 4. REL — 故障注入（E2，对应 TEST-PLAN §6）

| ID | P | 场景 | 判定（**每条都须产出"运维处置口径"并回写 DEPLOY.md**） |
|---|---|---|---|
| REL-F01 | P0 | active MDS 崩溃（有 standby） | 进行中 IO 阻塞后自动恢复；阻塞 ≤ 阈值；md5 一致 |
| REL-F02 | P0 | MDS 全灭 | 行为固化（挂起 or 报错）；恢复后是否需重建 FileSystem（→附录 A-1） |
| REL-F03 | P0 | 单 OSD down（副本充足） | IO 无中断，无错误 |
| REL-F04 | P0 | PG inactive（多 OSD down） | 写阻塞可控；恢复后 md5 一致，无损坏 |
| REL-F05 | P1 | mon 部分/全部不可达 | mount 期与运行期超时行为、报错文本可诊断 |
| REL-F06 | P0 | **客户端被 evict/blocklist** | 错误可诊断；已打开流的行为固化；恢复路径明确（→附录 A-1） |
| REL-F07 | P1 | 网络 100ms 延迟 + 1% 丢包 | 吞吐退化曲线记录；无错误放大，无损坏 |
| REL-F08 | P1 | 集群整体重启 | 客户端恢复行为固化；运行中的 MR 作业结局记录 |
| REL-F09 | P1 | 池写满（ENOSPC） | 异常可诊断；不产生半截损坏文件（→附录 A-6） |
| REL-F10 | P1 | 目录配额触发 | 同上 |
| REL-F11 | P0 | caps 矩阵：`r` / `rw`(无 p) / 路径受限 / root_squash | 每格记录可用与不可用操作及错误文本 |
| REL-F12 | P1 | 文件系统置只读 | 写失败语义可诊断 |
| REL-F13 | P0 | 写入中 kill -9 客户端 | 同 FN-13/14 |
| REL-F14 | P1 | 双客户端并发写同一文件 | 语义结论写入"已知限制" |
| REL-F15 | P1 | MDS cache 压力（5 万打开文件） | 客户端内存与 MDS 健康可控，无 OOM |

---

## 5. PERF — 性能与容量（E2，对照内核 mount）

| ID | P | 用例 | 判定 |
|---|---|---|---|
| PERF-01 | P0 | 单流顺序写吞吐（1GB/10GB） | ≥ 内核 mount 70% |
| PERF-02 | P0 | 单流顺序读吞吐 | ≥ 内核 mount 70% |
| PERF-03 | P0 | 8/16/64 并发聚合读写 | 8 并发 ≥ 85%；16/64 记录扩展性曲线 |
| PERF-04 | P0 | 元数据 OPS：create/stat/list/rename/delete | ≥ 内核 mount 60%；p99 < 100 ms |
| PERF-05 | P1 | 随机 pread（4KB/64KB/1MB，QD 1/16） | 延迟分位记录，与内核 mount 对比 |
| PERF-06 | P1 | `ceph.client.buffer.size` ∈ {1,4,16} MB 敏感度 | 给出推荐默认值（含结论依据） |
| PERF-07 | P1 | `ceph.object.size` ∈ {4,64,256} MB 敏感度 | 同上 |
| PERF-08 | P1 | `ceph.localize.reads` on/off | 量化本地读收益 |
| PERF-09 | P1 | 10 万条目目录 `listStatus` 耗时 | 与内核 `ls -l` 对比；决定是否触发附录 A-3 |
| PERF-10 | P1 | 小文件高频 `hflush`（4KB × 1 万次） | 延迟分布；判断 HBase 类工作负载可行性（ECO-7） |
| PERF-11 | P1 | `getFileBlockLocations` 大文件 split 计算耗时 | 每次开关 fd 的开销量化 |
| PERF-12 | P1 | TestDFSIO write/read（`-nrFiles 16 -fileSize 1GB`） | 记录基线；与 HDFS 同规模对照 |
| PERF-13 | P1 | TeraSort 100GB | 完成时间与失败率；本地性命中率 |
| PERF-14 | P2 | `getContentSummary` 10 万文件树 | 耗时基线 |
| PERF-15 | P0 | 与上一版本基线的回归比较 | 无 > 10% 退化 |

---

## 6. SOAK — 长稳（E2）

| ID | P | 用例 | 判定 |
|---|---|---|---|
| SOAK-01 | P0 | 72h 混合负载（读写 7:3 + 元数据 + 每 6h 故障注入） | 0 失败；每小时 md5 抽样一致 |
| SOAK-02 | P0 | JVM heap / **native RSS** 趋势 | 增长 < 5%/24h（NMT + pmap 佐证） |
| SOAK-03 | P0 | fd 数量趋势 | 稳态；负载结束后归零 |
| SOAK-04 | P1 | 线程数与 CephFS 会话数趋势 | 稳态不增（重点：`CephFs` 无 close 钩子的 mount 累积） |
| SOAK-05 | P1 | 长跑 NodeManager 模拟（FileContext 路径 24h） | mount/会话不累积或给出运维口径 |

---

## 7. ECO — 生态集成（E3）

| ID | P | 用例 | 判定 |
|---|---|---|---|
| ECO-01 | P0 | MR wordcount，输入输出均 `ceph://` | 成功；结果正确 |
| ECO-02 | P0 | MR 作业提交时的 split 本地性 | BlockLocation 生效，本地性命中率记录 |
| ECO-03 | P0 | `fs.defaultFS=ceph://` 全局替换 | staging/history/家目录全链路可用 |
| ECO-04 | P0 | FileOutputCommitter v1/v2 提交（大量 rename） | 正确性 + 耗时（`_temporary` 目录 rename 密集） |
| ECO-05 | P0 | YARN 日志聚合（走 `CephFs`/FileContext） | 日志可聚合可查看；mount 生命周期正常 |
| ECO-06 | P0 | Spark 3.4 读写 Parquet/ORC | 成功；数据一致 |
| ECO-07 | P1 | Spark event log + checkpoint 在 `ceph://` | 成功；`hflush` 语义满足 |
| ECO-08 | P1 | Hive 外部表 + INSERT + 动态分区 | 成功；分区扫描耗时记录 |
| ECO-09 | P0 | DistCp HDFS→Ceph | 成功；逐文件校验一致 |
| ECO-10 | P0 | DistCp `-update` 增量 | **`getFileChecksum` 返回 null 的实际影响**结论化（→附录 A-5） |
| ECO-11 | P1 | Ceph→Ceph DistCp | 成功 |
| ECO-12 | P1 | 与 HDFS 共存（双 scheme 同集群） | 无 classpath / native 冲突 |
| ECO-13 | P2 | HBase 评估（WAL on ceph://） | 结论：支持/不支持，附数据 |

---

## 8. COMPAT — 兼容矩阵（E4）

| ID | P | 用例 | 判定 |
|---|---|---|---|
| COMPAT-01 | P0 | Hadoop 3.3.6 + JDK 11 + Ceph 16.2.14（基线） | 全绿 |
| COMPAT-02 | P1 | Hadoop 3.3.9 | L2 契约全绿 |
| COMPAT-03 | P2 | Hadoop 3.4.x | 结论化（差异/不支持写入文档） |
| COMPAT-04 | P1 | JDK 8 运行（字节码目标已是 8） | L2 契约全绿 |
| COMPAT-05 | P2 | JDK 17 运行 | 结论化（Hadoop 3.3 已知限制） |
| COMPAT-06 | P0 | Ceph 服务端 16.2 最新小版本 | 全绿 |
| COMPAT-07 | P1 | Ceph 17.2 Quincy 服务端 + 16.2 客户端 | 结论化 |
| COMPAT-08 | P2 | Ceph 18.2 Reef 服务端 | 结论化 |
| COMPAT-09 | P0 | **libcephfs.jar 与 libcephfs_jni.so 版本错配** | 失败必须早且可诊断（不得运行到中途才崩） |
| COMPAT-10 | P1 | Ubuntu 22.04 / Rocky 8（glibc 差异） | 加载与全链路正常 |
| COMPAT-11 | P1 | 发行版自带 libcephfs2 的 jni 库替换本项目 so | 可用（DEPLOY.md §1 声称的路径必须成立） |
| COMPAT-12 | P2 | 32 位/异构架构（arm64） | 结论化（不支持则明确声明） |

---

## 9. SEC — 安全与多租户（E2）

| ID | P | 用例 | 判定 |
|---|---|---|---|
| SEC-01 | P0 | Hadoop 用户身份是否下传 MDS（多 UGI 对照） | 结论化：不下传 → DEPLOY.md 新增"安全模型与适用边界"章节 |
| SEC-02 | P0 | 最小 caps 矩阵（含 `mds rwp` 必要性复核） | 每格可用操作表 |
| SEC-03 | P1 | 路径受限 caps（`ceph fs authorize a client.x /tenantA rwp`）+ `ceph.root.dir` | 越界访问必失败 |
| SEC-04 | P1 | root_squash caps | 行为与错误文本固化 |
| SEC-05 | P0 | 日志/异常栈**不得含 cephx key** | 自动 grep 断言全套测试日志，0 命中 |
| SEC-06 | P1 | keyring 文件权限异常（644/不可读/不存在） | 报错可诊断，不打印密钥内容 |
| SEC-07 | P1 | `hadoop fs -chown user:group`（非数字） | 静默 warn 行为固化并写入"已知限制"（SEC-6/A 相关） |
| SEC-08 | P1 | `-chmod` 全权限位（含 setuid/setgid/sticky） | `mode & 01777` 差异书面化 |
| SEC-09 | P1 | 依赖 CVE 扫描 | 无 CVSS ≥ 7.0 未豁免 |
| SEC-10 | P2 | `ceph.conf.options` 传入敏感项（如 `key=`） | 不落日志 |

---

## 10. OPS — 部署与运维验收（E2/E3）

| ID | P | 用例 | 判定 |
|---|---|---|---|
| OPS-01 | P0 | 用**真实 Hadoop 3.3.6 发行版**按 DEPLOY.md 全流程安装验证 | `hadoop fs -ls ceph:///` 起全链路通过（补上 T07 遗留 4） |
| OPS-02 | P0 | native 库三种配置方式（`java.library.path` / `LD_LIBRARY_PATH` / 发行版 native 目录） | 三种均实测有效 |
| OPS-03 | P1 | 1.0.0 → 1.1.0 升级与回滚 | 双向可行；无残留冲突 |
| OPS-04 | P1 | 集群内滚动更新连接器 jar（部分节点新版） | 混版可用或明确禁止 |
| OPS-05 | P1 | DEPLOY.md 排查表 6 类症状在 E2 上复现 | 症状文本与生产构建一致（调试构建专属项须标注） |
| OPS-06 | P1 | 错误可诊断性抽查：10 个典型误配 | 均能从日志定位根因 |
| OPS-07 | P1 | 监控采集点清单（fd/会话/吞吐/错误率） | 给出可采集方案（→附录 A-7） |
| OPS-08 | P1 | 制品可重现性 + sha256 + so 纯净度 | L0 阈值 |
| OPS-09 | P2 | 容量规划建议（客户端内存 vs 并发流数） | 给出经验公式与实测依据 |
| OPS-10 | P1 | 干净 shell 终验脚本在 E2/E3 重跑 | 退出码 0 |

---

## 11. NEG — 非目标的可诊断性（E2）

| ID | P | 用例 | 判定 |
|---|---|---|---|
| NEG-01 | P1 | `setReplication` | 返回 false（不抛异常），文档已声明 |
| NEG-02 | P1 | `concat` / `truncate` | `UnsupportedOperationException` 或明确 IOException，消息可读 |
| NEG-03 | P1 | XAttr / ACL API 调用 | 明确不支持异常，非 NPE/静默成功 |
| NEG-04 | P1 | 符号链接创建/解析 | 明确不支持；`supportsSymlinks()` 声明一致 |
| NEG-05 | P1 | 快照 API | 明确不支持 |
| NEG-06 | P1 | Kerberos 环境下使用（`hadoop.security.authentication=kerberos`） | 行为固化：不支持委托 Token，错误可诊断 |
| NEG-07 | P1 | `fs.AbstractFileSystem.ceph.impl` 未配置 | `UnsupportedFileSystemException`（已有 ITest，纳入回归） |
| NEG-08 | P2 | CephFS multi-fs 环境指定非默认 fs | 明确不支持并给出规避方式（`ceph.conf.options`） |

---

## 12. 用例总量与自动化目标

| 类别 | 待建数量 | 自动化要求 |
|---|---|---|
| UT | 20 | 100% 自动，进 PR 门禁 |
| CT | 9（新增约 150+ 官方断言） | 100% 自动，进合并门禁 |
| FN | 30 | 100% 自动，进夜间 |
| REL | 15 | ≥ 80% 自动（脚本化注入），其余半自动 |
| PERF | 15 | 100% 脚本化，结果 CSV 归档 |
| SOAK | 5 | 100% 自动 + 监控采集 |
| ECO | 13 | ≥ 70% 自动（Hive/Spark 用例可脚本驱动） |
| COMPAT | 12 | 矩阵脚本驱动 |
| SEC | 10 | ≥ 70% 自动 |
| OPS | 10 | ≥ 50% 自动（发行版安装类可脚本化） |
| NEG | 8 | 100% 自动 |
| **合计** | **147** | 整体自动化率目标 ≥ 80% |
