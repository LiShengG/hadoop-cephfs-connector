# hadoop-cephfs 生产级测试方案

> 版本：v1.0（2026-08-12）
> 适用对象：hadoop-cephfs 1.0.0 → **1.1.0（生产就绪）**
> 读者：测试负责人、开发 agent（T08–T12）、SRE/部署方
> 配套文档：用例清单 [TEST-CASES.md](TEST-CASES.md)；阶段任务书 `docs/tasks/T08–T12`

---

## 0. 一句话结论

v1.0.0 的质量证据**全部来自单机 vstart 调试构建集群**（1 mon/1 mds/1 osd、
`client.admin` 全权、无副本、无故障域、无 Hadoop 发行版）。功能语义已被 116 例
Hadoop 官方契约测试钉死，但**可靠性、性能、长稳、生态、兼容、安全六个维度的证据为零**。
本方案定义把这六个维度补齐所需的环境、用例、阈值、门禁与排期，产出物是可复现的
「生产就绪验收报告」，而非一次性手工验证。

---

## 1. 现状盘点

### 1.1 已有能力（可直接复用为回归底座）

| 资产 | 规模 | 门控方式 |
|---|---|---|
| 单元测试（mock `CephFsProto`） | 122 例 | 无需集群，`mvn test` |
| Hadoop FileSystem 契约测试 | 116 例（9 套件，1 例书面 override） | `CEPH_CONTRACT_TEST=1 mvn verify` |
| 集成测试 `ITest*` | 24 例（含并发冒烟、BlockLocation、FileContext） | 同上，合计 140 例 |
| CLI 端到端 `scripts/e2e-cli-test.sh` | 21 项断言 + 200MB md5 往返 | 需 vstart 集群 |
| 部署终验 `scripts/t07-final-verify.sh` | 干净 shell 按 DEPLOY.md 全流程 | 需 vstart 集群 |
| 排查表 | 6 类故障症状，均真实复现 | DEPLOY.md §6 |

### 1.2 生产化缺口（本方案要解决的问题，逐条给出依据）

| # | 缺口 | 依据 | 覆盖章节 |
|---|---|---|---|
| G1 | **环境失真**：单 OSD、单 MDS、无 standby、无副本、调试构建（lockdep 误报需 workaround）、`client.admin` 全权 | ENV.md §1、PROGRESS T06 环境变更 | §4 E2 |
| G2 | **无故障与恢复证据**：连接器全链路**无重试/无重连**——`CephTalker` 直接透传 libcephfs 调用，`shutdown()` 后一律 `CephNotMountedException`；客户端被 evict/blocklist 后能否恢复未知 | `CephTalker.java` 全文无 retry 逻辑 | §6 |
| G3 | **无性能与容量基线**：吞吐/元数据 OPS/延迟分位/资源占用全无数据，无法判断回归 | 无 perf 记录 | §7 |
| G4 | **无长稳证据**：fd、JVM heap、**native RSS**（libcephfs objecter/缓存在 JVM 堆外）、线程、mount 数的长期趋势未测 | 仅 300s 并发冒烟 | §8 |
| G5 | **生态集成为零（最高风险）**：MR/YARN/Hive/Spark/HBase/DistCp 全未验证；`hadoop fs` 亦以 `FsShell` 等效方式跑，从未用 Hadoop 发行版。且连接器在**属主/组字符串、`access()`、`setOwner`、写入中文件可见性、`FileContext` 语义、委托 Token** 六处存在生态组件重度依赖、冒烟测试打不到的偏差 | PROGRESS T06 偏差 2 / T07 遗留 4；`ownerName`/`groupName`/`setOwner`/`CephInputStream`/`CephFs` | §11 + [TEST-CASES-ECO.md](TEST-CASES-ECO.md) |
| G6 | **兼容矩阵未定义**：仅 Hadoop 3.3.6 + JDK 11 + Ceph 16.2.14 单点；libcephfs.jar 与 `libcephfs_jni.so` 的版本配对约束未做负向验证 | pom + ENV.md | §9 |
| G7 | **多租户/安全模型未澄清**：连接器所有请求使用**同一 cephx id**，Hadoop UGI 身份不下传到 MDS；`setOwner` 只接受数字 uid/gid（非数字静默 warn 跳过）；受限 caps/子目录挂载/root_squash 未测 | `CephFileSystem#setOwner`、`CephTalker#initialize` | §10 |
| G8 | **读可见性语义未定稿**：`CephInputStream` 在 open 时刻快照 `fileLength`，其后追加的数据对已打开的 reader 不可见（`read` 越过快照即返回 -1） | `CephInputStream` 构造器 + `read()` | §5 L3 |
| G9 | **规模化元数据路径未测**：`listStatus` 为 `listdir` + 逐项 `lstat`（N+1 次 RPC）；`getContentSummary` 走基类 Java 侧递归 | `CephFileSystem#listStatus`、PROGRESS T07 遗留 5 | §7.4 |
| G10 | **工程门禁缺失**：无 CI、无覆盖率统计、无静态检查、无依赖 CVE 扫描、无 flaky 管控、制品未做可重现性与签名（发布 so 还带本机 RUNPATH） | 仓库无 `.github/`；PROGRESS T07 遗留 2/3 | §5 L0、§12 |

