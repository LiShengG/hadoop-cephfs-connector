# 项目进度登记

> 每个 agent 完成（或阻塞）时按 `docs/01-协作规范.md` §3 的模板在此追加一节。

| 任务 | 状态 | 完成日期 | 备注 |
|---|---|---|---|
| T01 环境验证与 Java 绑定构建 | DONE | 2026-07-05 | smoke test 全链路通过；产物见 dist/native 与本地 Maven 仓库 |
| T02 Maven 骨架与抽象层 | DONE | 2026-07-05 | mvn test 15/15 绿色；ITestCephTalker 6/6 通过；CephFsProto 签名已冻结（见 T02 节） |
| T03 元数据操作 | DONE | 2026-07-05 | mvn clean test 69/69 绿；ITestCephFileSystemMeta 5/5 通过（含 ServiceLoader 发现验证）；open/create/append 留桩待 T04 |
| T04 数据读写流 | DONE | 2026-07-05 | mvn clean test 95/95 绿；ITestCephIO 3/3 通过（200MB md5/pread/append/hflush/fd 计数） |
| T05 BlockLocation 与 AbstractFileSystem | 未开始 | — | |
| T06 契约测试与集成验证 | 未开始 | — | |
| T07 打包部署与文档 | 未开始 | — | |

---

## T01 环境验证与 libcephfs Java 绑定构建 — DONE（2026-07-05）

- 验收结果：
  1. ✅ `scripts/cluster-up.sh` 从停止状态（先跑 `cluster-down.sh` 确认 `ceph -s` 不可达）执行后就绪：
     `health: HEALTH_WARN`（单 OSD 正常告警 "3 pool(s) have no replicas configured"）、
     `mds: 1/1 daemons up`、`ceph fs ls` → `name: a, metadata pool: cephfs.a.meta, data pools: [cephfs.a.data]`，脚本退出码 0。
  2. ✅ `scripts/smoke-test.sh` 退出码 0（集群重启前后各跑一次均通过）：
     mount→mkdirs→写 1MB→lstat 校验→回读逐字节比对一致→unlink→rmdir→unmount，
     输出 `[smoke] PASS: Java -> JNI -> libcephfs -> CephFS 全链路验证通过`。
  3. ✅ `mvn dependency:get -Dartifact=com.ceph:libcephfs:16.2.14 -o` → BUILD SUCCESS（本地仓库命中）。
     注：首次需在线跑一次同命令以拉取 maven-dependency-plugin 本身，之后离线可复现。
  4. ✅ `docs/ENV.md` 中全部路径/命令逐条实测有效（mon 地址、conf/keyring、重建命令、LD_LIBRARY_PATH 要求）。
- 交付物清单：
  - `dist/native/libcephfs.jar`、`dist/native/libcephfs_jni.so`（git 忽略二进制，磁盘上存在；重建方法见 ENV.md §2）
  - 本地 Maven 仓库 `com.ceph:libcephfs:16.2.14`（/root/.m2/repository）
  - `scripts/cluster-up.sh`、`scripts/cluster-down.sh`、`scripts/smoke-test.sh`、`scripts/smoke/CephSmoke.java`
  - `docs/ENV.md`、初始化的 git 仓库（含 .gitignore）
- 与设计文档的偏差：
  - 任务书称"Java 绑定未构建"，实际开工时 `WITH_CEPHFS_JAVA=ON` 已在 CMakeCache 且产物已存在
    （应为环境预配置）；已通过 `make libcephfs cephfs_jni` 增量重跑确认可复现、`ldd`/`nm` 验证
    56 个 JNI 符号与 libcephfs.so.2 链接正确。路径 A 成立，未动用路径 B。
  - 上游 `ceph/src/java/CMakeLists.txt` 的 meta target `java` 有缺陷（DEPENDS 把 target 名
    `libcephfs_jni` 当文件依赖），`make java` 必失败；正确 target 为 `libcephfs`（jar）与
    `cephfs_jni`（.so）。遵守"不改 ceph 源码"未修复，已在 ENV.md 记录绕行方法。
