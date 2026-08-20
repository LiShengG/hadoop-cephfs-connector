# 语义覆盖矩阵

## 用途

本文档按操作而非测试阶段来索引核心文件系统语义。[`TEST-PLAN.md`](TEST-PLAN.md) 与
[`TEST-CASES-ECO.md`](TEST-CASES-ECO.md) 按层次和组件组织定义，一个操作因此被打散到多个分组里。
语义基线提供正交视角：一个 record 或一行对应一个决策条件，说明该操作应当做什么、依据是什么、今天实际做了
什么，以及哪一个精确断言守着它。

本索引与 [`catalog.ndjson`](catalog.ndjson) 共同组成语义基线，拥有决策条件粒度上的期望行为。凡是会重复陈述
期望的用例定义，一律委托到基线，使一条期望只存在于一处。实际行为由源码拥有，用例的身份与范围由两个用例
文件拥有，执行观察由 catalog `run` records 与带日期的 [`reports/`](reports/) 拥有。

## Current scope（当前范围）

当前清单覆盖 rename、create、delete、sync、visibility、identity、append、mkdirs、path-entrypoint、metadata、
read、status-defaults 与 block-location。已迁移 records 的计数与分类由 catalog viewer 计算，完整清单的结构
由校验脚本检查，不在本文手工维护。这是当前已盘点范围，不是完整的 Hadoop FileSystem SPI 语义清单，也不构成
未列出能力已经兼容的声明。

rename、create、delete、sync、append 与 mkdirs 这六个操作轴已经迁移到
[`catalog.ndjson`](catalog.ndjson)；本文只保留各轴的导航与关键解释。visibility、identity、
path-entrypoint、metadata、read、status-defaults 与 block-location 仍由本文中的五列表格拥有，后续再按
同一 schema 逐轴迁移。待迁移行的 `Guard / 用例` 与 `Coverage` 已先完成解耦。

## Deferred semantic axes（延后语义轴）

以下语义轴尚未完整进入本矩阵：checksum、replication semantics、truncate、concat、ACL、XAttr、symlink、
snapshot、native session lifecycle 与 multi-filesystem selection。现有孤立行或
[`KNOWN-LIMITATIONS.md`](KNOWN-LIMITATIONS.md) 条目只记录已知边界，不能替代这些轴的完整盘点。

## 如何读一行

| 列 | 含义 |
|---|---|
| 决策条件 | 输入情形 |
| 期望行为 | 该操作应当做什么 |
| Basis | 期望行为的规范、HDFS 参考实现或项目决策依据 |
| 当前行为 | 源码或已知观测所表现的行为；不承载覆盖或执行状态 |
| Classification | 当前行为相对于期望的分类 |
| Guard / 用例 | 精确测试方法或认领该条件的稳定用例 ID；没有则为 `—` |
| Coverage | 守护机制的类型；不表示最近一次是否执行 |
| Limitation / ADR | 偏离、项目决策或已知边界的持久记录；不适用则为 `—` |

`期望行为` 在 Hadoop 文件系统规范有明确规定处遵循规范；规范允许一个取值范围时遵循 HDFS，因为生态组件是
照着 HDFS 的行为写的；两者都无法定夺时由本矩阵决定，而该决定需要在 ADR 或
[`KNOWN-LIMITATIONS.md`](KNOWN-LIMITATIONS.md) 中留下理由。

`Basis` 是稳定的依据标识，可以组合使用。精确来源映射由 catalog metadata 拥有；某个轴声明了
`axis_basis_sources` 时优先使用该轴的来源，否则回退到全局 `basis_sources`。viewer 会把二者解析成可点击链接，
避免在每条 record 中复制 URL。当前全局通用值包括：

| Basis | 依据 |
|---|---|
| `HADOOP-SPEC` | Hadoop 3.3.6 的 FileSystem、stream 或 API 规范；catalog 按轴链接到实际使用的页面或源码 |
| `HDFS-3.3.6` | HDFS 3.3.6 的对应实现分支；catalog 按轴链接到 rename/create/delete/append/mkdirs 等实际源码 |
| `PROJECT-ADR-xxxx` | 本仓库对应 ADR 中接受的项目决策 |

