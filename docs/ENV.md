# 开发环境说明（T01 产出，T02+ 连接集群的唯一依据）

> 更新日期：2026-07-05（T01）

## 1. vstart Ceph 集群

| 项 | 值 |
|---|---|
| Ceph 版本 | 16.2.14 (pacific, 238ba602515df21ea7ffc75c88db29f9e5ef12c9) |
| 构建目录 | `/home/lsh/code/ceph/build` |
| ceph.conf | `/home/lsh/code/ceph/build/ceph.conf` |
| keyring | `/home/lsh/code/ceph/build/keyring`（权限 600） |
| auth id | `admin`（即 client.admin；另有 vstart 生成的 `client.fs`） |
| mon 地址 | `[v2:10.255.255.254:40425,v1:10.255.255.254:40426]`（**以 ceph.conf 的 `mon host` 为准**——`vstart.sh -n` 重建后可能变化，代码/测试不要硬编码，一律通过 `ceph.conf.file` 读取） |
| CephFS 文件系统 | `name: a`，metadata pool `cephfs.a.meta`，data pool `cephfs.a.data` |
| 拓扑 | 1 mon + 1 mgr + 1 mds + 1 osd（单机 vstart） |
| 正常健康状态 | `HEALTH_OK` 或 `HEALTH_WARN`（"pool(s) have no replicas configured" 为 vstart 单 OSD 正常告警） |
| 守护进程日志 | `/home/lsh/code/ceph/build/out/` |

### 集群操作脚本

```bash
scripts/cluster-up.sh        # 启动并等待就绪；已运行则复用（幂等）
scripts/cluster-up.sh -n     # 销毁重建全新集群（清空数据；ceph.conf 可能被重写）
scripts/cluster-down.sh      # 停止（保留数据）
```

两脚本均支持 `CEPH_BUILD` 环境变量覆盖构建目录（默认 `/home/lsh/code/ceph/build`）。

手工检查：

```bash
cd /home/lsh/code/ceph/build
bin/ceph -s          # 健康状态（stderr 的 experimental WARNING 可忽略）
bin/ceph fs ls       # 文件系统列表
```

## 2. libcephfs Java 绑定产物

| 产物 | 路径 | 来源 |
|---|---|---|
| `libcephfs.jar` | `/home/lsh/code/hadoop-cephfs-connector/dist/native/libcephfs.jar` | cmake target `libcephfs`（构建输出 `ceph/build/src/java/libcephfs.jar`） |
| `libcephfs_jni.so` | `/home/lsh/code/hadoop-cephfs-connector/dist/native/libcephfs_jni.so` | cmake target `cephfs_jni`（构建输出 `ceph/build/lib/libcephfs_jni.so.1.0.0` 的拷贝） |
| Maven 坐标 | `com.ceph:libcephfs:16.2.14`（已装入本地仓库 `/root/.m2/repository`） | `mvn install:install-file` |

Maven 依赖写法（T02 起使用）：

```xml
<dependency>
  <groupId>com.ceph</groupId>
  <artifactId>libcephfs</artifactId>
  <version>16.2.14</version>
</dependency>
```

### 重建方法（产物失效时）

cmake 已配置 `WITH_CEPHFS_JAVA=ON`，增量重建：

```bash
cd /home/lsh/code/ceph/build
make libcephfs cephfs_jni     # 分别产出 jar 与 jni .so
# 注意：meta target `make java` 因上游 CMakeLists 缺陷不可用
#（add_custom_target(java DEPENDS ... libcephfs_jni) 把 target 名当文件依赖），勿用。
cp src/java/libcephfs.jar     /home/lsh/code/hadoop-cephfs-connector/dist/native/libcephfs.jar
cp lib/libcephfs_jni.so.1.0.0 /home/lsh/code/hadoop-cephfs-connector/dist/native/libcephfs_jni.so
```

## 3. JVM 运行时要求（所有需真实集群的测试必读）

`libcephfs_jni.so` 动态依赖 `libcephfs.so.2`、`libceph-common.so.2` 等，位于
`ceph/build/lib`。运行任何走 JNI 的 Java 程序须同时设置：

```bash
export LD_LIBRARY_PATH=/home/lsh/code/ceph/build/lib:$LD_LIBRARY_PATH
java -Djava.library.path=/home/lsh/code/hadoop-cephfs-connector/dist/native:/home/lsh/code/ceph/build/lib ...
```

（`java.library.path` 供 `System.loadLibrary("cephfs_jni")` 定位 jni 库；
`LD_LIBRARY_PATH` 供动态链接器解析其依赖。缺一会分别报
`UnsatisfiedLinkError: no cephfs_jni in java.library.path` 或
`libcephfs.so.2: cannot open shared object file`。）

## 4. Smoke test（链路自检）

```bash
scripts/smoke-test.sh    # 编译并运行 scripts/smoke/CephSmoke.java
                         # mount→mkdirs→写1MB→回读比对→unlink→rmdir→unmount
```

退出码 0 即 Java→JNI→libcephfs→CephFS 链路完好。任何时候怀疑环境坏了先跑它。

## 5. 工具链版本

| 工具 | 版本 |
|---|---|
| Java | OpenJDK 11.0.27（`JAVA_HOME` 未预设，javac/java 在 PATH） |
| Maven | 3.6.3（本地仓库 `/root/.m2/repository`） |
| Hadoop 源码参考 | `/home/lsh/code/hadoop-3.3.6-src`（只读） |
| Ceph 源码 | `/home/lsh/code/ceph`（只读，仅 build 目录可增量编译） |
