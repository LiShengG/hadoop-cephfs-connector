# Hadoop CephFS 连接器开发者文档（hadoop-cephfs 1.0.0）

面向开发者：构建、三级测试、打包。部署与使用见 [DEPLOY.md](DEPLOY.md)，
本机环境事实（路径、集群、JNI 产物）见 [ENV.md](ENV.md)。

## 1. 工程布局

```
hadoop-cephfs-connector/
├── hadoop-cephfs/          # Maven 工程（连接器源码与测试）
├── conf/                   # core-site.xml.example
├── dist/native/            # libcephfs.jar + libcephfs_jni.so（重建见 ENV.md §2）
├── scripts/                # 全部可复现脚本（见下）
└── docs/                   # 设计文档、任务书、本文档
```

依赖关系：`hadoop-common:3.3.6`（provided）、`com.ceph:libcephfs:16.2.14`
（本地仓库安装方法见 ENV.md §2）。字节码目标 Java 8，构建用 JDK 11。

## 2. 构建

```bash
cd hadoop-cephfs
mvn clean package          # 编译 + 全部无集群单测 → target/hadoop-cephfs-1.0.0.jar
```

非本机默认环境时用 `-Dceph.lib.dir=<Ceph 库目录> -Dceph.jni.dir=<jni so 目录>`
覆盖 pom 里的 JNI 路径默认值。

## 3. 三级测试（架构文档 §6.2）

### 3.1 单元测试（无集群，mock CephFsProto）

```bash
cd hadoop-cephfs && mvn clean test        # 122 例，任何环境必须全绿
```

### 3.2 契约 + 集成测试（需 vstart 集群，`CEPH_CONTRACT_TEST=1` 门控）

```bash
scripts/cluster-up.sh                     # 启动/复用 vstart 集群（-n 销毁重建）
scripts/smoke-test.sh                     # 怀疑环境坏了先跑这个（JNI 全链路自检）
cd hadoop-cephfs
CEPH_CONTRACT_TEST=1 mvn verify           # 全量门控 140 例（Hadoop FS 契约 116 + 集成 24）
# 只跑某套件：
CEPH_CONTRACT_TEST=1 mvn verify -Dit.test='ITestCephContract*'
```

未设 `CEPH_CONTRACT_TEST=1` 时 `ITest*` 全部 skip，`mvn verify` 无集群也绿。
注意：vstart 调试构建需规避 lockdep 误报，pom 的 failsafe 已对测试进程设
`CEPH_ARGS=--lockdep=false`（详见 pom 注释与 DEPLOY.md §6）。

### 3.3 CLI 端到端（需 vstart 集群）

```bash
scripts/e2e-cli-test.sh                   # FsShell 全链路 21 项断言 + 200MB md5 往返
```

### 集群操作

```bash
scripts/cluster-up.sh [-n]   # 启动（-n 重建，清数据；ceph.conf 会被重写）
scripts/cluster-down.sh      # 停止（保留数据）
```

## 4. 打包发布

```bash
scripts/make-dist.sh                      # mvn clean package（含单测）+ 组装 tar.gz
SKIP_TESTS=1 scripts/make-dist.sh         # 仅打包（跳过单测）
```

产物 `dist/hadoop-cephfs-1.0.0.tar.gz`，内容与理由见脚本头注释及 DEPLOY.md §2.1。

## 5. 发布前回归门禁

依次全绿方可发布（T06/T07 既定门禁）：

```bash
cd hadoop-cephfs && mvn clean test        # 1) 无集群单测 122
scripts/cluster-up.sh && cd hadoop-cephfs && CEPH_CONTRACT_TEST=1 mvn verify   # 2) 门控 140
scripts/e2e-cli-test.sh                   # 3) CLI 端到端（退出码 0）
scripts/make-dist.sh                      # 4) 打包成功
```

## 6. 代码规约（协作规范 §4 摘要）

- 包名固定 `org.apache.hadoop.fs.ceph`；日志 slf4j；
- `CephFsProto` / `CephConfigKeys` 为冻结接口，只增不改（改动须登记 PROGRESS.md）；
- 单元测试 `Test*.java`（surefire），需集群的 `ITest*.java`（failsafe + 门控）；
- 可复现步骤一律写成 `scripts/` 下脚本，不留在会话里。