---

## 2. 测试目标与量化验收阈值

发布 v1.1.0（生产就绪）须同时满足下表。**任一项不达标即阻断发布**，除非在发布评审
上以书面豁免（含理由、影响面、缓解措施）通过。

| 维度 | 指标 | 阈值 |
|---|---|---|
| 功能正确性 | 契约 + 集成 + 扩展功能用例通过率 | 100%（排除项 ≤ 5 类且有书面理由） |
| 缺陷 | S1/S2 缺陷 | 0 未关闭 |
| 数据完整性 | 全部读写用例（含故障注入期间）md5/校验和一致 | 0 例静默损坏 |
| 可靠性 | 故障注入 15 类场景 | 无损坏、无死锁；行为符合《预期行为表》且已写入 DEPLOY.md 排查表 |
| 恢复 | MDS failover 期间阻塞时长 | ≤ `mds_reconnect_timeout` + 30s，恢复后操作成功 |
| 性能（单流顺序） | 读/写吞吐 vs 同机内核态 CephFS mount（fio 基线） | ≥ 70% |
| 性能（8 并发聚合） | 读/写聚合吞吐 vs 内核 mount | ≥ 85% |
| 性能（元数据） | create/stat/list/delete OPS vs 内核 mount | ≥ 60% |
| 性能（延迟） | 元数据操作 p99（正常负载、3 副本集群） | < 100 ms |
| 性能回归 | 相对上一发布基线 | 下降 > 10% 阻断 |
| 长稳 | 72h 混合负载 | 失败 0；native RSS 增长 < 5%/24h；fd 归零；线程数稳定；mount 数不增 |
| 兼容 | 兼容矩阵单元格 | 全绿或书面豁免 |
| 生态 | 组件场景用例（TEST-CASES-ECO.md，88 条） | P0 全通过；P1 通过或书面豁免；**《组件支持矩阵》发布**；不支持项必须"明确失败 + 可读消息"，不得静默产生错误数据 |
| 覆盖率 | `org.apache.hadoop.fs.ceph` 行/分支 | ≥ 80% / ≥ 70%；`CephFileSystem` 行 ≥ 85% |
| 门禁效率 | PR 门禁流水线时长 | ≤ 30 min |
| 稳定性（测试自身） | flaky 率（连续 20 轮） | < 1%，且每个 flaky 必须建单，禁止盲目 rerun |

---

## 3. 范围

### 3.1 在范围内

- `CephFileSystem`（FileSystem API）与 `CephFs`（AbstractFileSystem/FileContext API）全部对外行为；
- 流层读写语义、可见性、`hflush`/`hsync` 持久化保证；
- 数据局部性（BlockLocation）在**多 OSD、多主机**下的正确性；
- 部署形态：Hadoop 发行版 classpath 安装、native 库加载、cephx 授权模型；
- 故障与降级、性能与容量、长稳、兼容、安全与多租户、生态集成；
- 工程质量门禁（静态检查、覆盖率、依赖 CVE、制品可重现性）。

### 3.2 不在范围内（架构文档 §1.2 既定非目标）

Kerberos / 委托 Token；XAttr / ACL / 快照；CephFS multi-fs 选择；多活 MDS 调优；
Hadoop 2.x 兼容；`concat` / `truncate` 扩展 API。

> **要求**：每个非目标必须有**负向用例**证明其"以可诊断的方式失败"（明确异常类型 +
> 可读消息），而不是静默错误行为。见 TEST-CASES.md `NEG-*`。

---

## 4. 测试环境矩阵

