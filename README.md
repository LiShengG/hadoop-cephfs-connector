# Hadoop CephFS 连接器 (hadoop-cephfs)

为 **Hadoop 3.3.6** 实现 **CephFS 16.2.14** 后端存储支持：实现 Hadoop `FileSystem` /
`AbstractFileSystem` 接口，使 Hadoop 生态（MapReduce / Hive / Spark / YARN 等）可以用
`ceph://` URI 直接读写 CephFS。

**状态：v1.0.0 已发布（2026-07-07，T01–T07 全部完成）。**
Hadoop FileSystem 契约测试 116/116 通过（仅 1 例按根目录保护口径 override）、
全量门控测试 140 例绿、CLI 端到端 200MB md5 往返一致；部署包一键产出并按文档
在干净 shell 完成终验（见 PROGRESS.md T07 节）。

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
| [docs/ENV.md](docs/ENV.md) | 本机开发环境事实（集群、JNI 产物、路径） | 开发者 |
| [docs/00-顶层架构设计.md](docs/00-顶层架构设计.md) | 总体架构、分层设计、语义映射、配置项、风险清单 | 所有 agent 必读 |
| [docs/01-协作规范.md](docs/01-协作规范.md) | 多 agent 协作流程、接口冻结规则、进度登记 | 所有 agent 必读 |
| [docs/tasks/T01-*.md](docs/tasks/) ~ T07 | 各阶段子任务书，每份对应一个 agent 会话 | 对应 agent |
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

## 关键环境事实（开发机）

- 工作根目录：`/home/lsh/code`
- Ceph 源码与构建目录：`/home/lsh/code/ceph`，`/home/lsh/code/ceph/build`
  - Java 绑定产物：`dist/native/libcephfs.jar` + `libcephfs_jni.so`（重建见 ENV.md §2）
  - vstart 测试集群：`scripts/cluster-up.sh` / `cluster-down.sh`
- Hadoop 3.3.6 源码：`/home/lsh/code/hadoop-3.3.6-src`（只读参考）
- Java 11.0.27（Ubuntu OpenJDK），Maven 3.6.3，Git 2.25.1
