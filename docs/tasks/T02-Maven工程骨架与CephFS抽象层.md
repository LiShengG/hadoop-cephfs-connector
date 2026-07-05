# T02 — Maven 工程骨架与 CephFS 抽象层

## 目标

搭建 `hadoop-cephfs` Maven 工程，交付并**冻结**三个基础类：
`CephConfigKeys`、`CephFsProto`、`CephTalker`，使后续任务在稳定地基上开发。

## 前置依赖

T01 DONE（本地仓库有 `com.ceph:libcephfs:16.2.14`，`docs/ENV.md` 可用）。
必读：架构文档 §2.3、§3.1、§5、§6.1；`docs/ENV.md`。

## 工作内容

1. **核对绑定 API**：通读 `ceph/src/java/java/com/ceph/fs/CephMount.java`
   （以及 `CephStat`、`CephStatVFS`、`CephFileExtent`、异常类），
   将架构文档 §3.1 的 `CephFsProto` 签名与实际 API 逐一核对；
   有出入的以源码为准修订，并将最终签名表登记到 PROGRESS.md（此后冻结）。
2. **Maven 工程** `hadoop-cephfs/`：
   - `hadoop-common:3.3.6`（provided）、`com.ceph:libcephfs:16.2.14`（compile）、
     junit4 + mockito（test）、`hadoop-common:3.3.6:test-jar`（test，供 T06 契约测试）；
   - Java 8 字节码目标（maven.compiler.release=8，保证与 Hadoop 3.3.6 兼容）；
   - surefire 配置：透传 `java.library.path`、`LD_LIBRARY_PATH` 与
     `CEPH_CONTRACT_TEST` 环境变量。
3. **`CephConfigKeys`**：架构文档 §5 全部配置键与默认值的常量类。
4. **`CephFsProto`**：抽象类，按核对后的签名定义（含 javadoc：每个方法的
   异常约定，如 `lstat` 对不存在路径抛 `FileNotFoundException`）。
5. **`CephTalker extends CephFsProto`**：封装 `CephMount` 的生产实现：
   - `initialize(uri, conf)`：创建 `CephMount(authId)` → `conf_read_file(ceph.conf)` →
     `conf_set` 逐项下发（keyring、`ceph.conf.options`、URI authority 覆盖 mon_host、
     `client_readahead_*` 等）→ `mount(ceph.root.dir)`；
   - 路径转换：Hadoop `Path` → CephFS 绝对路径字符串的唯一入口（私有方法），
     处理 scheme/authority 剥离；
   - `shutdown()`：`unmount` + `ceph_release`，幂等。
6. **测试**：
   - 单元：`CephConfigKeys` 默认值、路径转换边界（根路径、相对路径、含空格）；
   - 集成 `ITestCephTalker`（`CEPH_CONTRACT_TEST=1` 门控）：对 vstart 走一遍
     initialize → mkdirs → lstat → listdir → open/write/read/close → unlink → shutdown。

## 交付物

- `hadoop-cephfs/pom.xml` 及标准目录树
- `CephConfigKeys.java`、`CephFsProto.java`、`CephTalker.java`
- `TestCephConfigKeys/TestPathTranslation`（名字可调）、`ITestCephTalker`
- PROGRESS.md：冻结后的 `CephFsProto` 签名清单

## 验收标准

1. `mvn clean test`（无集群环境）绿色通过；
2. 集群启动后 `CEPH_CONTRACT_TEST=1 mvn verify -Dit.test=ITestCephTalker`
   （或等效 surefire 运行方式）通过；
3. `CephFsProto` 覆盖架构文档 §3.1 列出的全部原语（元数据 + 数据 + 局部性三组）；
4. 工程中无任何 `CephFileSystem` 相关代码（不越界）。

## 边界与禁止事项

- 不实现 `CephFileSystem` / 流类 / `CephFs`；
- `CephFsProto` 一经登记冻结，本任务内不得再改动签名。