- 遗留/移交事项：
  - T02 连接集群一律以 `docs/ENV.md` 为准；mon 地址勿硬编码（`vstart.sh -n` 重建会重写 ceph.conf）。
  - JNI 运行须同时设 `java.library.path` 与 `LD_LIBRARY_PATH`（ENV.md §3 有报错对照）。
  - `CephMount` 实际 API 已初步核对（read/write 返回 long 而非架构文档 §3.1 草案中的 int；
    lstat 抛 FileNotFoundException + CephNotDirectoryException），T02 定义 CephFsProto 时以
    `ceph/src/java/java/com/ceph/fs/CephMount.java` 逐一为准。
- 环境变更：
  - vstart 集群经历一次 stop/start 验证（数据保留，mon 端口未变）；当前运行中。
  - 本地 Maven 仓库新增 com.ceph:libcephfs:16.2.14 与 maven-dependency-plugin。

---

## T02 Maven 工程骨架与 CephFS 抽象层 — DONE（2026-07-05）

- 验收结果：
  1. ✅ 无集群 `mvn clean test`（`hadoop-cephfs/`，未设 CEPH_CONTRACT_TEST）：
     `Tests run: 15, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS
     （TestCephConfigKeys 3 + TestPathTranslation 12）。另验证 `mvn verify` 无门控时
     ITestCephTalker 全部 skip（`Tests run: 6, Skipped: 6`），仍 BUILD SUCCESS。
  2. ✅ 集群运行中（`bin/ceph -s`：HEALTH_WARN 单 OSD 正常告警、mds 1/1 up）执行
     `CEPH_CONTRACT_TEST=1 mvn verify -Dit.test=ITestCephTalker`：
     failsafe `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS
     （全流程 initialize→mkdirs→lstat→listdir→open/write/flush/fstat/read/lseek→
     extent/OSD 地址→chmod/setattr/ftruncate/rename/statfs→unlink→rmdir→shutdown）。
     可复现：`scripts/t02-verify.sh [--with-cluster]`。
  3. ✅ CephFsProto 覆盖架构文档 §3.1 全部原语：生命周期 2 + 元数据 9 + 数据 9 + 局部性 2，
     共 22 个方法（清单见下）。
  4. ✅ 工程中无任何 CephFileSystem/流类/CephFs 代码（main 下仅
     CephConfigKeys/CephFsProto/CephTalker 三个类）；字节码目标 Java 8
     （javap major version 52）。
- 交付物清单：
  - `hadoop-cephfs/pom.xml`（hadoop-common:3.3.6 provided、com.ceph:libcephfs:16.2.14 compile、
    junit 4.13.2 + mockito-core 3.12.4 + hadoop-common test-jar test；
    maven.compiler.release=8；surefire/failsafe 均配置
    `-Djava.library.path=${ceph.jni.dir}:${ceph.lib.dir}`、环境变量 LD_LIBRARY_PATH 与
    CEPH_CONTRACT_TEST 透传；JNI 路径可用 `-Dceph.lib.dir/-Dceph.jni.dir` 覆盖）
  - `hadoop-cephfs/src/main/java/org/apache/hadoop/fs/ceph/CephConfigKeys.java`
  - `hadoop-cephfs/src/main/java/org/apache/hadoop/fs/ceph/CephFsProto.java`
  - `hadoop-cephfs/src/main/java/org/apache/hadoop/fs/ceph/CephTalker.java`
  - `hadoop-cephfs/src/test/java/org/apache/hadoop/fs/ceph/TestCephConfigKeys.java`、
    `TestPathTranslation.java`、`ITestCephTalker.java`
  - `scripts/t02-verify.sh`
