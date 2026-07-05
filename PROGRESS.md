# 项目进度登记

> 每个 agent 完成（或阻塞）时按 `docs/01-协作规范.md` §3 的模板在此追加一节。

| 任务 | 状态 | 完成日期 | 备注 |
|---|---|---|---|
| T01 环境验证与 Java 绑定构建 | DONE | 2026-07-05 | smoke test 全链路通过；产物见 dist/native 与本地 Maven 仓库 |
| T02 Maven 骨架与抽象层 | 未开始 | — | |
| T03 元数据操作 | 未开始 | — | |
| T04 数据读写流 | 未开始 | — | |
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
