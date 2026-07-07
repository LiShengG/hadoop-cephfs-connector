# Hadoop CephFS 连接器部署文档（hadoop-cephfs 1.0.0）

本文档面向部署者：在一台装有 Hadoop 3.3.6 与 Ceph 客户端库的机器上配通 `ceph://`。
开发/构建/测试见 [DEVELOP.md](DEVELOP.md)。

> 实测口径：本文档所有命令与报错信息均在 Ceph 16.2.14 vstart 集群 + OpenJDK 11 环境
> 逐条实测（2026-07-07）。验证环境没有 Hadoop 可执行发行版，`hadoop fs` 命令以
> `java org.apache.hadoop.fs.FsShell` 等效实测——`hadoop fs` 包装脚本最终 exec 的
> 就是 FsShell，二者仅 classpath 组装方式不同（见 §5.2）。

## 1. 前提条件

| 项 | 要求 | 获取方式 |
|---|---|---|
| Java | 8 或 11（连接器为 Java 8 字节码；实测 OpenJDK 11.0.27） | 发行版包 |
| Hadoop | 3.3.6（`hadoop-common` 为 provided 依赖，由部署环境提供） | 官方 tarball / 已有集群 |
| Ceph 客户端库 | `libcephfs.so.2` 与 `libceph-common.so.2`，版本 16.2.x (Pacific)，须与包内 `libcephfs_jni.so` 同大版本 | Ubuntu/Debian: `apt install libcephfs2`；EL: `dnf install libcephfs2`（配置 Ceph Pacific 仓库）；或源码构建（见 ENV.md §2） |
| Ceph 集群 | 可达的 MON/MDS/OSD + 已创建的 CephFS 文件系统 | `ceph fs ls` 确认 |
| 客户端配置 | 本机可读的 `ceph.conf`（含 mon 地址）与 cephx keyring | 集群管理员分发；权限要求见 §4 |

自检 Ceph 客户端库是否就绪（`not found` 即缺库）：

```bash
ldd /path/to/libcephfs_jni.so | grep -E 'libcephfs|libceph-common'
```

## 2. 安装

### 2.1 解包

```bash
tar xzf hadoop-cephfs-1.0.0.tar.gz
```

得到：

```
hadoop-cephfs-1.0.0/
├── hadoop-cephfs-1.0.0.jar      # 连接器
├── libcephfs.jar                # Ceph 官方 Java 绑定（com.ceph.fs.CephMount）
├── libcephfs_jni.so             # Java 绑定的 JNI 原生库
├── conf/core-site.xml.example   # 配置样例（本文档 §3 的机读版）
├── README.md                    # 快速安装
└── docs/DEPLOY.md               # 本文档
```

**为什么 libcephfs.jar 不合入（shade）连接器 jar、而是并列发布：**

1. `libcephfs.jar`（LGPL-2.1）与连接器（Apache-2.0）许可不同，合入会污染连接器许可；
2. `libcephfs.jar` 与 `libcephfs_jni.so` 是同源构建的一对（JNI 符号一一对应），
   随 Ceph 客户端升级时必须整体替换，独立成件才能只换这一对而不动连接器；
3. 节点上若已随 Ceph 部署过 `libcephfs.jar`，shade 进去的副本会造成同名类冲突。

### 2.2 jar 放置（两种方式，选一）

**方式 A：放入 Hadoop 公共 lib 目录**（集群常驻部署推荐，对所有组件生效）：

```bash
cp hadoop-cephfs-1.0.0/hadoop-cephfs-1.0.0.jar \
   hadoop-cephfs-1.0.0/libcephfs.jar \
   "$HADOOP_HOME/share/hadoop/common/lib/"
```

**方式 B：加入 HADOOP_CLASSPATH**（不动 Hadoop 安装目录）：

```bash
export HADOOP_CLASSPATH="/opt/hadoop-cephfs-1.0.0/hadoop-cephfs-1.0.0.jar:/opt/hadoop-cephfs-1.0.0/libcephfs.jar${HADOOP_CLASSPATH:+:$HADOOP_CLASSPATH}"
```

两个 jar 缺一不可：连接器 jar 提供 `org.apache.hadoop.fs.ceph.*`，
`libcephfs.jar` 提供其依赖的 `com.ceph.fs.*`。

### 2.3 native 库路径（关键，两个变量各司其职）

JNI 加载分两步，对应两个必须都满足的路径设置：

