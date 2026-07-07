# 项目进度登记

> 每个 agent 完成（或阻塞）时按 `docs/01-协作规范.md` §3 的模板在此追加一节。

| 任务 | 状态 | 完成日期 | 备注 |
|---|---|---|---|
| T01 环境验证与 Java 绑定构建 | DONE | 2026-07-05 | smoke test 全链路通过；产物见 dist/native 与本地 Maven 仓库 |
| T02 Maven 骨架与抽象层 | DONE | 2026-07-05 | mvn test 15/15 绿色；ITestCephTalker 6/6 通过；CephFsProto 签名已冻结（见 T02 节） |
| T03 元数据操作 | DONE | 2026-07-05 | mvn clean test 69/69 绿；ITestCephFileSystemMeta 5/5 通过（含 ServiceLoader 发现验证）；open/create/append 留桩待 T04 |
| T04 数据读写流 | DONE | 2026-07-05 | mvn clean test 95/95 绿；ITestCephIO 3/3 通过（200MB md5/pread/append/hflush/fd 计数） |
| T05 BlockLocation 与 AbstractFileSystem | DONE | 2026-07-05 | mvn clean test 120/120 绿；ITestCephBlockLocation 4/4、ITestCephFileContext 4/4 通过；host 与 ceph osd dump 一致 |
| T06 契约测试与集成验证 | DONE | 2026-07-07 | 契约 116/116 通过（仅 1 例按根目录保护口径 override）；e2e-cli-test.sh 退出码 0（200MB md5 往返一致）；并发冒烟通过；无集群 mvn clean test 122/122 绿 |
| T07 打包部署与文档 | DONE | 2026-07-07 | make-dist.sh 一键产出 v1.0.0 tar.gz；干净 shell 按 DEPLOY.md 终验通过；打包后回归门禁 140 例 + e2e 全绿；git tag v1.0.0 |

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

### T04 独立验收（2026-07-05，验收 agent）— 通过

- 验收标准逐条复核（均 ✅）：
  1. 无集群 `mvn clean test`：`Tests run: 95, Failures: 0, Errors: 0, Skipped: 0`，
     BUILD SUCCESS；另跑无门控 `mvn verify`：failsafe `Tests run: 14, Skipped: 14`
     （ITestCephTalker 6 + ITestCephFileSystemMeta 5 + ITestCephIO 3 全部正确 skip），仍绿。
  2. 集群健康（HEALTH_WARN 单 OSD 正常告警、mds 1/1 up）下
     `CEPH_CONTRACT_TEST=1 mvn verify -Dit.test=ITestCephIO`：3/3 通过
     （200MB md5、100 次随机 pread、append、hflush 跨实例可见）；验收补充用例后复跑 4/4 通过。
  3. 异常分支与 filesystem.md 逐条比对一致（create !overwrite→FileAlreadyExists、
     create 目标为目录→FileAlreadyExists、父为文件→ParentNotDirectory、
     createNonRecursive 父缺失→FNF、append/open 不存在或目录→FNF，均为规范允许集合内的选择）。
  4. fd 泄漏断言存在（CephTalker.openFileDescriptorCount，仅增量式改动、未动冻结签名）
     且 ITestCephIO 每次流关闭后断言归零，通过。
- 代码走查确认：seek 缓冲命中/失效正确、close 幂等（AtomicBoolean）、关闭后读写抛
  IOException、EOF 返回 -1；hflush/hsync 刷缓冲后 proto.flush（InOrder 单测钉死）、
  StreamCapabilities 正确；create 的 O_* 全部用 CephMount 常量、blockSize→objectSize +
  stripe_count=1 下发、data pool 取第一项；statistics 读写字节接入且无双重计数；
  git diff b65f951..HEAD 中元数据方法零改动（仅移除 T03 留桩），CephFsProto/CephConfigKeys
  未被触碰。
