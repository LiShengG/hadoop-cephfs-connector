# E2 三节点生产仿真环境

> 建成日期：2026-08-15；用途：L2–L5 与 Spark E2 复验。该环境数据可丢弃，
> 但 OSD 设备处置仍须严格遵守本页设备白名单。

## 1. 固定指纹

| 项 | 值 |
|---|---|
| FSID | `b8bcd906-984c-11f1-9f8c-e37fec9805df` |
| 服务端 | 官方 Release 容器 `quay.io/ceph/ceph:v16.2.14` |
| RepoDigest | `sha256:9ca1fe4ec4643bbe5ab8b895cca0d54fb8edae70fcca189919177db1cdd91745` |
| 节点 | `node-44` (`10.20.40.44`)、`node-26` (`10.20.40.26`)、`node-28` (`10.20.40.28`) |
| public network | `10.20.40.0/23`（`ens192`） |
| cluster network | `172.30.40.0/24`（`ens224`，netplan 持久化） |
| 控制面 | 3 MON、2 MGR（1 active + 1 standby） |
| 存储 | 6 OSD（每节点 2）、约 700GiB raw，CRUSH failure domain=host |
| CephFS | `cephfs-e2`，3 MDS（1 active + 2 standby） |
| 池 | `cephfs.cephfs-e2.meta/data`，均 `size=3/min_size=2` |
| 客户端身份 | `client.hadoop`：mon r、mds rwp、osd rw，均限制到 `cephfs-e2` |
| 客户端配置 | `/etc/ceph/ceph.conf`、`/etc/ceph/ceph.client.hadoop.keyring` |

## 2. OSD 设备边界

E2 已使用且仅使用以下设备：

- `node-44`: `/dev/sdb`、`/dev/sdc`；
- `node-26`: `/dev/sdc`、`/dev/sdd`；
- `node-28`: `/dev/sdd`、`/dev/sdg`。

`node-28` 的 `/dev/sdb`、`/dev/sdc`、`/dev/sde`、`/dev/sdf` 含旧 BlueStore，属于
其他实验数据，**未被 E2 触碰，禁止自动 zap 或 `--all-available-devices`**。

## 3. 基线检查

在 `node-44` 执行：

```bash
/usr/local/sbin/e2-status
```

该命令由仓库中的 `scripts/env/e2-status.sh` 安装而来，只执行只读检查。

正常终行必须为：

```text
[e2-status] PASS: 3 MON, 6 OSD up/in, CephFS size=3/min_size=2, HEALTH_OK
```

## 4. Release 客户端验证

`node-26` 安装 Pacific 官方 Release 客户端 16.2.15：

- `/usr/share/java/libcephfs-16.2.15.jar`；
- `/usr/lib/jni/libcephfs_jni.so.1.0.0`；
- `/usr/lib/libcephfs.so.2.0.0`（stripped Release）。

服务端为 16.2.14，客户端为同一 Pacific 分支的 16.2.15。运行时必须显式设置
`LD_LIBRARY_PATH=/usr/lib`，否则动态链接器会优先命中 `/usr/local/lib` 的旧 Debug 库。

完整门禁命令：

```bash
cd /code/hadoop-cephfs-connector/hadoop-cephfs
CEPH_CONTRACT_TEST=1 \
CEPH_CONF_FILE=/etc/ceph/ceph.conf \
CEPH_AUTH_ID=hadoop \
CEPH_AUTH_KEYRING=/etc/ceph/ceph.client.hadoop.keyring \
mvn -Dceph.lib.dir=/usr/lib \
    -Dceph.jni.dir=/usr/lib/jni \
    -Dceph.test.args= verify
```

2026-08-15 结果：128/128 单测、142/142 契约与集成测试通过；BlockLocation 的三个
副本地址为 `10.20.40.26/28/44`。

## 5. Spark SP-04 结论

Release JNI、无 lockdep workaround 下：

- SP-04B 连续 5 轮均为 1 成功 + 7 `FileAlreadyExistsException`；
- Structured Streaming 同 checkpoint 重启保持 query ID，batch 0/40 行推进到 batch 1/80 行；
- 双 `spark-submit` 竞争时退出码 0/1，失败方为 `SparkConcurrentModificationException`，
  第三 JVM 可恢复到 batch 1/80 行；
- 未出现 `boost::bad_get`、abort 或 core dump；
- 失败竞争者留下一个 `.metadata.<uuid>.tmp`。正式 metadata 与 commits/offsets 正确，
  但该临时源需由作业/运维清理，连接器不能在 no-replace 失败时擅自删除源路径。

原始日志位于 `node-26:/code/hadoop-cephfs-connector/hadoop-cephfs/target/` 下的
`spike-e2-release/` 与 `spike-spark-e2-release/`。