| 环境 | 用途 | 规格 | 供给方式 |
|---|---|---|---|
| **E1 开发机 vstart** | L0/L1/L2 快速回归、开发自测 | 现状（1 mon/1 mgr/1 mds/1 osd，调试构建） | `scripts/cluster-up.sh`（已有） |
| **E2 生产仿真 Ceph 集群** | L3/L4/L5/L7 主战场 | ≥ 3 节点：3 mon、2 mgr、3 mds（1 active + 2 standby）、≥ 6 osd（每节点 2）、replicated size=3 min_size=2、public/cluster 网分离、**非调试构建**（无 lockdep）、独立 cephx 用户 | `scripts/env/ceph-cluster-up.sh`（cephadm 或容器编排，T08 交付） |
| **E3 Hadoop 生态集群** | L6 生态集成 | Hadoop 3.3.6 **发行版**（NN/RM/3×NM）+ Hive 4.0.1 + Spark 3.4；`fs.defaultFS=ceph://` 与 HDFS 共存两种配置 | `scripts/env/hadoop-cluster-up.sh`（T08 交付） |
| **E4 兼容矩阵机** | L6 COMPAT | 可切换 JDK/Hadoop/Ceph 版本的容器镜像集 | `scripts/env/compat-matrix.sh`（T11 交付） |

**环境铁律**

1. E2/E3 的所有数据**可丢弃**，但根目录清空类用例（如契约 RootDirectory）**仅允许在专用
   测试文件系统**上运行，须由脚本校验 `ceph fs ls` 名称白名单后方可执行；
2. 每轮基准/长稳测试前记录环境指纹（`ceph versions`、`ceph osd tree`、`ceph config dump`、
   内核版本、JDK、Hadoop 版本、连接器 commit），随报告归档——**无指纹的性能数据一律作废**；
3. E2 必须验证 `lockdep` 相关 workaround **不需要**（生产构建默认关闭），并把该结论写回
   DEPLOY.md §6；
4. 测试用 cephx 用户按最小权限创建（`ceph fs authorize`），**禁止**用 `client.admin` 跑
   除"权限矩阵对照组"以外的任何用例。

---

## 5. 测试层级与套件设计

### L0 — 静态与构建质量（无需集群，PR 门禁）

| 项 | 工具 | 判定 |
|---|---|---|
| 代码风格 | maven-checkstyle（Hadoop 规则裁剪版） | 0 error |
| 缺陷模式 | SpotBugs（含 `findsecbugs` 插件） | 0 High，Medium 需登记 |
| 依赖 CVE | OWASP dependency-check | 无 CVSS ≥ 7.0 未豁免项 |
| 许可证 | license-maven-plugin | 产物内 LGPL(libcephfs) 与 Apache-2.0 隔离关系与 DEPLOY.md §2.1 声明一致 |
| 覆盖率 | JaCoCo | 达 §2 阈值，低于则 fail build |
| 制品可重现性 | 连续两次 `make-dist.sh` 产物 sha256 | 一致（须先剔除时间戳/顺序噪声，T08 负责） |
| 发布 so 纯净度 | `readelf -d libcephfs_jni.so` | 无指向构建机的 RUNPATH（T07 遗留 2） |

### L1 — 单元测试（mock，无集群）

保持 122 例底座，按 TEST-CASES.md `UT-*` 补齐：异常映射矩阵、layout 归一化边界、
`statfs` 大容量溢出、`setOwner` 数字/非数字分支、`pathString` 编码与特殊字符、
`getFileBlockLocations` 降级路径、流的越界/关闭后调用/部分写循环。

### L2 — 契约测试（真实集群，可在 E1 与 E2 双跑）

现有 9 套件保留，**扩接 Hadoop 官方另外两组基类套件**（hadoop-common test-jar 已在依赖中，
零新增依赖、高性价比）：

- `FSMainOperationsBaseTest` → `ITestCephFSMainOperations`
- `FileContextMainOperationsBaseTest` → `ITestCephFileContextMainOperations`
- `FileContextCreateMkdirBaseTest` / `FileContextPermissionBase` / `FileContextURIBase`

预期新增 150+ 例官方语义断言，尤其补强 `FileContext`（`CephFs`）路径——当前该路径仅有
自研 ITest 185 行覆盖，而 YARN 走的正是它。

### L3 — 功能深化（真实集群，E2）