- 验收发现并补齐的小问题（功能本身正确）：
  - ITestCephIO 原 3 例的 create 均为 overwrite=false，O_TRUNC 覆盖已存在文件
    （7 参 layout open 打开已存在文件）分支未在真实集群验证过。已先以独立驱动程序在
    真实集群抽查通过（3MB→截断为 11 字节，内容一致），并补充集成用例
    `testCreateOverwriteTruncatesExisting`，复跑 `CEPH_CONTRACT_TEST=1 mvn verify
    -Dit.test=ITestCephIO` 4/4 通过。
- 观察项（非缺陷，移交 T06 契约测试关注）：
  - create 的 stripe_unit=min(blockSize, ceph.object.size)，若用户传入的 blockSize
    不是 stripe_unit 的整数倍（如非 2 幂的 100MB），Ceph 会以 EINVAL 拒绝 layout，
    表现为 IOException（规范允许拒绝非法块大小，但报错信息较隐晦）。
- 结论：T04 验收通过，T05 可以启动。

---

## T05 BlockLocation 与 AbstractFileSystem — DONE（2026-07-05）

- 验收结果：
  1. ✅ 无集群 `mvn clean test`（`hadoop-cephfs/`，未设 CEPH_CONTRACT_TEST）：
     `Tests run: 120, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。
     T05 新增 mock/结构单测 25 例：TestCephBlockLocation 16（基类契约分支 6 +
     切分正确性 5 + 降级路径 5，含跨对象边界/start 非对齐/len 超文件尾/
     Long.MAX_VALUE 不溢出/OSD 解析失败降级 localhost/getFileExtent 失败按
     blockSize 步进降级/混合失败仅降级失败 extent/异常传播时 finally 关 fd）、
     TestCephFileSystemStatus 6（statfs→FsStatus 映射、frsize=0 回退 bsize、
     getDefaultBlockSize(Path)、getServerDefaults 双重载、getContentSummary
     基类默认实现汇总正确）、TestCephFs 3（DelegateToFileSystem 继承、
     (URI,Configuration) 构造器、默认端口 6789）。另验证无门控 `mvn verify`
     failsafe `Tests run: 23, Skipped: 23` 仍绿。
  2. ✅ 集群运行中（smoke-test.sh 先行通过）执行
     `CEPH_CONTRACT_TEST=1 mvn verify -Dit.test=ITestCephBlockLocation,ITestCephFileContext`：
     failsafe `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。
     3×objectSize（4MB×3）文件 BlockLocation 数量=3、offset/length 逐块正确、
     host 非空且非降级值；实测 hosts=[10.255.255.254]，与
     `ceph osd dump` 中 osd.0 地址 `v2:10.255.255.254:6800/...` 一致（vstart 单机）。
     另复跑全部门控 IT：23/23 通过（ITestCephTalker 6 + Meta 5 + IO 4 +
     BlockLocation 4 + FileContext 4），T02–T04 行为无回归。
  3. ✅ FileContext 路径可用：`FileContext.getFileContext(URI.create("ceph:///"), conf)`
     全链路 mkdir/create/open/rename/delete/listStatus/getFsStatus 走通
     （ITestCephFileContext.testFileContextFullRoundTrip）；且负向用例证明
     `fs.AbstractFileSystem.ceph.impl` 即生效点（不配置该键 →
     UnsupportedFileSystemException）。
  4. ✅ `getStatus()`：实测 capacity=106287857664 B（≈99 GiB）、used=0、
     remaining=106287857664 B；与 `ceph df` 同量级（RAW TOTAL 101 GiB、
     MAX AVAIL 99 GiB；数据池仅存 21 B，按 4MB frsize 取整后 used 为 0，合理）。
- 交付物清单：
  - `hadoop-cephfs/src/main/java/org/apache/hadoop/fs/ceph/CephFileSystem.java`
    （新增 getFileBlockLocations、getDefaultBlockSize(Path)、getServerDefaults×2、
    getStatus(Path)；元数据/流方法零改动）
  - `hadoop-cephfs/src/main/java/org/apache/hadoop/fs/ceph/CephFs.java`
  - 测试：`TestCephBlockLocation.java`、`TestCephFileSystemStatus.java`、
    `TestCephFs.java`、`ITestCephBlockLocation.java`、`ITestCephFileContext.java`、
    `CephFsTestHelper.java`（增补 CephFileExtent 反射构造 helper）