- **CephFsProto 冻结签名清单**（已按 16.2.14 `CephMount.java` 逐一核对；后续任务只增不改）：

  ```java
  // 生命周期
  void initialize(URI uri, Configuration conf) throws IOException;  // 重复调用抛 IllegalStateException
  void shutdown() throws IOException;                               // 幂等
  // 元数据
  CephStatVFS statfs(Path path) throws IOException;
  void lstat(Path path, CephStat stat) throws IOException;          // 不存在 → FileNotFoundException
  String[] listdir(Path path) throws IOException;                   // 存在但非目录 → null；不存在 → FNF
  void mkdirs(Path path, int mode) throws IOException;              // 已存在（含末段为文件）静默成功，见移交事项
  void rmdir(Path path) throws IOException;                         // 仅空目录
  void unlink(Path path) throws IOException;
  void rename(Path src, Path dst) throws IOException;               // POSIX 语义（覆盖 dst 文件），T03 修正
  void setattr(Path path, CephStat stat, int mask) throws IOException; // mask=CephMount.SETATTR_*，时间毫秒
  void chmod(Path path, int mode) throws IOException;
  // 数据
  int open(Path path, int flags, int mode) throws IOException;      // flags=CephMount.O_*
  int open(Path path, int flags, int mode, int stripeUnit, int stripeCount,
           int objectSize, String dataPool) throws IOException;
  long lseek(int fd, long offset, int whence) throws IOException;   // whence=CephMount.SEEK_*
  long read(int fd, byte[] buf, long size, long offset) throws IOException;   // 返回 long；offset=-1 当前位置；0=EOF
  long write(int fd, byte[] buf, long size, long offset) throws IOException;  // 返回 long；offset=-1 当前位置
  void fstat(int fd, CephStat stat) throws IOException;
  void close(int fd) throws IOException;
  void flush(int fd) throws IOException;                            // = CephMount.fsync(fd, false)
  void ftruncate(int fd, long size) throws IOException;
  // 数据局部性
  CephFileExtent getFileExtent(int fd, long offset) throws IOException;
  InetAddress getOsdAddress(int osd) throws IOException;
  ```

- 与设计文档的偏差（架构文档 §3.1 草案 → 实际，均已按"以源码为准"条款修正）：
  1. `read`/`write` 返回类型 `int` → **`long`**（`CephMount.read/write` 返回 long）。
  2. `listdir` "非目录返回 null"：JNI 实际对 ENOTDIR 抛 `CephNotDirectoryException`
     （libcephfs_jni.cc 中注释与行为不符），CephTalker 捕获后转为返回 null，
     对上层维持 §3.1 契约不变。注意该异常未出现在 `CephMount.listdir` 的 throws
     声明中（native 方法可抛未声明检查异常），实现里须 catch IOException 再判类型。
  3. 补充明确（非签名变化）：错误映射 ENOENT→FileNotFoundException、
     EEXIST→CephFileAlreadyExistsException、ENOTDIR→CephNotDirectoryException、
     其余 errno→IOException(strerror)；未挂载调用→CephNotMountedException；
     CephStat 的 a_time/m_time 单位为毫秒（JNI 已换算，架构文档 §4-8 的
     纳秒→毫秒截断在 JNI 层完成，T03 无需再除以 1e6）。