规模化与边界：超大文件（1GB/10GB/> 2^31 单次读写路径）、大目录（1 万/10 万条目）、
深目录（100 层）、特殊文件名（空格、UTF-8、`%`、`#`、255 字节上限、超长路径）、
可见性语义（G8）、`hflush`/`hsync` 后 kill -9 的持久性、并发 rename/delete 竞态、
`ceph.root.dir` 子目录挂载、Trash、`getContentSummary`、`FileSystem.Statistics` 计数准确性、
`getFileChecksum` 为 null 对 DistCp 的影响。

### L4 — 可靠性与故障注入（E2） → §6

### L5 — 性能与容量基准（E2） → §7

### L6 — 生态集成与兼容矩阵（E3/E4） → §9、§11

### L7 — 部署与运维验收（E2/E3）

发行版安装路径（`share/hadoop/common/lib` + native 目录）、升级与回滚（1.0.0 ↔ 1.1.0 共存/替换）、
配置项全矩阵生效性（13 个键逐个改值验证生效与默认回退）、日志规范（无密钥泄漏、
错误可诊断）、监控采集点、容量规划建议、DEPLOY.md 全文按真实发行版重跑。

---

## 6. 故障注入清单（L4）

每个场景须记录：**注入方式（命令）→ 客户端观测行为 → 恢复行为 → 数据一致性结论 →
运维处置口径**。结论回写 DEPLOY.md 排查表。

| ID | 场景 | 注入方式 | 关注点 |
|---|---|---|---|
| F01 | active MDS 崩溃，有 standby | `ceph mds fail <id>` / kill -9 | 进行中的读/写/元数据操作是否阻塞后自动继续；阻塞时长；有无 EIO |
| F02 | MDS 全灭（无 standby） | 停全部 mds | 操作挂起 vs 报错；超时可控性；恢复后是否需重建 FileSystem |
| F03 | 单 OSD down（副本充足） | `ceph osd down/stop` | IO 无中断；BlockLocation 结果变化 |
| F04 | PG 降级到 inactive | 停 2/3 副本所在 OSD | 写阻塞行为、超时、恢复后 md5 一致 |
| F05 | mon 部分/全部不可达 | iptables DROP | mount 阶段与运行期分别的超时与报错文本（已知 mount 期约 50s） |
| F06 | **客户端被 evict/blocklist** | `ceph tell mds.<id> client evict id=<n>` | 后续操作错误码；已打开流的表现；**连接器无重连**，须给出运维口径与是否需要代码增强 |
| F07 | 网络劣化 | `tc netem delay 100ms loss 1%` | 吞吐/延迟退化曲线；无错误放大 |
| F08 | 集群整体重启 | 全停全起 | 长连接恢复；正在跑的 MR 作业结局 |
| F09 | 池写满 ENOSPC | 小池灌满 | 异常类型是否可诊断（当前直传 IOException）；是否需要专门映射 |
| F10 | 配额触发 | `setfattr ceph.quota.max_bytes` | 同上；Hadoop 侧错误可读性 |
| F11 | caps 不足矩阵 | `r` / `rw`（无 p）/ 路径受限 caps | 失败点与错误文本（`rwp` 结论已有，需扩为矩阵） |
| F12 | 只读文件系统 / MDS read-only | `ceph fs set <fs> ... ` | 写操作失败语义 |
| F13 | 客户端 JVM 被 kill -9（写入中） | kill -9 | 已 `hsync` 数据必须存活；未 flush 数据丢失可接受；文件不得损坏；后续 append/覆盖正常 |
| F14 | 双客户端并发写同一路径 | 两 JVM 同写 | 语义澄清（POSIX 无锁，Hadoop 亦不保证）；写入文档"已知限制" |
| F15 | MDS cache 压力 / 大量打开文件 | 并发打开 5 万文件 | 客户端内存、MDS 告警、是否 OOM/超时 |

**F06 与 F02 的结论直接决定"连接器是否需要重连/重试能力"**，属发布评审必答项（附录 A-1）。

---

## 7. 性能与容量基准（L5）

### 7.1 对照基线

同一 E2 集群、同一 pool，用**内核态 CephFS mount + fio** 作为 100% 参照；同时记录
`ceph-fuse` 参考值。所有连接器数据以"占内核 mount 百分比"呈现，避免绝对值不可比。

### 7.2 负载与工具