- 与设计文档的偏差：无（实现口径补充，均在架构文档许可范围内）：
  1. BlockLocation 的 hosts/names 用 OSD 地址的 IP 文本（InetAddress.getHostAddress，
     不做反向 DNS），与 `ceph osd dump` 直接可比；names 无端口
     （CephMount.get_osd_address 仅返回 InetAddress，无 OSD 端口）。
  2. 降级细化：getOsdAddress 失败/OSD 列表为空 → 该 extent hosts 降级
     ["localhost"]（长度仍按 extent）；getFileExtent 本身失败 → 该段按
     FileStatus.blockSize 步进且 hosts 降级；均 warn 不抛，与任务书
     "不让作业提交失败"一致。temp fd 以 finally 兜底关闭（含 RuntimeException 路径）。
  3. getServerDefaults 的 checksumType 报 DataChecksum.Type.NULL
     （CephFS 无客户端校验和，完整性由 RADOS 负责）；bytesPerChecksum 沿用
     基类同源的 "io.bytes.per.checksum"（Hadoop 无公开常量）。
  4. getStatus 的 used = capacity - remaining（CephStatVFS 无 bfree 字段，
     JNI 未透传 f_bfree）；p=null 时对根 "/" statfs（CephFS 单一分区）。
  5. CephFs 构造器为 public（AbstractFileSystem 反射 setAccessible 本可用
     包可见，公开便于直接引用；RawLocalFs 因同包才用包可见）。
- 遗留/移交事项（给 T06）：
  - 契约测试 fs.contract 声明可参考：rename 语义 §4-1、不支持 concat/truncate、
    setReplication 返回 false；T04 观察项（blockSize 非 stripe_unit 整数倍 →
    EINVAL IOException）仍待契约测试确认口径。
  - FileContext/AbstractFileSystem 无 close 钩子：CephFs 内部的 CephFileSystem
    mount 生命周期与 JVM 相同（FileContext 缓存），vstart 下无害；
    契约测试若并发创建大量 FileContext 需留意 mount 数。
  - `fs.AbstractFileSystem.ceph.impl` 需用户显式配置（AbstractFileSystem 无
    ServiceLoader 机制），T07 部署文档必须写入 core-site.xml 样例。
  - getFileBlockLocations 的 topologyPaths 留空（无 crush 机架感知，任务书边界），
    后续迭代可用 CephMount.get_osd_crush_location 补齐。
  - CLI `hadoop fs -stat` / `df` 走 getStatus，已可用；`getContentSummary`
    为基类默认实现（Java 侧递归），超大目录树性能一般，暂无优化必要。
- 环境变更：无（集成测试目录均已清理；vstart 集群未调整，仍运行中）。

---

## T06 契约测试与集成验证 — DONE（2026-07-07）

> 注：本任务由两个 agent 接力完成（前一个 agent 中途被终止，已完成契约声明/
> 测试类骨架与部分语义修复；接续 agent 盘点半成品后续作收口，未从零重做）。