- 遗留/移交事项（给 T03）：
  - 直接以 `new CephTalker()` 作为 `CephFsProto` 生产实现；单测用 Mockito mock
    `CephFsProto`（包可见抽象类，测试须在 `org.apache.hadoop.fs.ceph` 包内）。
  - `mkdirs` 已存在语义（`Client::mkdirs` 源码 + ITestCephTalker.testMkdirsExistingSemantics
    实测钉死）：已存在目录 no-op 成功；<b>末段为已存在文件也静默成功</b>（不创建不报错）；
    仅中间级为文件时抛 CephNotDirectoryException。因此 Hadoop `mkdirs` 的
    "已存在同名文件应失败 / 已存在目录返回 true" 判定必须由 CephFileSystem 层
    自行 lstat 完成，不能依赖 proto.mkdirs 报错。
  - open/lseek/setattr 的 flags/whence/mask 常量直接用 `CephMount.O_*` / `SEEK_*` /
    `SETATTR_*`（libcephfs 私有编码，勿用 POSIX 数值）。
  - 路径转换唯一入口 `CephTalker.pathString`（包可见 static，仅测试直呼；相对路径按根解析，
    workingDir 语义按架构文档 §4-7 在 CephFileSystem 纯 Java 维护）。
  - pom 中 JNI 路径默认取本机 ENV.md 值，非默认环境用
    `mvn -Dceph.lib.dir=... -Dceph.jni.dir=...` 覆盖。
- 环境变更：
  - 本地 Maven 仓库新增 hadoop-common:3.3.6（含 test-jar）、junit 4.13.2、
    mockito-core 3.12.4、surefire/failsafe 3.0.0-M5 及传递依赖。
  - 集群未做变更（测试目录均已清理；当前仍运行中）。

---

## T03 CephFileSystem 元数据操作 — DONE（2026-07-05）

- 验收结果：
  1. ✅ 无集群 `mvn clean test`（`hadoop-cephfs/`，未设 CEPH_CONTRACT_TEST）：
     `Tests run: 69, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。
     T03 新增 mock 单测 54 例（TestCephFileSystemMeta 28 + Mutations 12 + Rename 14），
     覆盖架构文档 §4 表 1（rename 全分支矩阵 14 例）/表 2（delete 全分支 8 例，
     含根目录保护与后序遍历 InOrder 验证）/表 3（异常映射：FileAlreadyExists、
     ParentNotDirectory、PathIsNotEmptyDirectory、FNF）/表 7（workingDir 相对路径
     解析）以及表 4（setReplication→false）/表 6（uid→用户名映射）/表 8（时间戳
     毫秒直传）。另验证无门控 `mvn verify` 时 ITest 全 skip（11 skipped）仍绿。
  2. ✅ 集群运行中（HEALTH_WARN 单 OSD 正常告警、mds 1/1 up）执行
     `CEPH_CONTRACT_TEST=1 mvn verify -Dit.test=ITestCephFileSystemMeta`：
     failsafe `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS
     （真实集群 mkdirs/getFileStatus/listStatus/rename 三分支/delete 三分支/
     workingDir/setPermission/setTimes 全走通）。
  3. ✅ ServiceLoader 发现：ITestCephFileSystemMeta.testServiceLoaderDiscovery 中
     `FileSystem.get(URI.create("ceph:///"), conf)`（未设 fs.ceph.impl）返回
     CephFileSystem 实例，scheme/uri 断言通过。
  4. ✅ 数据流方法 open/create/append 为显式留桩（仅抛
     UnsupportedOperationException，无半成品逻辑），单测
     testDataMethodsAreExplicitStubs 钉死。
- 交付物清单：
  - `hadoop-cephfs/src/main/java/org/apache/hadoop/fs/ceph/CephFileSystem.java`
  - `hadoop-cephfs/src/main/resources/META-INF/services/org.apache.hadoop.fs.FileSystem`
  - `hadoop-cephfs/src/main/java/org/apache/hadoop/fs/ceph/CephConfigKeys.java`
    （增补 ceph.replication，见偏差 1）
  - 测试：`CephFsTestHelper.java`（CephStat 反射构造/lstat mock 助手，非测试类）、
    `TestCephFileSystemMeta.java`、`TestCephFileSystemMetaMutations.java`、
    `TestCephFileSystemMetaRename.java`、`ITestCephFileSystemMeta.java`
  - `docs/00-顶层架构设计.md` §5 表新增 ceph.replication 行（同步更新条目）