`Classification` 取值：

| Classification | 含义 |
|---|---|
| `MATCH` | 已知当前行为与期望一致 |
| `DIFFERENT` | 已知当前行为与期望不同；必须链接 limitation 或 ADR |
| `UNSUPPORTED` | 该语义或能力被明确拒绝支持 |
| `UNKNOWN` | 当前行为尚不足以分类；不能用缺少最近执行结果来代替 |

`Coverage` 取值：

| Coverage | 含义 |
|---|---|
| `UNIT` | 由 mock 单测守着；不需要集群，每次构建都跑 |
| `CONTRACT` | 由 Hadoop 官方契约套件守着；需要集群与测试门控 |
| `CLUSTER` | 由本仓库的集成测试守着；需要集群与测试门控 |
| `SPIKE` | 只被脚本探针观察过一次，无法让构建失败 |
| `NONE` | 当前没有自动断言或探针；`Guard / 用例` 仍可列出计划中的稳定用例 ID |

一个用例 ID 可以出现在多个 records 或行上。它们合起来才是该用例完整的期望结果，单独一项永远不是全部。已迁移轴的
自动化覆盖必须写出 `Class#method`，不能要求读者按条件搜索测试树；待迁移轴尚未补齐这层追溯。
`NOT_RUN`、`PASS`、`FAIL` 等执行状态不进入 semantic records，只由 catalog `run` records、带日期的
[`reports/`](reports/) 与显式 `readiness_area` records 持有。

尚未迁移的五列表格以 `符合`、`符合（源码判断）`、**粗体差异**与`未知`临时表达分类；新 schema 不再使用这种
隐式约定。

## Derived views（派生视图）

Classification、Coverage、axis counts 与反向引用不再落盘为手工汇总。启动
[`viewer/`](viewer/) 查看可筛选视图，或只查询需要的 records：

```bash
python3 scripts/docs-catalog.py query --kind semantic --where axis=rename
python3 scripts/docs-catalog.py query --kind semantic --where classification=DIFFERENT
```

一条语义可以同时带多个 Coverage，因此各 Coverage 计数之和不等于条件数；`NONE` 不与其他 Coverage 组合。
`SPIKE` 只能说明曾有探针观察，不能代替回归断言或最近执行结果。

## rename