- 验收结果：
  1. ✅ `CEPH_CONTRACT_TEST=1 mvn verify -Dit.test='ITestCephContract*'`：
     failsafe `Tests run: 116, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。
     排除/override 仅 1 例（远小于 ≤5 类上限），书面理由见下表与代码注释。
     另全量门控 `CEPH_CONTRACT_TEST=1 mvn verify`（T02–T05 的 23 例 + T06 并发 1 例 +
     契约 116 例 = 140 例）连续 3 轮全绿（`Tests run: 140, Failures: 0, Errors: 0,
     Skipped: 0`），T02–T05 行为无回归。
  2. ✅ `scripts/e2e-cli-test.sh` 退出码 0：mkdir -p / test / put(200MB+小文件+
     已存在失败) / ls(含长度断言) / stat / df / cat / get / cp / mv / rm -r /
     负向 rm 共 21 项逐条断言退出码全 PASS；200MB put/get md5 往返一致。
  3. ✅ 并发冒烟 `CEPH_CONTRACT_TEST=1 mvn verify -Dit.test=ITestCephConcurrentIO`：
     1/1 通过（4 线程共享同一 CephFileSystem/mount，各写读 8MB 独立文件，
     顺序 md5 + 随机 pread 比对一致，300s 超时兜底无死锁，结束后 fd 计数归零）。
  4. ✅ 无集群 `mvn clean test`：`Tests run: 122, Failures: 0, Errors: 0, Skipped: 0`，
     BUILD SUCCESS（T05 基线 120 + 本任务新增 testNormalizeLayoutSize、
     testCreateNonRecursiveFlagsOverload）。无门控 `mvn clean verify`：
     failsafe `Tests run: 140, Skipped: 140` 全部正确 skip，BUILD SUCCESS。

- 契约测试结果汇总表（`CEPH_CONTRACT_TEST=1`，vstart 真实集群）：

  | 套件 | 用例数 | 结果 | 排除/override 及理由 |
  |---|---|---|---|
  | ITestCephContractRootDirectory | 9 | 通过 | 1 例 override：`testRmNonEmptyRootDirNonRecursive` —— 基类期望非空根 `delete("/", false)` 抛 IOException；本实现按 T03 已验收的根目录保护条款一律返回 false 且不触碰数据（filesystem.md 允许对根特殊处理）。override 改为断言既定语义：返回 false、根仍在、数据不丢。按协作规范不为契约测试改动已验收行为 |
  | ITestCephContractMkdir | 8 | 通过 | 无 |
  | ITestCephContractCreate | 16 | 通过 | 无 |
  | ITestCephContractAppend | 8 | 通过 | 无 |
  | ITestCephContractDelete | 8 | 通过 | 无 |
  | ITestCephContractSeek | 18 | 通过 | 无 |
  | ITestCephContractRename | 10 | 通过 | 无 |
  | ITestCephContractOpen | 19 | 通过 | 无 |
  | ITestCephContractGetFileStatus | 20 | 通过 | 无 |
  | 合计 | 116 | 116 通过 / 0 失败 / 0 skip | 仅上述 1 例 override |

  语义均由 `src/test/resources/contract/ceph.xml` 逐项声明（每项附实测依据注释）：
  strict-exceptions、unix-permissions、rename 全套（overwrites-dest=false、
  returns-false-if-source-missing/dest-exists=true、creates-dest-dirs=false、
  atomic-rename=true）、atomic-directory-delete=false、supports-append/seek/
  positioned-readable/hflush/hsync=true、rejects-seek-past-eof=true、
  supports-concat=false、create-overwrites-directory=false、
  create-file-under-file-allowed=false、supports-block-locality=true、
  root-tests-enabled=true（vstart 数据可丢弃）。

- 契约暴露并修复的语义缺口（修复以满足契约为限，全部有 mock 单测或契约用例钉死）：
  1. **createNonRecursive 的 `EnumSet<CreateFlag>` 重载缺失**：FileSystem 基类该
     重载默认抛 "createNonRecursive unsupported"，而 `createFile(path)` 建造器
     （契约 Create/Open 套件、HBase WAL 等下游）走的正是它，致 6 例 ERROR。
     补 override 映射 OVERWRITE→overwrite 委托 createInternal（与 RawLocalFileSystem
     同法）；新增单测 testCreateNonRecursiveFlagsOverload 钉死 O_EXCL/O_TRUNC 分支。
  2. **祖先为普通文件的路径解析归一化**（新增私有 helper `lstatResolved`）：
     CephFS 客户端对经过普通文件的路径 lstat 返回 EACCES（文件无 x 位，JNI 表现为
     裸 IOException("Permission denied")）或 ENOTDIR，契约要求 Hadoop 语义。
     归一化口径：变更语境（mkdirs/create）→ ParentNotDirectoryException；查询语境
     （getFileStatus/open/append/delete/rename）→ FileNotFoundException（delete/
     rename 入口继而按既有分支返回 false）。真正的目录权限不足不受影响
     （祖先探测判定为目录时原样抛出）。
  3. **blockSize → Ceph layout 归一化**（T04/T05 观察项定稿，见下"与设计文档的偏差"）。
  4. **hasPathCapability 能力声明**：契约 Append 套件要求
     `fs.capability.paths.append` 声明；补 override 声明 FS_APPEND 与
     FS_PERMISSIONS（均为 CephFS 原生支持、T03/T04 实测），其余沿用基类默认 false。

- 与设计文档的偏差：
  1. **§4-5 blockSize→layout 映射口径变更（唯一一处改动 T04 已验收行为）**：
     原口径 objectSize=blockSize、stripe_unit=min(blockSize, ceph.object.size)，
     任意 blockSize 直传 Ceph，非 64KB 整数倍即 EINVAL（T04 独立验收观察项）。
     契约测试的 writeDataset 会传 1024 等任意 blockSize，直接击中该缺口。
     定稿口径：按 "blockSize 是提示值" 的 Hadoop 惯例向上归一化 ——
     objectSize=stripe_unit=roundUp(blockSize, 64KB)（Ceph file_layout_t::is_valid
     的 CEPH_MIN_STRIPE_UNIT 约束），stripe_count=1，并钳制到 int 可表示的最大
     64KB 整数倍。影响面：仅 create/createNonRecursive 下发 layout 的取值；
     64KB 整数倍的 blockSize（含默认 64MB、非 2 幂的 100MB）行为不变；
     mock 单测（testNormalizeLayoutSize 等）与真实集群契约用例
     （AbstractContractCreateTest 以 blockSize=1024 创建，实测通过）双重钉死。
     T02 冻结接口未动。
  2. **CLI 端到端未用 Hadoop 发行版**：磁盘无 Hadoop 3.3.6 可执行发行版
     （hadoop-dist 未构建、无 tarball），按任务书"最省事可行"条款采用
     `java org.apache.hadoop.fs.FsShell` 直跑（`hadoop fs` 包装脚本最终 exec 的
     就是它），classpath 由 `mvn dependency:build-classpath` 从本地仓库解析
     hadoop-common:3.3.6 全部传递依赖组装，另生成最小 core-site.xml
     （FsShell quietMode=false 要求 classpath 上真实存在）。与发行版的差异仅
     classpath 来源，命令解析/FS 调用路径完全相同。脚本头注释有完整说明。
  3. 契约测试主体绑定为 `AbstractBondedFSContract`（任务书两个选项之一），
     门控实现：未设 CEPH_CONTRACT_TEST=1 时不注入 `fs.contract.test.fs.ceph`，
     AbstractBondedFSContract 自动置 disabled → 全部 skip。

- 遗留/移交事项（给 T07）：
  - 部署文档的 core-site.xml 样例可直接取 `scripts/e2e-cli-test.sh` 生成的
    `target/e2e-conf/core-site.xml` 模板（fs.defaultFS、fs.ceph.impl、
    fs.AbstractFileSystem.ceph.impl、ceph.conf.file、ceph.auth.id 五项）。
  - 运行时部署三件套：连接器 jar + `dist/native/libcephfs.jar` +
    `dist/native/libcephfs_jni.so`；JVM 须同时设 `-Djava.library.path` 与
    `LD_LIBRARY_PATH`（ENV.md §3）。发行版环境等价做法：三者入
    `$HADOOP_HOME/share/hadoop/common/lib` + jni so 入 native lib 目录。
  - lockdep 仅存在于调试构建的 ceph.conf（vstart -d），生产 ceph.conf 无此项，
    部署文档无需提及；但若用户拿调试构建自测，可参考 pom 注释用
    `CEPH_ARGS=--lockdep=false` 规避。
  - 契约套件 + e2e-cli-test.sh 可作为 T07 打包产物的回归门禁复用。
- 环境变更：
  - 本地 Maven 仓库新增 assertj-core:3.12.2（契约基类编译期依赖，版本与
    hadoop-3.3.6 hadoop-project 对齐）。未下载/安装 Hadoop 发行版（见偏差 2）。
  - pom failsafe 与 e2e 脚本对测试客户端进程设 `CEPH_ARGS=--lockdep=false`：
    vstart 调试构建 ceph.conf 含 lockdep=true（Ceph 开发者锁序调试器），
    mount 引导期偶发在 libceph-common 内部（AsyncMessenger::shutdown_connections
    ← MonClient::get_monmap_and_config）误报锁环并 ceph_abort 拉崩测试 JVM
    （复现 3 次，崩溃栈完全在 Ceph 内部，与连接器无关）。仅影响连接器测试
    客户端进程，集群守护进程配置未动。
  - ITestCephContractRootDirectory 会清空 CephFS 根目录（vstart 测试集群数据
    可丢弃，ceph.xml root-tests-enabled=true 前提）；本轮结束后根目录已为空，
    集群仍运行中（HEALTH_WARN 单 OSD 正常告警）。

---

## T07 打包、部署与使用文档 — DONE（2026-07-07）

- 验收结果（任务书 4 条逐条）：
  1. ✅ `scripts/make-dist.sh` 从干净 `mvn clean` 状态一键成功：先 `mvn -q clean`
     确认 target 清空，再执行脚本，退出码 0；内部 `mvn clean package` 含全部无集群
     单测（surefire 汇总 122/122，0 失败 0 跳过），产出
     `dist/hadoop-cephfs-1.0.0.tar.gz`（sha256=1aa5b4e7d47d…0480d9），内容为
     `hadoop-cephfs-1.0.0/{hadoop-cephfs-1.0.0.jar, libcephfs.jar, libcephfs_jni.so,
     conf/core-site.xml.example, README.md, docs/DEPLOY.md}`。
     libcephfs.jar 不合入（shade）连接器 jar、并列发布，理由（脚本头注释与
     DEPLOY.md §2.1）：LGPL-2.1/Apache-2.0 许可隔离；jar 与 jni so 同源成对、
     须随 Ceph 客户端整体升级；避免与节点已有 libcephfs.jar 类冲突。
  2. ✅ 终验（干净 shell、只按文档操作）全部命令成功：`scripts/t07-final-verify.sh`
     以 `env -i bash --noprofile --norc`（环境仅 HOME/PATH/PWD 等 6 项，不继承会话
     env）从 tar.gz 出发，仅按 DEPLOY.md §2.1/§3/§2.3-A/§5.2 操作，
     ls/put/cat/rm ceph:/// 全流程通过，退出码 0。命令与输出记录见下。
  3. ✅ DEPLOY.md 中每条命令均实测有效：验证序列（§5.2）即终验所跑；cephx 授权
     命令（§4 `ceph fs authorize a client.hadoop / rwp`、`ceph auth get`）在 vstart
     实测并贴实测 caps 输出；排查表症状文本全部为本轮真实复现的原文（见 4）；
     `ldd` 自检、`CEPH_JNI_PATH`、`ceph.conf.options=lockdep=false` 均实测。
     无 Hadoop 发行版，`hadoop fs` 以 `java org.apache.hadoop.fs.FsShell` 等效实测
     （与 T06 CLI 方案一致，DEPLOY.md 开头有书面口径说明）。
  4. ✅ 排查表覆盖 6 类，全部真实复现（2026-07-07，复现方法与原文记录）：
     ① UnsatisfiedLinkError（`java.library.path` 指空目录复现；实测症状为加载器
       fallback 后的 `Can't load library: /usr/lib/jni/libcephfs_jni.so`，比 ENV.md §3
       记录的教科书文本更真实——CephNativeLoader 会吞掉首次失败）；同症状第二根因
       （依赖 so 缺失）用二进制等长改写 RUNPATH 的 so 副本复现，`ldd` 显示
       `libcephfs.so.2 => not found`；
     ② mount 超时：fs.defaultFS 指向不可达 mon `ceph://192.0.2.1:6789/`，
       约 50s 后 `ls: Connection timed out`（rc=1）；
     ③ 认证失败：`ceph.auth.id=nosuch`（不存在的用户）→ 挂载期 `ls: Permission denied`；
     ④ mds caps 缺 `p` 位：`ceph fs authorize a client.hadoop / rw` 用户
       `-mkdir` 成功但 `-put` 报 `put: Permission denied`（原因：连接器 create 恒以
       7 参 layout open 下发 object size，MDS 要求 `p`＝set layout 能力；改授
       `/ rwp` 后同一操作序列 put/cat/rm 全通过，已实测钉死）；
     ⑤ 连接器 jar 不在 classpath：`-ls: Fatal internal error` +
       `ClassNotFoundException: Class org.apache.hadoop.fs.ceph.CephFileSystem not found`；
     ⑥ `fs.AbstractFileSystem.ceph.impl` 未配 → `UnsupportedFileSystemException`
       （T05 ITestCephFileContext 负向用例实测）。另附调试构建集群 lockdep 误报条目
       （T06 复现 3 次；本轮实测 `ceph.conf.options=lockdep=false` 配置法有效）。