| 设置 | 作用 | 缺失时的报错（实测见 §6-1） |
|---|---|---|
| `java.library.path`（JVM 属性） | 让 `System.loadLibrary("cephfs_jni")` 找到 `libcephfs_jni.so` | `UnsatisfiedLinkError` |
| `LD_LIBRARY_PATH`（进程环境变量） | 让动态链接器解析 `libcephfs_jni.so` 依赖的 `libcephfs.so.2`、`libceph-common.so.2` | `UnsatisfiedLinkError`（`ldd` 显示 `not found`） |

若 Ceph 客户端库经发行版包安装在系统路径（`/usr/lib/x86_64-linux-gnu`、`/usr/lib64`
等 ldconfig 缓存内），`LD_LIBRARY_PATH` 可省略；源码构建/非标准路径则必须设置。
另外 Linux JVM 会把 `LD_LIBRARY_PATH` 并入 `java.library.path` 的默认值（实测），
所以把 `libcephfs_jni.so` 与 Ceph 库放同一目录时，仅设 `LD_LIBRARY_PATH` 即可同时
满足两项。

三种配置方式（选一）：

**方式 A：环境变量 `LD_LIBRARY_PATH`**（会话级，最简单）：

```bash
export LD_LIBRARY_PATH="/opt/hadoop-cephfs-1.0.0:/usr/lib/ceph${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
```

（第一段为 `libcephfs_jni.so` 所在目录，第二段为 Ceph 库目录，按实际路径替换。）

**方式 B：环境变量 `JAVA_LIBRARY_PATH`**（Hadoop 启动脚本识别，会拼进
`-Djava.library.path`）：

```bash
export JAVA_LIBRARY_PATH="/opt/hadoop-cephfs-1.0.0"
export LD_LIBRARY_PATH="/usr/lib/ceph${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"   # Ceph 库不在系统路径时仍需要
```

**方式 C：写入 `$HADOOP_HOME/etc/hadoop/hadoop-env.sh`**（集群持久化，对守护进程
与 CLI 统一生效）：

```bash
export JAVA_LIBRARY_PATH="/opt/hadoop-cephfs-1.0.0${JAVA_LIBRARY_PATH:+:$JAVA_LIBRARY_PATH}"
export LD_LIBRARY_PATH="/opt/hadoop-cephfs-1.0.0:/usr/lib/ceph${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
```

补充：`libcephfs.jar` 的加载器还支持环境变量 `CEPH_JNI_PATH` 直接指定 `.so`
**全路径**（如 `/opt/hadoop-cephfs-1.0.0/libcephfs_jni.so`），优先级最高，
可用于排查加载路径问题。

## 3. core-site.xml 配置

把下面 5 个必配项合并进 `$HADOOP_HOME/etc/hadoop/core-site.xml`
（机读样例见包内 `conf/core-site.xml.example`，含全部可选项）：

```xml
<property><name>fs.ceph.impl</name><value>org.apache.hadoop.fs.ceph.CephFileSystem</value></property>
<property><name>fs.AbstractFileSystem.ceph.impl</name><value>org.apache.hadoop.fs.ceph.CephFs</value></property>
<property><name>fs.defaultFS</name><value>ceph:///</value></property>
<property><name>ceph.conf.file</name><value>/etc/ceph/ceph.conf</value></property>
<property><name>ceph.auth.id</name><value>hadoop</value></property>
```

- **`fs.AbstractFileSystem.ceph.impl` 必须显式配置**：`AbstractFileSystem`
  （FileContext 路径，YARN NodeManager 等使用）没有 ServiceLoader 自动发现机制，
  不配则 FileContext/YARN 报
  `UnsupportedFileSystemException: fs.AbstractFileSystem.ceph.impl=null`。
  `fs.ceph.impl` 虽有自动发现兜底，也建议显式配置以避免歧义。
- `fs.defaultFS=ceph:///`（无 authority）时 mon 地址从 `ceph.conf.file` 读取（推荐）；
  写成 `ceph://mon1.example.com:6789/` 则 URI 中的地址覆盖 ceph.conf 的 `mon_host`。
  不想改默认文件系统时可不配 `fs.defaultFS`，命令里写全 URI（`hadoop fs -ls ceph:///`）。
- `ceph.auth.id` 是 cephx 用户名去掉 `client.` 前缀的部分（§4）。

全部配置键（架构文档 §5，均有注释样例在 `conf/core-site.xml.example`）：