- 与设计文档的偏差：
  1. 新增配置键 `ceph.replication`（默认 3）：任务书要求 getDefaultReplication
     "读配置，缺省 3"，但 §5 冻结表无对应键；按协作规范 §2 做最小增补
     （CephConfigKeys 只增不改）并同步更新架构文档 §5。该值仅用于
     FileStatus/getDefaultReplication 报告，不影响实际存储（§4-4）。
  2. §4-6 细化：uid 与当前进程 uid（读 /proc/self/status，失败为 -1）相同→
     ugi.shortUserName，否则数字字符串；gid 一律数字字符串（架构文档只约定
     uid 映射，组名无法在"不做 NSS 查询"前提下取得）。
  3. delete 根目录：!recursive → false（任务书保护条款）；recursive=true →
     清空全部子项但保留根本身、返回 true（filesystem.md 允许的 POSIX 模型）。
  4. rename 在 §4-1 三分支之外补充的分支（均按 HDFS/filesystem.md 惯例，
     单测钉死）：dst 目录下同名条目已存在→false；dst 不存在且父路径缺失或为
     文件→false；目录移入自身子树→false；src 为根→false；rename 到自身所在
     目录（dst=src 父目录）→true no-op。
  5. mkdirs 对入参权限应用 fs.permissions.umask-mode（默认 022），与 HDFS
     客户端行为一致。
- 遗留/移交事项（给 T04）：
  - `CephFileSystem.proto` 为 private final，经包可见构造器
    `CephFileSystem(CephFsProto)` 注入；T04 在同类内实现 open/create/append，
    直接使用 proto 字段与私有 helper `mapCephException`（errno→Hadoop 异常，
    集中一处，勿另起炉灶）、`makeAbsolute`（相对路径解析，所有 public 方法
    入口必须先调用）。
  - create 需自行完成元数据前置检查（proto 层不会代劳）：overwrite=false 且
    目标存在→FileAlreadyExistsException；目标为目录→FileAlreadyExistsException；
    父目录缺失→mkdirs（父为文件时 mkdirs 已会抛 ParentNotDirectoryException）。
    blockSize 参数按 §4-5 映射到 7 参 open 的 objectSize（stripe 参数传 0 用默认），
    data pool 取 CEPH_DATA_POOLS_KEY 第一个；O_* 常量一律 CephMount.*。
  - getDefaultBlockSize() 已实现（读 ceph.object.size）；
    getFileStatus 的 blockSize 用 lstat 的 st_blksize（>0 时），T04/T05 如需
    更精确的 layout 值可换 get_file_extent/ceph_get_layout 途径再议。
  - FileSystem.statistics（读写字节/操作计数）尚未接入，T04 在流中递增。
  - 单测助手 `CephFsTestHelper`（反射构造 CephStat、mockLstat/mockLstatMissing、
    newFs）可直接复用；CephStat 的 is_file/is_directory 为 native 填充的私有
    字段，mock 场景必须经该助手构造。
  - ITestCephFileSystemMeta 因 create 未实现，借助第二个 CephTalker mount 造
    测试文件；T04 完成后可（可选）改用 fs.create 简化。
  - close() 幂等由 AtomicBoolean 保证，且先 super.close()（处理 deleteOnExit）
    再 proto.shutdown()；T04 流的 close 与 fs.close 互不代替。
- 环境变更：无（集群未变更，测试目录均已清理；本地 Maven 仓库无新增）。

---

## T04 数据读写流实现 — DONE（2026-07-05）