- 打包后回归门禁（T06 交接的门禁复用，均在 make-dist 产物构建后执行）：
  - `CEPH_CONTRACT_TEST=1 mvn verify`：failsafe 汇总 `Tests run: 140, Failures: 0,
    Errors: 0, Skipped: 0`，BUILD SUCCESS（契约 116 + 集成 24）；
  - `scripts/e2e-cli-test.sh`：退出码 0，21 项断言全 PASS，200MB md5 往返一致。

- 终验命令与输出记录（`scripts/t07-final-verify.sh` 实录，SLF4J/log4j 噪音行略）：

  ```
  == 干净 shell 环境（env 全量）==
  HOME=/root  PATH=/usr/local/sbin:...:/bin  PROJ_ROOT=...  PWD=...  SHLVL=0  TARBALL=.../dist/hadoop-cephfs-1.0.0.tar.gz
  == DEPLOY.md §2.1 解包 ==            # tar xzf dist/hadoop-cephfs-1.0.0.tar.gz
  hadoop-cephfs-1.0.0.jar / libcephfs.jar / libcephfs_jni.so / conf/ / docs/ / README.md
  == DEPLOY.md §3 core-site.xml ==     # 5 必配项(vstart 值) + lockdep=false(§6 调试构建条目)
  == DEPLOY.md §5.2-1 组装 classpath == # mvn dependency:build-classpath → 108 条目
  == DEPLOY.md §2.3-A + §5.2-3 ==      # export LD_LIBRARY_PATH=/home/lsh/code/ceph/build/lib
                                       # hfs = java -cp conf:连接器:libcephfs.jar:$(cat cp.txt)
                                       #       -Djava.library.path=<解包目录> o.a.h.fs.FsShell
  $ hadoop fs -mkdir -p ceph:///verify/dir                                  rc=0
  $ hadoop fs -ls ceph:///verify
  Found 1 items
  drwxr-xr-x   - root 0          0 2026-07-07 13:40 ceph:///verify/dir      rc=0
  $ hadoop fs -put /tmp/t07-deploy.BrB5sp/hello.txt ceph:///verify/dir/hello.txt   rc=0
  $ hadoop fs -ls ceph:///verify/dir
  -rw-r--r--   3 root 0         13 2026-07-07 13:40 ceph:///verify/dir/hello.txt  rc=0
  $ hadoop fs -cat ceph:///verify/dir/hello.txt
  hello cephfs                                                              rc=0
  $ hadoop fs -rm -r ceph:///verify
  Deleted ceph:///verify                                                    rc=0
  $ hadoop fs -test -e ceph:///verify（应 rc=1）                            rc=1
  [t07-final-verify] PASS: 干净 shell 按 DEPLOY.md 完成 ls/put/cat/rm 全流程
  ```