| 键 | 默认 | 说明 |
|---|---|---|
| `fs.ceph.impl` | — | 固定 `org.apache.hadoop.fs.ceph.CephFileSystem` |
| `fs.AbstractFileSystem.ceph.impl` | — | 固定 `org.apache.hadoop.fs.ceph.CephFs`，**必配** |
| `fs.defaultFS` | — | `ceph:///` 或 `ceph://host:port/` |
| `ceph.conf.file` | null | ceph.conf 路径 |
| `ceph.conf.options` | null | 分号分隔的附加 ceph 客户端配置，如 `client_mount_timeout=30` |
| `ceph.auth.id` | null | cephx 用户 id；null 用 libcephfs 默认 |
| `ceph.auth.keyring` | null | keyring 文件路径；null 依赖 ceph.conf 的 keyring 项 |
| `ceph.root.dir` | `/` | 把 CephFS 子目录挂为 Hadoop 根（多业务隔离） |
| `ceph.object.size` | 67108864 | 新文件 object size（字节），兼作 blockSize 报告值；须为 64KB 整数倍 |
| `ceph.data.pools` | null | 新文件 data pool（逗号分隔取第一个）；cephx 授权须覆盖该 pool |
| `ceph.replication` | 3 | 仅用于上报的副本数，真实冗余由 pool 决定 |
| `ceph.localize.reads` | true | 读时优先本地 OSD 副本 |
| `ceph.client.buffer.size` | 4194304 | 流读/写缓冲（字节） |

## 4. cephx 权限要求

连接器挂载与读写需要的最小能力（caps）：

| 组件 | 能力 | 说明 |
|---|---|---|
| mon | `allow r` | 获取集群/OSD map |
| mds | `allow rwp` | 元数据读写；**`p` 位必需**——连接器 `create()` 会把 Hadoop blockSize 作为 file layout（object size）下发，MDS 要求 `p`（set layout/quota）能力位。只给 `rw` 的实测症状：`mkdir` 成功、`put` 报 `Permission denied`（见 §6-4） |
| osd | `allow rw`（限 data pool） | 文件数据读写 |

> 注：任务书/旧文档写的 "mds rw" 不足，实测必须 `rwp`。

用 `ceph fs authorize` 一条命令生成（fsname 换成 `ceph fs ls` 里的名字，此处为 `a`；
实测于 16.2.14）：

```bash
ceph fs authorize a client.hadoop / rwp > /etc/ceph/ceph.client.hadoop.keyring
chmod 600 /etc/ceph/ceph.client.hadoop.keyring
ceph auth get client.hadoop     # 核对 caps
```

实测生成的 caps：

```
caps mds = "allow rwp fsname=a"
caps mon = "allow r fsname=a"
caps osd = "allow rw tag cephfs data=a"
```

core-site.xml 对应：

```xml
<property><name>ceph.auth.id</name><value>hadoop</value></property>
<property><name>ceph.auth.keyring</name><value>/etc/ceph/ceph.client.hadoop.keyring</value></property>
```

（vstart 开发集群图省事可直接用 `admin`——`ceph.conf` 已声明 keyring 路径，
`ceph.auth.keyring` 可省。）

若配置了 `ceph.data.pools` 指向非默认 pool，osd caps 须放宽覆盖该 pool，例如
`osd 'allow rw tag cephfs data=a, allow rw pool=cephfs_data_ssd'`。

## 5. 验证命令序列

### 5.1 标准方式（有 Hadoop 发行版）

完成 §2/§3/§4 后逐条执行（应全部 rc=0，`-test -e` 最后一条 rc=1）：

```bash
echo 'hello cephfs' > /tmp/hello.txt
hadoop fs -mkdir -p ceph:///verify/dir
hadoop fs -ls ceph:///verify
hadoop fs -put /tmp/hello.txt ceph:///verify/dir/hello.txt
hadoop fs -cat ceph:///verify/dir/hello.txt        # 应输出 hello cephfs
hadoop fs -rm -r ceph:///verify
hadoop fs -test -e ceph:///verify && echo STILL-THERE || echo CLEANED
```

### 5.2 无 Hadoop 发行版（FsShell 直跑，本文档的实测方式）

`hadoop fs` 最终 exec `org.apache.hadoop.fs.FsShell`。只要 classpath 上有
hadoop-common 及其依赖（下例用 Maven 从本地仓库解析），即可等效验证：

```bash
# 1) 组装 Hadoop classpath（也可用已有集群的 `hadoop classpath` 输出替代）
cd hadoop-cephfs && mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt

# 2) conf 目录：放好按 §3 写的 core-site.xml
export CONF_DIR=/path/to/confdir

# 3) 逐条执行（与 §5.1 相同的命令序列）
export LD_LIBRARY_PATH=/path/to/ceph/lib
alias hfs='java -cp "$CONF_DIR:/path/to/hadoop-cephfs-1.0.0.jar:/path/to/libcephfs.jar:$(cat target/cp.txt)" -Djava.library.path=/path/to/jni-dir org.apache.hadoop.fs.FsShell'
hfs -mkdir -p ceph:///verify/dir
hfs -put /tmp/hello.txt ceph:///verify/dir/hello.txt
hfs -cat ceph:///verify/dir/hello.txt
hfs -rm -r ceph:///verify
```

