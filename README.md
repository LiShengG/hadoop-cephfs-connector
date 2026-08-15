# Hadoop CephFS 连接器 (hadoop-cephfs)

为 **Hadoop 3.3.6** 实现 **CephFS 16.2.14** 后端存储支持：实现 Hadoop `FileSystem` /
`AbstractFileSystem` 接口，使 Hadoop 生态（MapReduce / Hive / Spark / YARN 等）可以用
`ceph://` URI 直接读写 CephFS。

**状态：v1.0.0 已发布（2026-07-07，T01–T07 全部完成）。**
Hadoop FileSystem 契约测试通过，当前 E2 Release 门控为 128/128 单测、142/142 契约与集成；
E3 已完成真实三节点 Hadoop/YARN、MR 提交/推测、DistCp、Hive 4 MR 基础矩阵和 Spark
checkpoint/失败恢复验证。CLI 端到端
200MB md5 往返一致，部署包一键产出并按文档
在干净 shell 完成终验（见 PROGRESS.md T07 节）。

## 完成进度表

> 更新日期：2026-08-15 ｜ 评估对象：当前主干（pom 版本 1.0.0）
> 明细（每项要做的事、里程碑门槛、关键路径）见 **[docs/READINESS.md](docs/READINESS.md)**。

| # | 维度 | 权重 | 当前 | 加权 | 一句话差距 |
|---|---|---:|---:|---:|---|
| A | 功能完整性 | 10 | **92%** | 9.2 | 多主机 BlockLocation 已验证，仅缺 checksum/truncate 等边角 |
| B | 语义正确性 | 15 | **85%** | 12.8 | E2 Release 与最小权限已过，官方基类及规模边界仍待补 |
| C | 可靠性与恢复 | 15 | **0%** | 0.0 | 15 类故障一条没测；代码无重试无重连 |
| D | 性能与容量 | 10 | **0%** | 0.0 | 无任何基线，不知道比内核 mount 慢多少 |
| E | 长稳与资源 | 10 | **5%** | 0.5 | 最长跑过 300 秒 |
| F | 生态兼容 | 20 | **45%** | 9.0 | E3 Hive MR 基础矩阵通过；Tez/ACID/Kerberos 与其余组件待补 |
| G | 安全与多租户 | 8 | **20%** | 1.6 | 身份模型未澄清，安全集群适用性未知 |
| H | 工程化与门禁 | 7 | **40%** | 2.8 | E2/E3 已建并有只读检查；仍缺一键重建、CI 与质量门禁 |
| I | 文档与交付 | 5 | **70%** | 3.5 | 部署文档扎实，缺支持矩阵与安全边界 |
| | **合计** | **100** | | **≈ 39%** | 距 GA 尚远，差的是完整场景、故障与长稳证据 |

```
A 功能完整性  ██████████████████░░  92%
B 语义正确性  █████████████████░░░  85%
C 可靠性      ░░░░░░░░░░░░░░░░░░░░   0%   ← 权重 15，全空
D 性能        ░░░░░░░░░░░░░░░░░░░░   0%   ← 权重 10，全空
E 长稳        █░░░░░░░░░░░░░░░░░░░   5%
F 生态兼容    █████████░░░░░░░░░░░  45%   ← 再增加 Hive 4 MR 基础矩阵真实证据
G 安全        ████░░░░░░░░░░░░░░░░  20%
H 工程化      ████████░░░░░░░░░░░░  40%
I 文档        ██████████████░░░░░░  70%
```

## 快速上手

**部署使用**（第三方机器，30 分钟配通 `ceph://`）：

1. `scripts/make-dist.sh` 产出（或直接获取）`dist/hadoop-cephfs-1.0.0.tar.gz`；
2. 按 **[docs/DEPLOY.md](docs/DEPLOY.md)** 操作：两个 jar 入 classpath →
   native 库路径（`java.library.path` + `LD_LIBRARY_PATH`）→ core-site.xml
   5 个必配项（`fs.AbstractFileSystem.ceph.impl` 必须显式配置）→ cephx 授权
   （mon r + **mds rwp** + osd rw）；
3. 验证：`hadoop fs -ls ceph:///`（无 Hadoop 发行版的 FsShell 等效方式见 DEPLOY.md §5.2）。