- 交付物清单：
  - `dist/hadoop-cephfs-1.0.0.tar.gz`（git 忽略二进制，磁盘上存在；
    `scripts/make-dist.sh` 可随时复现，sha256 见脚本输出）
  - `scripts/make-dist.sh`、`scripts/t07-final-verify.sh`
  - `conf/core-site.xml.example`（架构文档 §5 全部 13 键注释版，vstart/生产两组示例值）
  - `docs/DEPLOY.md`（前提、安装、native 三种配置方式、配置讲解、cephx、验证、
    6 类排查表、已知限制）、`docs/DEVELOP.md`（构建、三级测试、打包、回归门禁）
  - `hadoop-cephfs/pom.xml`（版本 0.1.0-SNAPSHOT → 1.0.0）
  - 更新的根 `README.md`（状态 + 快速上手）、`.gitignore`（忽略打包产物）
  - git tag `v1.0.0`
- 与设计文档的偏差：
  1. 任务书 §3 写 cephx 需 "mds rw"，实测不足：连接器 create 恒下发 file layout
     （7 参 open），MDS 要求 `p` 能力位；DEPLOY.md 按实测写 **mds rwp** 并在 §4
     显著标注（复现与验证记录见验收 4-④）。代码未改（见遗留 1）。
  2. ENV.md §3 的两条报错文本源自直接 System.loadLibrary 场景；经 libcephfs.jar 的
     CephNativeLoader（带 /usr/lib64、/usr/lib/jni fallback）实际症状统一为
     `UnsatisfiedLinkError: Can't load library: /usr/lib/jni/libcephfs_jni.so`。
     DEPLOY.md 排查表按实测原文写，并给出用加载日志与 `ldd` 区分两种根因的方法。
  3. pom 版本升至 1.0.0 属任务书明确要求（产出 hadoop-cephfs-1.0.0.jar），
     非接口变更；scripts/e2e-cli-test.sh 以通配符匹配 jar，无需改动。