（源码仓库的 `scripts/e2e-cli-test.sh` 就是这条路的自动化版：21 项断言 +
200MB put/get md5 往返比对，可作部署后回归。）

## 6. 故障排查表

下表全部症状均已真实复现（2026-07-07，Ceph 16.2.14 + OpenJDK 11；
症状文本为实测原文）。

| # | 症状（实测原文） | 原因 | 解法 |
|---|---|---|---|
| 1 | 启动即抛 `java.lang.UnsatisfiedLinkError: Can't load library: /usr/lib/jni/libcephfs_jni.so`，其前有 `Loading libcephfs-jni from default path: ...` / `Loading libcephfs-jni: Failure!` 日志 | `libcephfs_jni.so` 加载失败。两种根因症状相同（加载器把 `java.library.path` 失败吞掉后 fallback 到 `/usr/lib64`、`/usr/lib/jni`，异常文本是最后的 fallback 路径）：① so 不在 `java.library.path`（看 `Loading ... from default path:` 打印的实际搜索路径里有没有你的目录）；② so 找到了但其依赖缺失——`ldd libcephfs_jni.so` 显示 `libcephfs.so.2 => not found` | ① 按 §2.3 设 `JAVA_LIBRARY_PATH`/`LD_LIBRARY_PATH`，或用 `CEPH_JNI_PATH` 指定 so 全路径二分定位；② 安装 `libcephfs2` 包或把 Ceph 库目录加入 `LD_LIBRARY_PATH`，直到 `ldd` 无 `not found` |
| 2 | 命令挂起约 1 分钟后 `ls: Connection timed out`（rc=1） | mon 地址不可达：URI/ceph.conf 里的 `mon_host` 写错、网络/防火墙不通、或集群未运行 | 核对 `ceph.conf.file` 指向的文件里的 `mon host`；用 `ceph -c <该文件> -s` 先在连接器外验证连通；可配 `ceph.conf.options=client_mount_timeout=30` 加快失败返回 |
| 3 | 所有命令报 `ls: Permission denied`（挂载阶段即失败） | cephx 认证失败：`ceph.auth.id` 用户不存在、keyring 文件不可读、或 keyring 与用户不匹配 | `ceph auth get client.<id>` 确认用户存在；核对 `ceph.auth.keyring` 路径与文件权限（进程用户可读）；keyring 内条目名须为 `[client.<id>]` |
| 4 | `-mkdir` 成功但 `-put` 报 `put: Permission denied` | mds caps 只有 `rw` 没有 `p`：连接器 create 下发 file layout，MDS 拒绝 | 按 §4 重新授权 `ceph fs authorize <fs> client.<id> / rwp`（或 `ceph auth caps` 把 mds 段改成 `allow rwp ...`） |
| 5 | `-ls: Fatal internal error` + `java.lang.ClassNotFoundException: Class org.apache.hadoop.fs.ceph.CephFileSystem not found`（未配 `fs.ceph.impl` 时为 `No FileSystem for scheme "ceph"`） | 连接器 jar 不在 classpath（`com.ceph.fs` 报 NoClassDefFoundError 则是缺 `libcephfs.jar`） | 按 §2.2 放置两个 jar；`hadoop classpath` 检查生效的 classpath |
| 6 | FileContext/YARN 报 `UnsupportedFileSystemException: fs.AbstractFileSystem.ceph.impl=null` | `fs.AbstractFileSystem.ceph.impl` 未配置（无自动发现） | 按 §3 显式配置该键 |

**调试构建集群专用**：若连接的是 Ceph 源码调试构建（如 vstart，其 ceph.conf 含
`lockdep = true`），客户端 JVM 可能在挂载引导期被 Ceph 内部 lockdep 误报
（`ceph_abort` 崩栈在 `AsyncMessenger`/`MonClient`）直接拉崩。配
`ceph.conf.options=lockdep=false`（实测有效）或环境变量
`CEPH_ARGS=--lockdep=false` 规避。生产 Ceph 构建无此配置项，不受影响。

## 7. 已知限制

- 不支持 `concat()`、`truncate()`；`setReplication()` 返回 false（副本数由 pool 决定）；
- 仅 cephx 认证，无 Kerberos/委托 Token 集成；
- `getFileBlockLocations` 返回 OSD 的 IP 文本，无机架拓扑（topologyPaths 为空）；
- 非空根目录 `delete("/", recursive=false)` 返回 false 而非抛异常（根目录保护）。