- 验收结果：
  1. ✅ 无集群 `mvn clean test`（`hadoop-cephfs/`，未设 `CEPH_CONTRACT_TEST`）：
     surefire `Tests run: 95, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。
     新增/更新 mock 覆盖：`TestCephInputStream` 7 例（缓冲读、EOF=-1、缓冲内/外 seek、
     positioned read 不移动当前位置、available、关闭后读/seek 抛 IOException）；
     `TestCephOutputStream` 7 例（跨缓冲写、hflush/hsync→proto.flush、close 幂等且失败仍关 fd、
     capability、关闭后写失败）；`TestCephFileSystemIO` 13 例（open 目录→FNF、create 分支矩阵、
     createNonRecursive、layout/data pool、append 初始位置与异常分支）。
  2. ✅ 集群运行中执行 `CEPH_CONTRACT_TEST=1 mvn verify -Dit.test=ITestCephIO`
     （需非网络隔离环境访问 vstart Ceph）：failsafe `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`，
     BUILD SUCCESS。覆盖 200MB 随机文件写入、顺序 md5 回读一致、100 次随机 pread 与本地副本一致、
     append 内容/长度正确、`hflush` 后第二个 FileSystem 实例可见。
  3. ✅ create/append/open 异常分支按 Hadoop 规范单测钉死：已存在且 `!overwrite`、已存在目录、
     父目录缺失递归创建、父路径为文件、`createNonRecursive` 父缺失、append/open 不存在或目录。
  4. ✅ 无资源泄漏：`CephTalker` 增加包内 fd 计数器，`ITestCephIO` 在每次流关闭后断言
     `openFileDescriptorCountForTests()==0`。
- 交付物清单：
  - `hadoop-cephfs/src/main/java/org/apache/hadoop/fs/ceph/CephInputStream.java`
  - `hadoop-cephfs/src/main/java/org/apache/hadoop/fs/ceph/CephOutputStream.java`
  - `hadoop-cephfs/src/main/java/org/apache/hadoop/fs/ceph/CephFileSystem.java`
    （补全 `open/create/createNonRecursive/append`）
  - `hadoop-cephfs/src/main/java/org/apache/hadoop/fs/ceph/CephTalker.java`
    （包内 fd 计数器，仅测试断言使用）
  - `hadoop-cephfs/src/test/java/org/apache/hadoop/fs/ceph/TestCephInputStream.java`
  - `hadoop-cephfs/src/test/java/org/apache/hadoop/fs/ceph/TestCephOutputStream.java`
  - `hadoop-cephfs/src/test/java/org/apache/hadoop/fs/ceph/TestCephFileSystemIO.java`
  - `hadoop-cephfs/src/test/java/org/apache/hadoop/fs/ceph/ITestCephIO.java`
  - `hadoop-cephfs/src/test/java/org/apache/hadoop/fs/ceph/TestCephFileSystemMeta.java`
    （移除 T03 留桩断言）
- 与设计文档的偏差：无。实现补充：为避免 `FSDataOutputStream` 与 `CephOutputStream`
  双重统计写字节，`CephFileSystem` 包装输出流时向 `FSDataOutputStream` 传 `stats=null`，
  由 `CephOutputStream` 自行递增 `FileSystem.statistics`；输入流直接递增读字节。
- 遗留/移交事项（给 T05）：
  - `getFileBlockLocations` 仍未实现，保持 T05 边界；T05 可按任务书使用 `proto.getFileExtent`
    / `getOsdAddress`，必要时临时 `O_RDONLY` 打开文件并确保关闭 fd。
  - `CephInputStream` 的普通读和 positioned read 均使用 `proto.read(fd, ..., offset)` 显式偏移，
    不依赖 native fd 当前位置；`seek` 仅维护 Java 侧位置和缓冲状态。
  - `CephTalker.openFileDescriptorCount()` 与 `CephFileSystem.openFileDescriptorCountForTests()`
    为包内测试辅助，不属于公共接口。
  - `ITestCephFileSystemMeta` 仍保留 T03 的辅助 `CephTalker` 造文件方式；T05/T06 如需可再用
    `fs.create` 简化，T04 未做非必要改动。
- 环境变更：无（集成测试目录均已清理；vstart 集群状态未调整）。注意真实集群测试不能在
  Codex 默认网络隔离 sandbox 内运行，需非网络隔离执行。