- 遗留/移交事项（项目级汇总，供人工后续决策）：
  1. **mds `p` 能力位需求可优化**：create 在用户未显式配置 blockSize/ceph.object.size
     偏好时也走 7 参 layout open。后续可改为"默认 layout 时用 3 参 open"，将最小
     mds caps 降回 `rw`（涉及 T04 已验收路径，本任务按边界只登记不实现）。
  2. 发布包内 `libcephfs_jni.so` 为本机 Ceph 调试构建产物（带指向
     `/home/lsh/code/ceph/build/lib` 的 RUNPATH，无害但非纯净）；正式对外发布建议
     用生产构建重编该 so（方法同 ENV.md §2），或指引用户改用发行版 libcephfs2
     自带的 jni 库（DEPLOY.md §1 已写获取方式）。
  3. 二进制产物（dist/native/*、tar.gz）均不入 git；正式发布渠道（制品库/Release
     附件）与签名流程需人工决策（任务书禁止发布到外部仓库）。
  4. 终验以 FsShell 等效方式完成（机器无 Hadoop 发行版，T06 既定口径）；如需
     用发行版 `bin/hadoop` 包装脚本再验一遍，装好发行版后按 DEPLOY.md §5.1 直接执行。
  5. 功能性遗留（历次登记汇总）：BlockLocation 无机架拓扑（topologyPaths 空）；
     getContentSummary 为基类 Java 侧递归，超大目录树性能一般；XAttr/ACL/快照/
     Kerberos/多 fs 为架构 §1.2 非目标，未实现；AbstractFileSystem（CephFs）无
     close 钩子，mount 生命周期同 JVM（T05 登记）。
- 环境变更：
  - 为验证 DEPLOY.md §4/排查表临时创建 cephx 用户 client.hadoop（rw→复现④）、
    client.hadoop2（rwp→验证修复）、client.viewer（只读），验证后已全部
    `ceph auth del` 删除；集群中测试路径（/t07-cephx、/verify、/e2e-cli-*）均已清理，
    根目录为空，集群仍运行中（HEALTH_WARN 单 OSD 正常告警）。
  - 本地 Maven 仓库无新增依赖。