| 类别 | 工具 | 说明 |
|---|---|---|
| Hadoop 侧吞吐 | `TestDFSIO`（hadoop-mapreduce-client-jobclient tests jar） | write/read，`-nrFiles` × `-fileSize` 矩阵 |
| 端到端作业 | `TeraGen`/`TeraSort`/`TeraValidate` | 1GB/100GB 两档 |
| 微基准 | `scripts/bench/io-bench.java`（T10 交付） | 纯 FileSystem API，去掉 MR 噪声 |
| 元数据基准 | `scripts/bench/meta-bench.java`（T10 交付） | create/stat/list/rename/delete OPS，mdtest 口径 |
| 对照 | fio（内核 mount） | 顺序/随机、1/4/16/64 并发 |

### 7.3 参数矩阵

文件大小 `{1KB, 1MB, 64MB, 1GB, 10GB}` × 并发 `{1, 4, 16, 64}` ×
`ceph.client.buffer.size` `{1MB, 4MB, 16MB}` × `ceph.object.size` `{4MB, 64MB, 256MB}` ×
`ceph.localize.reads` `{true, false}`。
全矩阵仅在发布前跑一次；夜间只跑**冒烟子集**（1MB/1GB × 并发 1/16 × 默认参数）。

### 7.4 必测的实现敏感点（G9 相关）

- `listStatus` 的 N+1 次 RPC：10 万条目目录耗时曲线，与内核 `ls -l` 对比，判断是否需要
  `readdirplus` 优化（结论进附录 A）；
- `getContentSummary` 深树递归耗时；
- `hflush`/`hsync` = 每次 `ceph fsync` 的代价：小包高频 sync 场景（HBase WAL 类）延迟分布；
- `CephOutputStream` 部分刷盘时的 `Arrays.copyOfRange` 分配开销（GC 日志佐证）；
- `getFileBlockLocations` 每次调用打开/关闭一个 fd 的开销（大作业 split 计算阶段放大）。

### 7.5 产出

`docs/perf/BASELINE-<version>.md`（含环境指纹、结论表、结论解读）+ 原始 CSV +
火焰图/GC 日志等佐证；后续版本以此做回归比较（阈值见 §2）。

---

## 8. 长稳测试（L5/L4 交叉）

- **时长**：发布前 72h 连续；日常夜间 4h 简版。
- **负载**：混合读写 7:3 + 元数据操作 + 每 6h 一次故障注入（F01/F03/F07 轮转）+
  每小时抽样 md5 校验。
- **监控采集**（`scripts/soak/monitor.sh`，T12 交付，60s 粒度）：
  客户端 JVM heap（`jcmd GC.heap_info`）、**进程 RSS 与 native 内存**（`pmap`/NMT）、
  线程数、`/proc/<pid>/fd` 计数、CephFS 会话数（`ceph tell mds.<id> session ls | jq length`）、
  慢请求（`ceph health detail`）、OSD/MDS 侧延迟。
- **判定**：§2 长稳阈值；任一泄漏指标越线即建 S2 缺陷。
- **特别关注**：`CephFs`（AbstractFileSystem）无 close 钩子，mount 生命周期同 JVM
  （PROGRESS T05 登记）——长跑 NodeManager 场景下的 mount/会话累积必须量化。

---

## 9. 兼容矩阵（L6）

| 维度 | 取值 | 优先级 |
|---|---|---|
| Hadoop | **3.3.6（基线）**、3.3.9、3.4.x | P0 / P1 / P2 |
| JDK | 8、**11（基线）**、17 | P1 / P0 / P2（Hadoop 3.3 在 17 上有已知限制，结论写入"已知限制"） |
| Ceph 服务端 | **16.2.14（基线）**、16.2 最新、17.2 Quincy、18.2 Reef | P0 / P0 / P1 / P2 |
| libcephfs 客户端 | 与服务端同版本；**故意错配**（jar 16.2.14 + so 17.2）为负向用例 | P0 |
| OS/glibc | Ubuntu 20.04（基线）、22.04、Rocky 8 | P0 / P1 / P1 |

**判定**：绿=全部 P0 用例通过；黄=有书面豁免；红=阻断。矩阵结果表进 `docs/COMPAT.md`。

---

## 10. 安全与多租户（L7）