**开发构建**（本机，环境事实见 [docs/ENV.md](docs/ENV.md)）：

```bash
cd hadoop-cephfs && mvn clean package        # 构建 + 无集群单测 128 例
scripts/cluster-up.sh                        # vstart 集群
CEPH_CONTRACT_TEST=1 mvn verify              # 契约 + 集成 142 例（需集群）
scripts/e2e-cli-test.sh                      # CLI 端到端（200MB md5 往返）
scripts/make-dist.sh                         # 一键打部署包
```

详见 **[docs/DEVELOP.md](docs/DEVELOP.md)**。

## 文档导航

| 文档 | 说明 | 读者 |
|---|---|---|
| [docs/DEPLOY.md](docs/DEPLOY.md) | 部署：安装、配置、cephx 权限、验证、故障排查表 | 部署者/用户 |
| [docs/DEVELOP.md](docs/DEVELOP.md) | 开发：构建、三级测试、打包、回归门禁 | 开发者 |
| [docs/READINESS.md](docs/READINESS.md) | **完成进度表**（9 维度评分、每项要做的事、里程碑门槛、关键路径） | 决策者/测试/开发 |
| [docs/TEST-PLAN.md](docs/TEST-PLAN.md) | **生产级测试方案**：缺口盘点、环境矩阵、L0–L7 层级、验收阈值、CI 门禁、排期 | 测试/开发/SRE |
| [docs/TEST-CASES.md](docs/TEST-CASES.md) | 生产级用例清单（147 例骨架，ID/优先级/判定标准） | 测试/开发 |
| [docs/TEST-CASES-ECO.md](docs/TEST-CASES-ECO.md) | **Hadoop 生态组件使用场景测试设计**（88 场景 + 8 前置 spike + API 支撑面盘点） | 测试/开发/使用方 |
| [docs/ECO-FINDINGS.md](docs/ECO-FINDINGS.md) | **ECO spike 实测结论**（`.26` A 层证据、缺陷与后续门禁） | 测试/开发/决策者 |
| [docs/E2-ENV.md](docs/E2-ENV.md) | **E2 三节点环境指纹**（拓扑、设备白名单、Release 复验与状态检查） | 测试/SRE/开发 |
| [docs/E3-ENV.md](docs/E3-ENV.md) | **E3 Hadoop/YARN/Hive/Spark 环境与真实分布式验证结果** | 测试/SRE/开发 |
| [docs/ENV.md](docs/ENV.md) | 本机开发环境事实（集群、JNI 产物、路径） | 开发者 |
| [docs/00-顶层架构设计.md](docs/00-顶层架构设计.md) | 总体架构、分层设计、语义映射、配置项、风险清单 | 所有 agent 必读 |
| [docs/01-协作规范.md](docs/01-协作规范.md) | 多 agent 协作流程、接口冻结规则、进度登记 | 所有 agent 必读 |
| [docs/tasks/T01-*.md](docs/tasks/) ~ T07 | 各阶段子任务书，每份对应一个 agent 会话 | 对应 agent |
| [docs/tasks/T08-*.md](docs/tasks/) ~ T12 | 生产就绪测试阶段任务书（测试基础设施 / 故障注入 / 性能 / 生态兼容 / 长稳验收） | 对应 agent |
| [PROGRESS.md](PROGRESS.md) | 任务进度与交接记录（含各任务验收输出） | 所有 agent |

## 任务总览与依赖关系（全部 DONE）

```
T01 环境验证与 libcephfs Java 绑定构建            DONE 2026-07-05
 └─> T02 Maven 工程骨架与 CephFS 抽象层           DONE 2026-07-05
      └─> T03 元数据操作（CephFileSystem 骨架）    DONE 2026-07-05
           └─> T04 数据读写流（Input/OutputStream）DONE 2026-07-05
                └─> T05 BlockLocation 与 AbstractFileSystem  DONE 2026-07-05
                     └─> T06 契约测试与 vstart 集成验证       DONE 2026-07-07
                          └─> T07 打包、部署与使用文档        DONE 2026-07-07 (v1.0.0)
```

## 生产就绪阶段（v1.1.0，方案见 docs/TEST-PLAN.md）

