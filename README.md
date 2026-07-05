# Hadoop CephFS 连接器 (hadoop-cephfs)

为 **Hadoop 3.3.6** 实现 **CephFS 16.2.14** 后端存储支持：实现 Hadoop `FileSystem` /
`AbstractFileSystem` 接口，使 Hadoop 生态（MapReduce / Hive / Spark / YARN 等）可以用
`ceph://` URI 直接读写 CephFS。

## 文档导航

| 文档 | 说明 | 读者 |
|---|---|---|
| [docs/00-顶层架构设计.md](docs/00-顶层架构设计.md) | 总体架构、分层设计、语义映射、配置项、风险清单 | **所有 agent 必读** |
| [docs/01-协作规范.md](docs/01-协作规范.md) | 多 agent 协作流程、接口冻结规则、进度登记 | 所有 agent 必读 |
| [docs/tasks/T01-*.md](docs/tasks/) ~ T07 | 各阶段子任务书，每份对应一个 agent 会话 | 对应 agent |
| [PROGRESS.md](PROGRESS.md) | 任务进度与交接记录（每个 agent 完成后更新） | 所有 agent |

## 任务总览与依赖关系

```
T01 环境验证与 libcephfs Java 绑定构建
 └─> T02 Maven 工程骨架与 CephFS 抽象层
      └─> T03 元数据操作（CephFileSystem 骨架）
           └─> T04 数据读写流（Input/OutputStream）
                └─> T05 权限属主、BlockLocation 与 AbstractFileSystem
                     └─> T06 契约测试与 vstart 集成验证
                          └─> T07 打包、部署与使用文档
```

任务按顺序串行执行；每个任务的「验收标准」全部通过后，下一个 agent 才启动。

## 关键环境事实（2026-07-05 已核实）

- 工作根目录：`/home/lsh/code`
- Ceph 源码与构建目录：`/home/lsh/code/ceph`，`/home/lsh/code/ceph/build`
  - `build/lib/libcephfs.so.2.0.0` 已构建 ✅
  - Java 绑定（`libcephfs.jar` / `libcephfs_jni.so`）**尚未构建** ❌，源码位于 `ceph/src/java`
  - vstart 测试集群：在 `ceph/build` 目录下运行（`../src/vstart.sh`）
- Hadoop 3.3.6 源码：`/home/lsh/code/hadoop-3.3.6-src`
- Java 11.0.27（Ubuntu OpenJDK），Maven 3.6.3，Git 2.25.1
