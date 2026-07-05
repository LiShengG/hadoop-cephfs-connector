# T01 — 环境验证与 libcephfs Java 绑定构建

## 目标

打通「Java → JNI → libcephfs → vstart CephFS 集群」全链路，产出后续任务依赖的
`libcephfs.jar` 与 `libcephfs_jni.so`，并用一个 smoke test 证明可用。

## 前置依赖

无（首个任务）。必读：`docs/00-顶层架构设计.md` §2、§6、§7。

## 已知环境事实

- `ceph/build/lib/libcephfs.so.2.0.0` 已存在；
- Java 绑定**未构建**（build 目录中无 `libcephfs.jar` / `libcephfs_jni.so`）；
- 绑定源码：`ceph/src/java/`（`java/com/ceph/fs/*.java` + `native/libcephfs_jni.cc`）；
- vstart 在 `ceph/build` 下经 `../src/vstart.sh` 运行；
- Java 11.0.27，Maven 3.6.3。

## 工作内容

1. **初始化工程仓库**：在 `hadoop-cephfs-connector/` 下 `git init` 并首次提交现有文档；
   创建 `PROGRESS.md`（空表头即可）与 `scripts/`、`dist/` 目录。
2. **验证 vstart 集群**：启动（或重建）vstart，确认 `bin/ceph -s` 健康、
   `bin/ceph fs ls` 有可用文件系统；把启动/停止/健康检查写成
   `scripts/cluster-up.sh` / `scripts/cluster-down.sh`。
3. **构建 Java 绑定**（两条路径按序尝试）：
   - 路径 A（优先）：在 `ceph/build` 用 cmake 开启绑定并只构建相关 target：
     `cmake -DWITH_CEPHFS_JAVA=ON .` 后 `make libcephfs_jni cephfs-java`
     （实际 target 名以 `make help | grep -i -E "java|jni"` 为准）。
     注意：只增量启用该选项，不得触发全量重编或改动其他缓存项；如 cmake 依赖缺失
     （如 jni.h 路径），先修 cmake 参数。
   - 路径 B（A 失败时）：手工构建 —— `javac`（`-source 8 -target 8`）编译
     `src/java/java` 打出 `libcephfs.jar`；`g++` 编译 `native/libcephfs_jni.cc`
     （`-I$JAVA_HOME/include -I$JAVA_HOME/include/linux -I ceph/src/include`，
     链接 `-L ceph/build/lib -lcephfs`）产出 `libcephfs_jni.so`。
4. **归置产物**：拷贝 `libcephfs.jar`、`libcephfs_jni.so` 到
   `hadoop-cephfs-connector/dist/native/`，并
   `mvn install:install-file` 以坐标 `com.ceph:libcephfs:16.2.14` 装入本地 Maven 仓库。
5. **Smoke test**：写 `scripts/smoke/CephSmoke.java`（不依赖 Hadoop）：
   `CephMount.mount()` → `mkdirs` → `open+write` 1MB → `read` 回读比对 → `unlink` →
   `unmount`；配套 `scripts/smoke-test.sh` 一键编译运行（正确设置
   `java.library.path` 与 `LD_LIBRARY_PATH=ceph/build/lib`）。
6. **写 `docs/ENV.md`**：记录 mon 地址、ceph.conf/keyring 路径、auth id、
   产物路径、两个脚本用法 —— 这是 T02+ 所有任务连接集群的唯一依据。

## 交付物

- `dist/native/libcephfs.jar`、`dist/native/libcephfs_jni.so`
- 本地 Maven 仓库中的 `com.ceph:libcephfs:16.2.14`
- `scripts/cluster-up.sh`、`scripts/cluster-down.sh`、`scripts/smoke-test.sh`、
  `scripts/smoke/CephSmoke.java`
- `docs/ENV.md`、初始化的 git 仓库、更新的 `PROGRESS.md`

## 验收标准

1. `scripts/cluster-up.sh` 从停止状态执行后，`ceph -s` 显示健康且 cephfs 就绪；
2. `scripts/smoke-test.sh` 退出码 0，输出写读比对一致；
3. `mvn dependency:get -Dartifact=com.ceph:libcephfs:16.2.14 -o` 成功（本地仓库命中）；
4. `docs/ENV.md` 内的每条路径/命令实际有效。

## 边界与禁止事项

- 不修改 ceph 源码文件；cmake 缓存调整仅限启用 Java 绑定所需的最小集合；
- 不开始编写任何 Hadoop 相关代码（那是 T02+ 的事）；
- 若路径 A/B 均失败，登记 BLOCKED 与完整报错，停止。