| 项 | 内容 | 判定 |
|---|---|---|
| SEC-1 | **身份模型澄清**：连接器所有请求用同一 cephx id，Hadoop UGI 不下传 MDS → 多租户下 CephFS 侧无法按 Hadoop 用户鉴权 | 必须实测确认并在 DEPLOY.md 写"安全模型与适用边界"章节 |
| SEC-2 | 最小 caps 矩阵（`mon r` / `mds rwp` / `osd rw` 及路径受限变体） | 每格记录可用/不可用操作 |
| SEC-3 | `root_squash` caps 下的行为 | 明确失败点与错误文本 |
| SEC-4 | `ceph.root.dir` 子目录挂载 + 路径受限 caps 的组合隔离性 | 越界访问必须失败 |
| SEC-5 | 密钥安全：keyring 权限校验、**日志/异常栈中不得出现 key**（自动 grep 断言）、`ceph.conf.options` 传入敏感项时不落日志 | 0 泄漏 |
| SEC-6 | `setOwner` 只接受数字 uid/gid（非数字静默 warn）→ Hive/Spark 的 chown 语义影响 | 实测影响面，写入"已知限制" |
| SEC-7 | 权限位映射：`stat.mode & 01777`（丢弃 setuid/setgid，保留 sticky） | 与 HDFS 行为差异书面化 |
| SEC-8 | 依赖 CVE 扫描 | §5 L0 阈值 |

---

## 11. 生态集成（L6，E3）

> **完整设计见 [TEST-CASES-ECO.md](TEST-CASES-ECO.md)（88 条场景用例 + 8 条前置 spike）。**
> 该篇按"组件的真实使用姿势 → 反推依赖的 FS API → 对照连接器实现事实"的方法展开，
> 而非每个组件跑一个 hello world。

生态集成是本方案中**风险最高、最可能推翻发布结论**的一段，原因在于连接器有一批
生态组件重度依赖、而冒烟测试打不到的语义偏差：

| 偏差 | 代码事实 | 波及组件 |
|---|---|---|
| `getOwner()` 在 uid ≠ 进程 uid 时返回**数字字符串**，`getGroup()` **恒为数字** | `CephFileSystem#ownerName/groupName` | MR staging 属主校验、`access()` 鉴权、Hive/YARN 目录准备 |
| `setOwner` 仅接受数字 uid/gid（非数字静默 warn） | `CephFileSystem#setOwner` | Hive 建库建表、运维脚本 |
| 已打开 reader 看不到后续追加（长度快照） | `CephInputStream` 构造器 | HBase WAL、流式 tail 读 |
| `CephFs` 仅覆写 `getUriDefaultPort`，`FileContext` 语义全靠基类 | `CephFs.java` | Spark Structured Streaming checkpoint、YARN 日志聚合 |
| 无委托 Token；单一 cephx id | 基类 `addDelegationTokens` | **Kerberos 安全集群**的适用性 |
| `getFileChecksum` 为 null、`truncate`/`concat` 不支持、能力声明仅 2 项 | 基类默认 | DistCp `-update`、Flink RecoverableWriter |

**组件覆盖面**：FsShell/CLI、MapReduce、YARN（日志聚合/本地化/长跑 NM）、Hive、
Spark（批 + Structured Streaming）、HBase（评估）、Tez/Flink（评估）、DistCp、
Kerberos 安全集群与多租户形态、HDFS 混合部署与迁移。

**最终对外交付物是《组件支持矩阵》**：每个组件标注 支持 / 受限支持（附条件）/ 不支持
＋ 配置示例——用户据此判断自己的技术栈能否使用本连接器，其价值高于任何单条用例。

---

## 12. 质量门禁与 CI 流水线

| 阶段 | 触发 | 内容 | 时长上限 | 阻断 |
|---|---|---|---|---|
| **PR 门禁** | 每次 PR | L0 + L1（122+ 单测） | 10 min | 是 |
| **合并门禁** | 合入主干 | 上述 + L2 契约（E1 容器化 vstart / 单节点 ceph 容器） | 30 min | 是 |
| **夜间** | 每日 | L3 全量 + L4 子集（F01/F03/F05/F13）+ L5 冒烟 + e2e-cli | 4 h | 报警 |
| **周度** | 每周 | 兼容矩阵 P0/P1 + 生态 ECO-1/4/6 | 8 h | 报警 |
| **发布前** | 打 tag 前 | 全量 L0–L7 + 72h 长稳 + 全兼容矩阵 + 部署终验 | 5 天 | 是 |

实现要点：

- 仓库当前无 `.github/`；CI 用 GitHub Actions + **self-hosted runner**（Ceph 集群不可能跑在
  托管 runner 上）。L0/L1 可用托管 runner。
- 合并门禁的集群依赖用**容器化单节点 Ceph**（`quay.io/ceph/ceph` + cephadm bootstrap 或
  vstart 容器镜像）解耦开发机，脚本 `scripts/ci/ceph-single-node.sh`（T08 交付）。