rename 的决策条件已迁移为 [`catalog.ndjson`](catalog.ndjson) 中 `SEM-RENAME-001` 至
`SEM-RENAME-027` 的 `semantic` records。catalog 是这些条件的唯一 owner；本文只保留入口与解释，不再复制
表格。可在 [HTTP viewer](viewer/index.html#/record/SEM-RENAME-001) 中逐条浏览并沿 Guard、limitation、ADR 或
evidence 跳转，也可以只读取需要的 records：

```bash
python3 scripts/docs-catalog.py query --kind semantic --where axis=rename
python3 scripts/docs-catalog.py show SEM-RENAME-004
```

旧 boolean rename 的目录 self-rename 采用 HDFS 3.3.6 行为作为期望：HDFS 先通过
`dstForRenameTo()` 把目录目的路径展开为 `dst/basename(src)`，再检查路径相等，因此目录到自身失败，而普通文件
到自身是空操作。`FileContext` 的普通文件 no-replace 路径使用 MDS hard-link 抢占；其项目决策由
[ADR-0004](adr/0004-atomic-no-replace-for-regular-files.md) 持有。

## create

create 的决策条件已迁移为 [`catalog.ndjson`](catalog.ndjson) 中 `SEM-CREATE-001` 至
`SEM-CREATE-021` 的 `semantic` records。catalog 是这些条件的唯一 owner；本文不再复制表格。可从
[首条 create record](viewer/index.html#/record/SEM-CREATE-001) 开始浏览，或按轴查询：

```bash
python3 scripts/docs-catalog.py query --kind semantic --where axis=create
python3 scripts/docs-catalog.py show SEM-CREATE-001
```

`FileSystem#create` 与两个 `createNonRecursive` 重载共用同一条内部路径，因此在一个入口上得到验证的条件，
并不自动在其他入口上成立。

## delete

delete 的决策条件已迁移为 [`catalog.ndjson`](catalog.ndjson) 中 `SEM-DELETE-001` 至
`SEM-DELETE-010` 的 `semantic` records。catalog 是这些条件的唯一 owner；本文不再复制表格。可从
[首条 delete record](viewer/index.html#/record/SEM-DELETE-001) 开始浏览，或按轴查询：

```bash
python3 scripts/docs-catalog.py query --kind semantic --where axis=delete
python3 scripts/docs-catalog.py show SEM-DELETE-001
```

`ITestCephContractRootDirectory` 的本地 override 只守护 connector 当前返回 `false` 的顺序结果，不把它变成
HDFS 语义。catalog 以 HDFS 3.3.6 为期望，并把非空根目录与递归删除的差异记录到 LIM-012。

`fs.trash.interval` 是 `FsShell`/`Trash` 包装层的行为，包装层通过 `rename` 把路径移进回收站，`CephFileSystem#delete`
不读取该配置，因此回收站语义由 FN-23 与 ECO-CLI-06 自行拥有，不在本矩阵内。

## sync

sync 的决策条件已迁移为 [`catalog.ndjson`](catalog.ndjson) 中 `SEM-SYNC-001` 至
`SEM-SYNC-016` 的 `semantic` records。catalog 是这些条件的唯一 owner；本文不再复制表格。可从
[首条 sync record](viewer/index.html#/record/SEM-SYNC-001) 开始浏览，或按轴查询：

```bash
python3 scripts/docs-catalog.py query --kind semantic --where axis=sync
python3 scripts/docs-catalog.py show SEM-SYNC-001
```

`hflush` 与 `hsync` 的开销差异由 PERF-10 判定；`SEM-SYNC-016` 只保存这个验证目标与当前 `UNKNOWN`
状态，不把尚未执行的性能探针写成兼容性结论。

## visibility

append 的起始位置同样源于 open 时刻的长度快照，该决策条件归 append 轴所有，此处不重复列出。

| 决策条件 | 期望行为 | 当前行为 | Guard / 用例 | Coverage |
|---|---|---|---|---|
| 在 writer sync 之后打开的 reader | 看到全部已 sync 的数据 | 符合 | FN-12 | CLUSTER |
| 在 writer 追加之前就已打开的 reader | 能观察到 open 之后才 sync 的数据 | **永远观察不到；长度在 open 时刻被定死** | FN-11, SP-05 | SPIKE |
| seek 越过 open 时刻记下的长度 | 只有越过真实文件末尾才被拒绝 | **越过记下的长度就被拒绝** | — | UNIT |
| 新创建的文件 | 立即可见 | 符合 | — | CONTRACT |
| 读取一个仍在被写入的文件 | 可读到最后一次 sync 为止 | **被 open 时刻的长度挡住** | ECO-HB-02, ECO-HB-03 | NONE |

## identity

| 决策条件 | 期望行为 | 当前行为 | Guard / 用例 | Coverage |
|---|---|---|---|---|
| status 的 owner，id 等于进程 id | Hadoop 用户名 | 符合 | SP-01 | UNIT + SPIKE |
| status 的 owner，其他任意 id | 可解析的用户名 | **十进制 id 字符串** | SP-01 | UNIT + SPIKE |
| status 的 group | 可解析的组名 | **恒为十进制 id 字符串** | SP-01, SP-02 | UNIT + SPIKE |
| status 的九个权限位 | 原样映射到 `FsPermission` | 符合 | UT-06 | UNIT |
| status 的 setuid/setgid/sticky 位 | sticky 保留；setuid 与 setgid 不在 Hadoop 模型内，被丢弃 | 符合（源码判断） | UT-06, SEC-08 | NONE |
| `setPermission` | 原样生效 | 符合 | SEC-08 | UNIT |
| `mkdirs` 与 `create` 的权限 | 应用 umask | 符合 | — | UNIT |
| `setOwner` 传数字 user 与 group | 两者都生效 | 符合 | UT-05 | UNIT |
| `setOwner` 只传数字 user | user 生效，group 不动 | 符合（源码判断） | UT-05 | NONE |
| `setOwner` 只传数字 group | group 生效 | 符合 | UT-05 | UNIT |
| `setOwner` 传非数字名字 | 要么生效，要么明确拒绝 | **被忽略，却报告成功** | UT-05, SP-03, SEC-07 | UNIT + SPIKE |
| `setOwner` 传空串或不传 | 不改动任何属性 | 符合（源码判断） | UT-05 | NONE |
| 对仅靠组授权可达的路径做权限检查 | 通过 | **被拒绝；数字组名永远匹配不上** | SP-02 | SPIKE |
| 文件校验和 | 一个可用于内容比对的值 | **不可用；调用方退化为按大小与时间比对** | SP-07, ECO-DCP-02 | SPIKE |
| 官方 `FileContext` 权限套件 | 通过 | 未知 | CT-04 | NONE |
| 在最小 caps 用户下重跑契约套件 | 除根目录类用例外全部通过 | 符合 | CT-08 | CLUSTER |
| 通过 `doAs` 使用多个用户 | 每个用户在 MDS 侧以自己的身份行事 | **每个 UGI 一个 mount，但共用同一个 Ceph 身份** | FN-21 | NONE |
| 以子目录挂载为文件系统根 | 根语义、容量与 block location 均正确；无法向上越界 | 未知 | FN-22 | NONE |
| `setReplication` | 返回 `false`，不抛错误 | 符合 | NEG-01 | UNIT |
| 家目录 | 由 Hadoop 用户名推导 | 符合 | — | UNIT |

## append

append 的决策条件已迁移为 [`catalog.ndjson`](catalog.ndjson) 中 `SEM-APPEND-001` 至
`SEM-APPEND-005` 的 `semantic` records。catalog 是这些条件的唯一 owner；本文不再复制表格。可从
[首条 append record](viewer/index.html#/record/SEM-APPEND-001) 开始浏览，或按轴查询：

```bash
python3 scripts/docs-catalog.py query --kind semantic --where axis=append
python3 scripts/docs-catalog.py show SEM-APPEND-001
```

`FileSystem#append` 的流语义与发行版 shell 包装层是不同边界；CLI 的组合行为仍由 ECO-CLI-04 拥有。

## mkdirs

mkdirs 的决策条件已迁移为 [`catalog.ndjson`](catalog.ndjson) 中 `SEM-MKDIRS-001` 至
`SEM-MKDIRS-010` 的 `semantic` records。catalog 是这些条件的唯一 owner；本文不再复制表格。可从
[首条 mkdirs record](viewer/index.html#/record/SEM-MKDIRS-001) 开始浏览，或按轴查询：

```bash
python3 scripts/docs-catalog.py query --kind semantic --where axis=mkdirs
python3 scripts/docs-catalog.py show SEM-MKDIRS-001
```

## path-entrypoint

本轴记录接入层与路径转换语义。具体配置键名与默认值由源码中的配置常量拥有，不在这里重复展开。

| 决策条件 | 期望行为 | 当前行为 | Guard / 用例 | Coverage |
|---|---|---|---|---|
| `FileSystem` scheme 与根 URI | scheme 为 `ceph`，文件系统 URI 归一化到根 | 符合 | — | UNIT |
| 初始化 URI 带 authority 与非根 path | authority 保留；初始化 path 不成为文件系统根 | 符合 | — | UNIT |
| `FileSystem` 服务发现 | 不显式配置实现类时仍能加载本实现 | 符合 | — | CLUSTER |
| `FileContext` 入口 | 经 `CephFs` 委托主实现，避免双入口语义漂移 | 符合 | — | UNIT + CLUSTER |
| `FileContext` 使用 authorityless `ceph:///` | 不声明默认端口，不被错误补端口后判为 wrong FS | 符合 | — | UNIT + CLUSTER |
| 初始工作目录 | 由 Hadoop 用户/home推导 | 符合 | — | UNIT |
| 设置工作目录 | 绝对路径直接采用；相对路径按当前工作目录拼接 | 符合 | — | UNIT |
| 绑定层收到 null、空路径或相对路径 | 转成 CephFS 根下的绝对路径 | 符合 | UT-07 | UNIT |
| 绑定层收到带 scheme/authority 的路径 | 剥离 scheme 与 authority，只保留 CephFS 内部路径 | 符合 | UT-07 | UNIT |
| 路径含空格、URI 编码或多余斜杠 | 按 Hadoop `Path` 归一化后的路径访问 CephFS | 符合 | UT-07 | UNIT |

## metadata

本轴只补充查询与时间属性语义。owner、group、permission 的身份相关条件仍归 identity 轴所有。

| 决策条件 | 期望行为 | 当前行为 | Guard / 用例 | Coverage |
|---|---|---|---|---|
| 文件 status | 上报文件长度、时间、块大小、复制数视图与 qualified path | 符合 | — | UNIT |
| 目录 status | 标识为目录，长度为 0 | 符合 | — | UNIT |
| status 目标不存在 | `FileNotFoundException` | 符合 | — | UNIT + CONTRACT |
| 查询路径的祖先是普通文件 | `FileNotFoundException` | 符合（源码判断） | UT-02 | NONE |
| `listStatus` 目标是目录 | 返回每个子项的 status | 符合 | — | UNIT + CLUSTER |
| `listStatus` 目标是空目录 | 返回空数组 | 符合 | — | UNIT |
| `listStatus` 目标是文件 | 返回只包含该文件 status 的数组 | 符合 | — | UNIT |
| `listStatus` 目标不存在 | `FileNotFoundException` | 符合 | — | UNIT |
| `setTimes` 只给 mtime | 只修改 mtime | 符合 | — | UNIT + CLUSTER |
| `setTimes` 只给 atime | 只修改 atime | 符合 | — | UNIT |
| `setTimes` 同时给 mtime 与 atime | 两者都修改 | 符合 | — | UNIT |
| `setTimes` 两个时间都为 `-1` | no-op，不调用底层属性修改 | 符合 | — | UNIT |
| `setTimes` 的底层属性修改失败 | 原错误透出，不改写成其他 Hadoop 异常 | 符合 | — | UNIT |

## read

本轴补充输入流内部语义。open 后长度快照导致的可见性限制仍归 visibility 轴所有。

| 决策条件 | 期望行为 | 当前行为 | Guard / 用例 | Coverage |
|---|---|---|---|---|
| open 目标是文件 | 以只读 fd 打开，并以 open 时刻长度初始化输入流 | 符合 | — | UNIT |
| open 目标是目录 | `FileNotFoundException` | 符合 | — | UNIT |
| 顺序读跨缓冲区 | 按序返回数据，到 EOF 后返回 `-1` | 符合 | — | UNIT |
| seek 到当前缓冲区内 | 只移动 Java 侧位置，不重新读底层 fd | 符合 | UT-13 | UNIT |
| seek 到当前缓冲区外 | 移动位置并使旧缓冲失效 | 符合 | UT-13 | UNIT |
| seek 到负数或 open 时刻 EOF 之后 | `EOFException` | 符合 | — | UNIT |
| positioned read | 从给定 offset 读取，且不改变当前流位置 | 符合 | — | UNIT + CLUSTER |
| positioned read 位于 open 时刻 EOF 及之后 | 返回 `-1` | 符合（源码判断） | UT-13 | NONE |
| `available` 与 `seekToNewSource` | `available` 按剩余快照长度计算；`seekToNewSource` 返回 `false` | 符合 | — | UNIT |
| 输入流 close | 释放 fd 且幂等；close 后 read/seek 以 `IOException` 拒绝 | 符合 | UT-12 | UNIT |

## status-defaults

本轴记录容量、服务默认值、内容汇总与能力声明语义。实际 Ceph 数据保护仍由 pool 策略控制。

| 决策条件 | 期望行为 | 当前行为 | Guard / 用例 | Coverage |
|---|---|---|---|---|
| `getStatus(null)` | 查询根路径，并把 statfs 映射为 capacity/used/remaining | 符合 | — | UNIT |
| `getStatus(path)` | 查询给定路径所在文件系统的容量视图 | 符合 | FN-29 | UNIT + CLUSTER |
| statfs 的 fragment size 不可用 | 回退到 block size 计算容量 | 符合 | UT-04 | UNIT |
| 带 path 的默认块大小查询 | 与全局默认块大小一致 | 符合 | — | UNIT |
| 服务默认值中的 checksum | 声明为不可用的 checksum 类型 | 符合 | — | UNIT |
| 服务默认值中的块、复制数与缓冲视图 | 来自连接器配置与 Hadoop 服务默认值模型 | 符合 | — | UNIT |
| `getContentSummary` | 复用基类遍历 list/status 得到汇总 | 符合 | FN-24 | UNIT |
| path capability | 声明支持 append 与权限；其他能力沿用基类结果 | 符合（源码判断） | — | NONE |

## block-location

Hadoop block location 在本连接器中只是调度提示，不表示 Hadoop 控制 Ceph 副本或放置策略。

| 决策条件 | 期望行为 | 当前行为 | Guard / 用例 | Coverage |
|---|---|---|---|---|
| file status 为 null | 返回 null | 符合 | UT-09 | UNIT |
| start 或 len 为负数 | `IllegalArgumentException` | 符合 | UT-09 | UNIT |
| 目标是目录、空文件、零长度请求或 start 已到 EOF | 返回空数组 | 符合 | UT-09 | UNIT + CLUSTER |
| 请求范围超过文件末尾或 len 极大 | 末端钳制到文件长度且不溢出 | 符合 | UT-09 | UNIT + CLUSTER |
| 范围跨 Ceph extent/object 边界 | 按 extent 剩余长度切分 block location | 符合 | — | UNIT + CLUSTER |
| start 非对齐或落在尾块内 | 首块和尾块长度按真实范围裁剪 | 符合 | — | UNIT + CLUSTER |
| extent 带 OSD id | 解析为 host/name 数组 | 符合 | — | UNIT + CLUSTER |
| 同一次调用内重复 OSD | OSD 地址只解析一次 | 符合 | — | UNIT |
| rack/topology 信息 | 不声明机架路径 | 符合 | — | UNIT |
| OSD 地址解析失败、地址为空或 OSD 列表为空 | 降级为 `localhost`，不让调用失败 | 符合 | UT-08 | UNIT |
| extent 查询失败 | 降级为 `localhost`，并按 block size 步进 | 符合 | UT-08 | UNIT |
| extent 长度非法 | 按单个 extent 失败处理并降级 | 符合（源码判断） | UT-08 | NONE |
| 临时 fd 生命周期 | 成功、降级或异常路径都关闭临时 fd | 符合 | UT-08 | UNIT |
| 一个 extent 返回多个 OSD | 每个 OSD 都进入 host/name 数组 | 符合（源码判断） | FN-25 | NONE |

## 维护规则

- 已迁移轴先把决策条件及其 Basis 作为 catalog record 加进来，再写断言；待迁移轴暂时增加表格行。项目自定
  语义必须先有 ADR 或 limitation。
- 已迁移轴中，`guards` 为空且 Coverage 为 `NONE` 的 record，要么补精确断言或稳定用例定义，要么明确保留为
  无守护项。
- `DIFFERENT` 必须链接 [`KNOWN-LIMITATIONS.md`](KNOWN-LIMITATIONS.md) 或 ADR；不能再用粗体隐含分类。
- Coverage 只描述守护机制。是否运行以及运行结果只更新 catalog `run` records 与 dated report；readiness 只由
  显式决策更新。
- 要放宽或收紧一条期望，修改其 canonical record 或待迁移行，不要改用例文件。
- 删除一个条件之前，先确认没有用例委托到它；删掉某个委托用例的最后一个条件，会让该用例没有期望结果。
- `scripts/check-docs.sh` 校验 catalog schema、Basis、Classification、Coverage、偏离跟踪、引用完整性，并校验
  每个用例 ID 在用例文件中恰好定义一次、每个委托用例至少被一个 record 或一行引用。迁移期间它还锁定每个
  语义轴的条件数与 Coverage 计数，避免未迁移表格中的无引用行被静默删除。
- 套件级、性能、spike 与生态用例保留各自的期望结果。它们的期望不在决策条件粒度上，因此即使有行引用它们，
  本矩阵也不拥有其期望。