```
E1 单机回归环境（.26：122 单测 + 140 门控 + CLI E2E + 打包）           DONE 2026-08-14
 │
SPIKE 8 条高风险预测的证伪（A 层完成，MR/YARN/Spark B 层部分完成）          PARTIAL 2026-08-15
 │
T08 测试基础设施与质量门禁（E2/E3 已建；CI、官方套件待补）              PARTIAL 2026-08-15
 ├─> T09 功能深化与故障注入（L3 + 15 类故障场景）                        PLANNED
 ├─> T10 性能与容量基准（对照内核 mount 建立基线）                       PLANNED
 ├─> T11 生态集成与兼容矩阵（88 组件场景 + 四维矩阵 → 组件支持矩阵）     PLANNED
 └─> T12 安全、长稳与发布验收（安全模型澄清 + 72h 长稳 + 生产就绪报告）   PLANNED
```

> 生态集成是风险最高的一段：连接器在**属主/组字符串、`access()` 鉴权、`setOwner`、
> 写入中文件可见性、`FileContext` 语义、委托 Token** 六处存在生态组件重度依赖、
> 而冒烟测试打不到的语义偏差。详见 [docs/TEST-CASES-ECO.md](docs/TEST-CASES-ECO.md)。
> `.26` A 层实测结论见 [docs/ECO-FINDINGS.md](docs/ECO-FINDINGS.md)：已发现
> 并修复 `FileContext.rename` 默认端口、普通文件原子 no-replace 与并发 mkdirs；
> 多 JVM、崩溃恢复及 Spark 3.4.4 local checkpoint 已在 E2 Release 通过；E3 又完成
> 三节点 YARN 上的 checkpoint、原子提交后应用失败及后继恢复。身份、可见性与安全边界问题仍在。

## 关键环境事实（开发机）

- 当前 E1 节点：`10.20.40.26`，项目 `/code/hadoop-cephfs-connector`，Ceph build
  `/code/ceph-v16.2.14/build`；3 mon + 1 mgr + 1 active mds + 4 osd，CephFS `a`；
  2026-08-14 已复验 122 单测、140 契约/集成门控、CLI 200MB md5 往返和打包。
- 当前 E1 使用非默认路径，运行前设置
  `CEPH_BUILD=/code/ceph-v16.2.14/build` 与 `CEPH_CONF_FILE=$CEPH_BUILD/ceph.conf`。
- 当前 E1 发布包 sha256：
  `3e4ec5d8fcc4cab6d0fa345b0528c3b9a2443ad8f05b2762393814184b595910`。
- 当前 E2：`.44/.26/.28` 三节点，3 MON + 2 MGR + 6 OSD + 3 MDS，池
  `size=3/min_size=2`；官方 Ceph 16.2.14 Release 服务端、16.2.15 Release JNI 客户端，
  受限 `client.hadoop` 下 128/128 单测与 142/142 契约/集成通过。详见
  [docs/E2-ENV.md](docs/E2-ENV.md)。
- 当前 E3：同三节点部署 Hadoop 3.3.6 + Hive 4.0.1 + Spark 3.4.4；HDFS 3 DataNode、YARN 3
  NodeManager，真实 MR 两种部署形态、committer v1/v2 与推测执行通过，DistCp 双向及
  `-update` 风险已验证，YARN 日志聚合走 CephFS；Hive Text/ORC/Parquet、动态分区、MSCK、
  ANALYZE 与 TRUNCATE 通过；Spark 三节点 checkpoint 从 batch 0/40 行推进到失败后恢复的
  batch 2/120 行。详见 [docs/E3-ENV.md](docs/E3-ENV.md)。
- 以下为 T01–T07 原始开发机基线，继续保留用于历史结果复现：

- 工作根目录：`/home/lsh/code`
- Ceph 源码与构建目录：`/home/lsh/code/ceph`，`/home/lsh/code/ceph/build`
  - Java 绑定产物：`dist/native/libcephfs.jar` + `libcephfs_jni.so`（重建见 ENV.md §2）
  - vstart 测试集群：`scripts/cluster-up.sh` / `cluster-down.sh`
- Hadoop 3.3.6 源码：`/home/lsh/code/hadoop-3.3.6-src`（只读参考）
- Java 11.0.27（Ubuntu OpenJDK），Maven 3.6.3，Git 2.25.1