- 测试报告（surefire/failsafe XML、JaCoCo、性能 CSV）统一归档到构建产物，保留 90 天。
- **flaky 管控**：不允许在 CI 里配置自动 rerun 掩盖失败；连续 20 轮夜跑统计不稳定用例，
  flaky 一律建单并在 7 天内定位（多为集群时序问题，须区分产品缺陷与测试缺陷）。

---

## 13. 缺陷管理与准入准出

### 13.1 严重级别

| 级别 | 定义 | 响应 |
|---|---|---|
| S1 | 数据丢失/静默损坏；集群或客户端不可用；安全越权 | 立即停止测试，24h 内定位 |
| S2 | 核心功能不可用、无 workaround；泄漏导致长跑必然失败；性能低于阈值 50% | 3 日内修复 |
| S3 | 有 workaround 的功能缺陷；性能未达阈值但 > 50% | 发布前修复或书面豁免 |
| S4 | 文档、日志、易用性 | 可延期 |

### 13.2 准入（开始 L3+ 的前提）

L0/L1/L2 全绿；E2 集群 `HEALTH_OK`；环境指纹已归档；测试用 cephx 用户按最小权限创建。

### 13.3 准出（发布评审）

§2 全部阈值达标 + S1/S2 清零 + 附录 A 的每个待决项有结论（修复 / 文档化为已知限制 /
延期并给出触发条件）+ DEPLOY.md 排查表已按 L4 结论更新 + 生产就绪报告
`docs/RELEASE-READINESS-1.1.0.md` 签署。

---

## 14. 阶段划分与排期

按仓库既有协作规范（每阶段一份任务书、一个 agent 会话、完成后登记 PROGRESS.md）。

| 阶段 | 任务书 | 内容 | 交付物 | 估时 |
|---|---|---|---|---|
| **SPIKE** | （并入 T11 任务书 §0，**最先执行**） | 8 条高风险预测的证伪：MR staging 属主校验、`access()` 组鉴权、chown 静默失效、Spark checkpoint 原子创建、HBase WAL 可见性、Kerberos 适用性、DistCp `-update`、YARN 日志聚合权限 | `docs/ECO-FINDINGS.md` 初版 | 3–5 d |
| **T08** | 测试基础设施与质量门禁 | E2/E3 环境脚本、CI 流水线、L0 全套、L1/L2 扩展（官方 FSMainOperations / FileContext 套件） | `scripts/env/*`、`scripts/ci/*`、pom 插件、新增契约套件 | 5–7 d |
| **T09** | 功能深化与故障注入 | L3 全量 + L4 十五类故障 + 预期行为表 | `ITest*` 扩展、`scripts/fault/*`、DEPLOY.md 排查表更新 | 7–10 d |
| **T10** | 性能与容量基准 | L5 全矩阵 + 内核 mount 对照 + 敏感点分析 | `scripts/bench/*`、`docs/perf/BASELINE-1.1.0.md` | 5–7 d |
| **T11** | 生态集成与兼容矩阵 | L6：8 条 spike + 88 条组件场景（CLI/MR/YARN/Hive/Spark/HBase/Tez/Flink/DistCp/安全集群/混合部署）+ 四维兼容矩阵 | `scripts/eco/*`、`docs/ECO-FINDINGS.md`、**《组件支持矩阵》**、`docs/COMPAT.md` | 14–18 d（本方案最大阶段） |
| **T12** | 安全、长稳与发布验收 | §10 安全项 + 72h 长稳 + L7 部署运维 + 发布评审 | `scripts/soak/*`、`docs/RELEASE-READINESS-1.1.0.md` | 5–7 d（含 72h 挂机） |

**SPIKE 最先执行**（成本低、结论影响面大：若 SP-01/SP-04/SP-06 成立，T11 范围与产品路线
都要变，甚至需要先做一轮产品增强再继续）；T08 是其余阶段的硬前置；T09/T10 可在 T08 后
并行（须错开独占 E2 的时段——性能与故障注入不得同时跑）；T11 依赖 T08 的 E3；T12 收口。

---

