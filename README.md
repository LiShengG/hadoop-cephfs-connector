# Hadoop CephFS 连接器 (hadoop-cephfs)

为 **Hadoop 3.3.6** 实现 **CephFS 16.2.14** 后端存储支持：实现 Hadoop `FileSystem` /
`AbstractFileSystem` 接口，使 Hadoop 生态（MapReduce / Hive / Spark / YARN 等）可以用
`ceph://` URI 直接读写 CephFS。

**状态：v1.0.0 已发布（2026-07-07，T01–T07 全部完成）。**
Hadoop FileSystem 契约测试 116/116 通过（仅 1 例按根目录保护口径 override）、
全量门控测试 140 例绿、CLI 端到端 200MB md5 往返一致；部署包一键产出并按文档
在干净 shell 完成终验（见 PROGRESS.md T07 节）。

## 完成进度表

> 更新日期：2026-08-12 ｜ 评估对象：当前主干（pom 版本 1.0.0）
> 明细（每项要做的事、里程碑门槛、关键路径）见 **[docs/READINESS.md](docs/READINESS.md)**。

| # | 维度 | 权重 | 当前 | 加权 | 一句话差距 |
|---|---|---:|---:|---:|---|
| A | 功能完整性 | 10 | **90%** | 9.0 | API 面已齐，仅缺 checksum/truncate 等边角 |
| B | 语义正确性 | 15 | **75%** | 11.3 | 契约 116 例全绿，但只在**一种**环境上验过 |
| C | 可靠性与恢复 | 15 | **0%** | 0.0 | 15 类故障一条没测；代码无重试无重连 |
| D | 性能与容量 | 10 | **0%** | 0.0 | 无任何基线，不知道比内核 mount 慢多少 |
| E | 长稳与资源 | 10 | **5%** | 0.5 | 最长跑过 300 秒 |
| F | 生态兼容 | 20 | **5%** | 1.0 | 从未在 MR/YARN/Hive/Spark 下跑过 |
| G | 安全与多租户 | 8 | **20%** | 1.6 | 身份模型未澄清，安全集群适用性未知 |
| H | 工程化与门禁 | 7 | **20%** | 1.4 | 无 CI、无覆盖率、无静态检查 |
| I | 文档与交付 | 5 | **70%** | 3.5 | 部署文档扎实，缺支持矩阵与安全边界 |
| | **合计** | **100** | | **≈ 28%** | 距 GA 尚远，差的是证据不是功能 |

```
A 功能完整性  ██████████████████░░  90%
B 语义正确性  ███████████████░░░░░  75%
C 可靠性      ░░░░░░░░░░░░░░░░░░░░   0%   ← 权重 15，全空
D 性能        ░░░░░░░░░░░░░░░░░░░░   0%   ← 权重 10，全空
E 长稳        █░░░░░░░░░░░░░░░░░░░   5%
F 生态兼容    █░░░░░░░░░░░░░░░░░░░   5%   ← 权重 20，全空（最大缺口）
G 安全        ████░░░░░░░░░░░░░░░░  20%
H 工程化      ████░░░░░░░░░░░░░░░░  20%
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
cd hadoop-cephfs && mvn clean package        # 构建 + 无集群单测 122 例
scripts/cluster-up.sh                        # vstart 集群
CEPH_CONTRACT_TEST=1 mvn verify              # 契约 + 集成 140 例（需集群）
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
SPIKE 8 条高风险预测的证伪（3–5 天，最先执行，见 TEST-CASES-ECO.md §3）      PLANNED
 │
T08 测试基础设施与质量门禁（E2/E3 环境、CI、L0 门禁、官方契约套件扩展）  PLANNED
 ├─> T09 功能深化与故障注入（L3 + 15 类故障场景）                        PLANNED
 ├─> T10 性能与容量基准（对照内核 mount 建立基线）                       PLANNED
 ├─> T11 生态集成与兼容矩阵（88 组件场景 + 四维矩阵 → 组件支持矩阵）     PLANNED
 └─> T12 安全、长稳与发布验收（安全模型澄清 + 72h 长稳 + 生产就绪报告）   PLANNED
```

> 生态集成是风险最高的一段：连接器在**属主/组字符串、`access()` 鉴权、`setOwner`、
> 写入中文件可见性、`FileContext` 语义、委托 Token** 六处存在生态组件重度依赖、
> 而冒烟测试打不到的语义偏差。详见 [docs/TEST-CASES-ECO.md](docs/TEST-CASES-ECO.md)。

## 关键环境事实（开发机）

- 工作根目录：`/home/lsh/code`
- Ceph 源码与构建目录：`/home/lsh/code/ceph`，`/home/lsh/code/ceph/build`
  - Java 绑定产物：`dist/native/libcephfs.jar` + `libcephfs_jni.so`（重建见 ENV.md §2）
  - vstart 测试集群：`scripts/cluster-up.sh` / `cluster-down.sh`
- Hadoop 3.3.6 源码：`/home/lsh/code/hadoop-3.3.6-src`（只读参考）
- Java 11.0.27（Ubuntu OpenJDK），Maven 3.6.3，Git 2.25.1