## 15. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| E2/E3 物理资源不足（≥3 节点 + Hadoop 集群） | 阻塞 L3–L7 | 优先级：E2 > E3；E3 可用单机多容器降配，但性能数据仅作趋势参考并显著标注 |
| 性能基线抖动（共享硬件/邻居噪声） | 回归误判 | 每次跑 3 轮取中位数 + 记录环境指纹 + 阈值留 10% 缓冲；基线机独占 |
| 故障注入破坏集群，恢复耗时 | 拖慢排期 | E2 集群一键重建脚本 + 快照；故障用例集中在独立时间窗 |
| F06/F02 结论要求代码增强（重连/重试） | 超出"仅测试"边界 | 本方案只负责**产出结论与用例**；代码增强另立任务，附录 A 登记 |
| 上游 Ceph/Hadoop 版本变动 | 矩阵膨胀 | 矩阵按 P0/P1/P2 分级，P2 仅在发布前跑一次 |
| 长稳 72h 占用测试床 | 与其他阶段冲突 | 排在 T12，与 T09–T11 串行；夜间 4h 简版做早期预警 |

---

## 16. 交付物清单

1. `docs/TEST-PLAN.md`（本文）、`docs/TEST-CASES.md`（用例清单）、
   `docs/TEST-CASES-ECO.md`（生态组件场景设计）；
2. `docs/tasks/T08–T12`（五份任务书）；
3. 环境与工具脚本：`scripts/env/`、`scripts/ci/`、`scripts/fault/`、`scripts/bench/`、
   `scripts/soak/`、`scripts/eco/`；
4. 测试代码：L1/L2/L3 新增 `Test*`/`ITest*`；
5. 结果文档：`docs/perf/BASELINE-1.1.0.md`、`docs/COMPAT.md`、`docs/FAULT-BEHAVIOR.md`、
   `docs/ECO-FINDINGS.md`、**《组件支持矩阵》**（README + DEPLOY.md）、
   `docs/RELEASE-READINESS-1.1.0.md`，DEPLOY.md 排查表与"安全模型/已知限制"章节更新；
6. PROGRESS.md 各阶段登记。

---

## 附录 A — 测试驱动的代码待决项（**本方案不实现，只负责产出结论**）

| # | 待决项 | 触发条件 | 依据 |
|---|---|---|---|
| A-1 | **失联/被 evict 后的重连与重试能力** | F02/F06 显示需重建 FileSystem 才能恢复 | `CephTalker` 无重试；`shutdown()` 后恒抛 `CephNotMountedException` |
| A-2 | reader 可见性：`fileLength` 快照 vs 动态刷新 | G8 用例显示与下游（Spark/Hive 读正在写的文件）冲突 | `CephInputStream` 构造器 |
| A-3 | `listStatus` 用 readdirplus 批量取 stat | L5 显示 10 万条目目录明显劣于内核 mount | `CephFileSystem#listStatus` N+1 |
| A-4 | 默认 layout 时改用 3 参 `open`，把最小 caps 降回 `mds rw` | 若部署方无法接受 `p` 位 | PROGRESS T07 遗留 1 |
| A-5 | `getFileChecksum` 实现（或明确声明不支持） | ECO-6 显示 DistCp `-update` 受影响 | 基类返回 null |
| A-6 | ENOSPC/配额异常映射为可诊断的 Hadoop 异常 | F09/F10 显示错误不可读 | `mapCephException` 仅映射 3 类 |
| A-7 | 指标暴露（连接数、fd、IO 计数）用于生产监控 | L7 运维验收要求 | 当前仅 `FileSystem.Statistics` |
| A-8 | 发布用 `libcephfs_jni.so` 的纯净构建与签名 | L0 制品检查 | PROGRESS T07 遗留 2/3 |
| A-9 | **owner/group 的用户名映射**（可选 NSS 查询或配置化 uid↔用户名映射表） | SP-01/SP-02/SP-03 任一成立——MR 提交、`access()` 鉴权、chown 受影响 | `ownerName`/`groupName` 返回数字字符串；架构文档 §4-6 明确"不做 NSS 查询" |
| A-10 | **安全集群支持口径**（委托 Token 不可行时，keyring 分发方案与风险边界） | SP-06 成立 | 基类 `addDelegationTokens` + 单一 cephx id |
| A-11 | `truncate` 实现或明确能力声明 | ECO-FLK-02 / ECO-HIVE-09 需要 | 未实现，`hasPathCapability` 亦未声明 |

## 附录 B — 工具清单

`fio`、`tc/netem`、`iptables`、`jcmd`/`jfr`/NMT、`pmap`、`async-profiler`、
`ceph` CLI（`tell`、`daemon perf dump`、`osd tree`、`health detail`）、
`TestDFSIO`/`TeraSort`（hadoop-mapreduce-client-jobclient tests jar）、
JaCoCo、SpotBugs+findsecbugs、Checkstyle、OWASP dependency-check。
