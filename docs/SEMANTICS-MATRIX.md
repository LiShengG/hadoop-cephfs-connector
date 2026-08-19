# 语义覆盖矩阵

## 用途

本文档按操作而非测试阶段来索引核心文件系统语义。[`TEST-PLAN.md`](TEST-PLAN.md) 与
[`TEST-CASES-ECO.md`](TEST-CASES-ECO.md) 按层次和组件组织定义，一个操作因此被打散到多个分组里。
本矩阵提供正交视角：一行一个决策条件，说明该操作应当做什么、今天实际做了什么、以及这一点被守得有多牢。

本矩阵拥有决策条件粒度上的期望行为。凡是本会重复陈述期望的用例定义，一律委托到这里，使一条期望只存在于
一处，无法在文件之间发生漂移。实际行为由源码拥有，用例的身份与范围由两个用例文件拥有，执行结果由带日期的
[`reports/`](reports/) 拥有。

## 如何读一行

| 列 | 含义 |
|---|---|
| 决策条件 | 输入情形 |
| 期望行为 | 该操作应当做什么 |
| 当前行为 | 相对于期望，今天实际做了什么 |
| 用例 ID | 范围覆盖该条件的用例；没有则为 `—` |
| 状态 | 当前行为被守得有多牢 |

`期望行为` 在 Hadoop 文件系统规范有明确规定处遵循规范；规范允许一个取值范围时遵循 HDFS，因为生态组件是
照着 HDFS 的行为写的；两者都无法定夺时由本矩阵决定，而该决定需要在 ADR 或
[`KNOWN-LIMITATIONS.md`](KNOWN-LIMITATIONS.md) 中留下理由。

一个用例 ID 可以出现在多行上。这些行合起来才是该用例完整的期望结果，单独一行永远不是全部。

`当前行为` 有三种写法：

| 写法 | 含义 |
|---|---|
| `符合` | 行为与期望一致，且有断言守着 |
| `符合（无断言）` | 源码读起来与期望一致，但没有任何断言守着 |
| **粗体文字** | 与期望不一致；文字给出实际行为 |

每一个**粗体**格子要么是缺陷，要么是有意为之且已记录在案的偏离。只扫这一列就能找出全部问题。

`状态` 取值：

| 状态 | 含义 |
|---|---|
| `UNIT` | 由 mock 单测守着；不需要集群，每次构建都跑 |
| `CONTRACT` | 由 Hadoop 官方契约套件守着；需要集群与测试门控 |
| `CLUSTER` | 由本仓库的集成测试守着；需要集群与测试门控 |
| `SPIKE` | 只被脚本探针观察过一次，无法让构建失败 |
| `NOT_RUN` | 有用例 ID 认领，但既无断言也无带日期的结果 |
| `GAP` | 既无用例 ID 也无断言 |

要找到守着某一行的测试，按操作与条件去测试树里搜；mock 测试类是按其覆盖的操作分组命名的。

## 覆盖概览

| 轴 | 行数 | 偏离 | UNIT | CONTRACT + CLUSTER | SPIKE | NOT_RUN | GAP |
|---|---|---|---|---|---|---|---|
| rename | 26 | 4 | 18 | 6 | 1 | 4 | 3 |
| create | 21 | 2 | 11 | 3 | 0 | 6 | 4 |
| delete | 10 | 1 | 7 | 3 | 0 | 2 | 1 |
| sync | 16 | 0 | 7 | 3 | 0 | 5 | 3 |
| visibility | 5 | 3 | 1 | 2 | 1 | 2 | 0 |
| identity | 20 | 6 | 11 | 1 | 6 | 6 | 0 |
| append | 5 | 1 | 3 | 1 | 0 | 1 | 1 |
| mkdirs | 10 | 0 | 7 | 1 | 0 | 3 | 0 |
| **合计** | **113** | **17** | **65** | **20** | **8** | **29** | **12** |

一行可以同时带多个状态，因此各状态列之和不等于行数。这些数字应当从下面的表格重新算出，而不是手工编辑。

`偏离` 统计当前行为与期望不一致的行。`NOT_RUN` 与 `GAP` 合起来是执行积压。`SPIKE` 标记那些已知但无人守护
的行为——它们发生回归时是静默的。

## rename

`FileSystem#rename(Path, Path)` —— 返回布尔值的入口。它以返回 `false` 表示失败，于是大量互不相同的条件
坍缩到同一个返回值上，每种都需要单独一行。

| 决策条件 | 期望行为 | 当前行为 | 用例 ID | 状态 |
|---|---|---|---|---|
| src 不存在 | `false` | 符合 | — | UNIT + CONTRACT |
| src 是根目录 | `false` | 符合 | — | UNIT |
| src 与 dst 相同且存在 | `true`，空操作 | 符合 | — | UNIT |
| dst 已存在且是文件 | `false`，目标不受影响 | 符合 | FN-18 | UNIT + CONTRACT |
| dst 已存在且是目录 | 以源名移入该目录之下 | 符合 | FN-18 | UNIT |
| dst 是目录且目标名已被占用 | `false` | 符合 | FN-18 | UNIT |
| rename 到自己的父目录 | `true`，空操作 | 符合 | — | UNIT |
| dst 的父目录不存在 | `false`，不创建任何目录 | 符合 | — | UNIT + CONTRACT |
| dst 的父路径是文件 | `false` | 符合 | — | UNIT |
| 目录 rename 到自身子树内 | `false` | 符合 | FN-18 | UNIT |
| 文件 rename 到不存在的 dst | `true` | 符合 | — | UNIT + CONTRACT |
| 目录 rename 到不存在的 dst | `true` | 符合 | — | UNIT |
| 在根一级上 rename | `true` | 符合 | — | UNIT |
| 相对路径 | 按工作目录解析 | 符合 | — | UNIT |
| 绑定层抛出"路径不存在"以外的错误 | 转换为 Hadoop 异常后抛出 | 符合（无断言） | — | GAP |
| 存在性检查与调用之间 dst 被创建 | 目标绝不被静默替换 | **静默覆盖 dst** | — | GAP |
| 两个线程 rename 同一个 src | 恰有一个成功；失败方报 `false` 或"路径不存在"，无中间态 | 未验证 | FN-15 | NOT_RUN |
| 在 100 层深的树内 rename | 成功且不栈溢出 | 未验证 | FN-05 | NOT_RUN |
| 目录 rename 到一个已存在的空目录 | 移入其中，而非替换它 | 未验证 | FN-18 | NOT_RUN |

`FileSystem#rename(Path, Path, Options.Rename...)` —— `FileContext` 的入口，以抛异常表示失败。普通文件的
no-replace rename 用一个 MDS 硬链接抢占目标
（[ADR-0004](adr/0004-atomic-no-replace-for-regular-files.md)）。

| 决策条件 | 期望行为 | 当前行为 | 用例 ID | 状态 |
|---|---|---|---|---|
| 普通文件，no-replace，dst 不存在 | 成功；抢占是原子的 | 符合 | SP-04 | UNIT + CLUSTER |
| 普通文件，no-replace，dst 已存在 | `FileAlreadyExistsException` | 符合 | SP-04 | UNIT + CLUSTER |
| 抢占成功，源已被移除 | 视为成功 | 符合 | — | UNIT |
| 抢占成功，但移除源失败 | 回滚目标并抛出错误 | 符合 | — | UNIT |
| 请求 overwrite | 原子地替换目标 | **委托给基类；基类默认实现非原子——先 `delete(dst, false)` 再 `rename(src, dst)`**（LIM-009） | FN-31 | NOT_RUN |
| 源是目录 | 原子地保证 no-replace | **无原子抢占；目录不能被硬链接**（LIM-005） | — | GAP |
| 抢占与移除源之间发生崩溃 | 只留下一个名字 | **两个名字都留了下来**（LIM-005） | — | SPIKE |

## create

`FileSystem#create` 与两个 `createNonRecursive` 重载共用同一条内部路径，因此在一个入口上得到验证的条件，
并不自动在其他入口上成立。

| 决策条件 | 期望行为 | 当前行为 | 用例 ID | 状态 |
|---|---|---|---|---|
| `overwrite=false`，dst 已存在且是文件 | `FileAlreadyExistsException` | 符合 | — | UNIT |
| `overwrite=true`，dst 已存在且是文件 | 原有内容被截断 | 符合 | — | UNIT + CLUSTER |
| `overwrite=true`，dst 已存在且是目录 | `FileAlreadyExistsException` | 符合 | — | UNIT + CONTRACT |
| `overwrite=false`，dst 已存在且是目录 | `FileAlreadyExistsException` | 符合（无断言） | — | GAP |
| 父目录不存在，递归 create | 先建父目录，再建文件 | 符合 | — | UNIT |
| 父目录不存在，`createNonRecursive` | `FileNotFoundException`；不创建父目录 | 符合 | — | UNIT |
| 父路径是文件，递归 create | `ParentNotDirectoryException` | 符合 | — | UNIT + CONTRACT |
| 父路径是文件，`createNonRecursive` | `ParentNotDirectoryException` | 符合（无断言） | — | GAP |
| 绑定层报告目标不是目录 | `ParentNotDirectoryException` | 符合 | UT-01 | UNIT |
| 更上层的某个祖先是文件 | `ParentNotDirectoryException` | 符合（无断言） | UT-02 | NOT_RUN |
| flag 重载，仅 create | 独占创建 | 符合 | — | UNIT |
| flag 重载，overwrite | 原有内容被截断 | 符合 | — | UNIT |
| flag 重载，不支持的 flag 组合 | 被 flag 校验拒绝 | **被忽略；flag 从不校验** | — | GAP |
| 两个线程 create 同一路径，`overwrite=false` | 恰有一个成功；其余全部得到 `FileAlreadyExistsException` | 未验证 | FN-17 | NOT_RUN |
| 前置检查之后路径被并发创建，`overwrite=true` | 成功并截断 | **以 `FileAlreadyExistsException` 失败** | — | GAP |
| 调用方给定的 block size | 向上取整到 64 KiB 的整数倍，以 layout 上限封顶，且永不溢出 | 符合 | UT-03 | UNIT |
| 配置了多个 data pool | 使用第一个 | 符合 | — | UNIT |
| 空文件与稀疏写 | 长度与内容符合 POSIX；block location 仍能解析 | 未验证 | FN-09 | NOT_RUN |
| 文件名含空格、UTF-8 或 URI 保留字符 | 经 API 与 CLI 创建、列举、读取、删除的行为一致 | 未验证 | FN-06 | NOT_RUN |
| 文件名处于以及超过 255 字节上限 | 处于上限时成功；超过时以可诊断的错误失败 | 未验证 | FN-07 | NOT_RUN |
| 路径总长接近 4096 字节 | 有一个固定且已记录在案的结果 | 未验证 | FN-08 | NOT_RUN |

## delete

根目录非递归删除返回 `false` 是本项目有意选择的语义：`filesystem.md` 允许对根目录做特殊处理，
契约基类期望抛出异常，`ITestCephContractRootDirectory` 以 `@Override` 改写为验证本实现的语义。

`fs.trash.interval` 是 `FsShell`/`Trash` 包装层的行为，包装层通过 `rename` 把路径移进回收站，`CephFileSystem#delete`
不读取该配置，因此回收站语义由 FN-23 与 ECO-CLI-06 自行拥有，不在本矩阵内。

| 决策条件 | 期望行为 | 当前行为 | 用例 ID | 状态 |
|---|---|---|---|---|
| 路径不存在 | `false` | 符合 | — | UNIT + CONTRACT |
| 文件 | 被删除，`true` | 符合 | — | UNIT |
| 空目录，非递归 | 被删除，`true` | 符合 | — | UNIT |
| 非空目录，非递归 | `PathIsNotEmptyDirectoryException` | 符合 | — | UNIT + CONTRACT |
| 非空目录，递归 | 整棵子树被删除 | 符合；遍历非原子 | — | UNIT |
| 根目录，非递归 | 返回 `false`，根目录与其子项均保留 | 符合 | — | UNIT + CLUSTER |
| 根目录，递归 | 子项被删除，根目录保留 | 符合 | — | UNIT |
| 递归遍历中途失败 | 有明确定义的返回值或异常 | **无定义；部分删除** | — | GAP |
| 递归 delete 期间有子项被创建 | 不抛非预期错误，且最终状态可解释 | 未验证 | FN-16 | NOT_RUN |
| 100 层深的树，递归 | 成功 | 未验证 | FN-05 | NOT_RUN |

## sync

`hflush` 与 `hsync` 的开销差异属于性能问题，由 PERF-10 判定，不在本矩阵内。

| 决策条件 | 期望行为 | 当前行为 | 用例 ID | 状态 |
|---|---|---|---|---|
| 跨缓冲区边界的缓冲写 | 所有字节按序送达 | 符合 | — | UNIT |
| `hflush` | 已写数据对新开的 reader 可见 | 符合 | — | UNIT + CONTRACT |
| `hsync` | 已写数据落到持久化 | 符合 | — | UNIT + CONTRACT |
| `hflush` 与 `hsync` 的相对强度 | `hflush` 至少保证"对新 reader 可见"，允许与 `hsync` 同等强度 | 符合（无断言）；`hflush` 直接委托 `hsync`，没有廉价的仅可见性 flush | — | GAP |
| 普通 `flush()` | 排空缓冲区；不承诺持久化 | 符合（无断言） | — | GAP |
| 缓冲区为空时调用 `hsync` | 仍然发起 sync | 符合（无断言） | — | GAP |
| `close` | 排空、sync、释放；幂等 | 符合 | — | UNIT |
| 排空失败之后再 `close` | 释放描述符并抛出错误 | 符合 | UT-11 | UNIT |
| 排空失败且释放也失败 | 抛出第一个错误，第二个进 suppressed | 符合（无断言） | UT-11 | NOT_RUN |
| close 之后再 write/read/seek/sync | 以 `IOException` 拒绝 | 符合 | UT-12 | UNIT |
| 底层写只接受了缓冲区的一部分 | 重试直到写完；返回 0 或超过请求量则为 `IOException` | 未验证 | UT-10 | NOT_RUN |
| 流声明的 capability | 声明支持 flush 与 sync | 符合 | — | UNIT |
| 同一 mount 内 sync 之后的长度 | 立即反映出来 | 符合 | — | CONTRACT |
| `hsync` 之后杀掉客户端 | 已 sync 的数据全部存活，且文件仍可 append | 未验证 | FN-13 | NOT_RUN |
| 任何 flush 之前杀掉客户端 | 允许丢数据，但长度与内容不得互相矛盾 | 未验证 | FN-14 | NOT_RUN |
| 高频小量 `hflush` | 吞吐可接受 | 未验证 | PERF-10 | NOT_RUN |

## visibility

append 的起始位置同样源于 open 时刻的长度快照，该决策条件归 append 轴所有，此处不重复列出。

| 决策条件 | 期望行为 | 当前行为 | 用例 ID | 状态 |
|---|---|---|---|---|
| 在 writer sync 之后打开的 reader | 看到全部已 sync 的数据 | 符合 | FN-12 | CLUSTER |
| 在 writer 追加之前就已打开的 reader | 能观察到 open 之后才 sync 的数据 | **永远观察不到；长度在 open 时刻被定死** | FN-11, SP-05 | SPIKE + NOT_RUN |
| seek 越过 open 时刻记下的长度 | 只有越过真实文件末尾才被拒绝 | **越过记下的长度就被拒绝** | — | UNIT |
| 新创建的文件 | 立即可见 | 符合 | — | CONTRACT |
| 读取一个仍在被写入的文件 | 可读到最后一次 sync 为止 | **被 open 时刻的长度挡住** | ECO-HB-02, ECO-HB-03 | NOT_RUN |

## identity

| 决策条件 | 期望行为 | 当前行为 | 用例 ID | 状态 |
|---|---|---|---|---|
| status 的 owner，id 等于进程 id | Hadoop 用户名 | 符合 | SP-01 | UNIT + SPIKE |
| status 的 owner，其他任意 id | 可解析的用户名 | **十进制 id 字符串** | SP-01 | UNIT + SPIKE |
| status 的 group | 可解析的组名 | **恒为十进制 id 字符串** | SP-01, SP-02 | UNIT + SPIKE |
| status 的九个权限位 | 原样映射到 `FsPermission` | 符合 | UT-06 | UNIT |
| status 的 setuid/setgid/sticky 位 | sticky 保留；setuid 与 setgid 不在 Hadoop 模型内，被丢弃 | 符合（无断言） | UT-06, SEC-08 | NOT_RUN |
| `setPermission` | 原样生效 | 符合 | SEC-08 | UNIT |
| `mkdirs` 与 `create` 的权限 | 应用 umask | 符合 | — | UNIT |
| `setOwner` 传数字 user 与 group | 两者都生效 | 符合 | UT-05 | UNIT |
| `setOwner` 只传数字 user | user 生效，group 不动 | 符合（无断言） | UT-05 | NOT_RUN |
| `setOwner` 只传数字 group | group 生效 | 符合 | UT-05 | UNIT |
| `setOwner` 传非数字名字 | 要么生效，要么明确拒绝 | **被忽略，却报告成功** | UT-05, SP-03, SEC-07 | UNIT + SPIKE |
| `setOwner` 传空串或不传 | 不改动任何属性 | 符合（无断言） | UT-05 | NOT_RUN |
| 对仅靠组授权可达的路径做权限检查 | 通过 | **被拒绝；数字组名永远匹配不上** | SP-02 | SPIKE |
| 文件校验和 | 一个可用于内容比对的值 | **不可用；调用方退化为按大小与时间比对** | SP-07, ECO-DCP-02 | SPIKE |
| 官方 `FileContext` 权限套件 | 通过 | 未验证 | CT-04 | NOT_RUN |
| 在最小 caps 用户下重跑契约套件 | 除根目录类用例外全部通过 | 符合 | CT-08 | CLUSTER |
| 通过 `doAs` 使用多个用户 | 每个用户在 MDS 侧以自己的身份行事 | **每个 UGI 一个 mount，但共用同一个 Ceph 身份** | FN-21 | NOT_RUN |
| 以子目录挂载为文件系统根 | 根语义、容量与 block location 均正确；无法向上越界 | 未验证 | FN-22 | NOT_RUN |
| `setReplication` | 返回 `false`，不抛错误 | 符合 | NEG-01 | UNIT |
| 家目录 | 由 Hadoop 用户名推导 | 符合 | — | UNIT |

## append

| 决策条件 | 期望行为 | 当前行为 | 用例 ID | 状态 |
|---|---|---|---|---|
| 已存在的文件 | 在末尾打开并追加 | 符合 | — | UNIT + CLUSTER |
| 不存在的文件 | 拒绝；不创建该文件 | 符合 | — | UNIT |
| 目录 | 以 `IOException` 拒绝 | 符合；但报出的类型是"路径不存在" | — | UNIT |
| 另一个 writer 正在追加时报告的起始位置 | 反映真实长度 | **是 open 时刻的陈旧快照** | — | GAP |
| shell 的 append 命令 | 经发行版 CLI 完成追加 | 未验证 | ECO-CLI-04 | NOT_RUN |

## mkdirs

| 决策条件 | 期望行为 | 当前行为 | 用例 ID | 状态 |
|---|---|---|---|---|
| 新目录 | 创建时应用 umask | 符合 | — | UNIT + CONTRACT |
| 目标已经是目录 | `true`，幂等 | 符合 | — | UNIT |
| 目标已经是文件 | `FileAlreadyExistsException` | 符合 | — | UNIT |
| 父路径是文件 | `ParentNotDirectoryException` | 符合 | — | UNIT |
| 被并发创建为目录 | `true`，幂等 | 符合 | — | UNIT |
| 被并发创建为文件 | `FileAlreadyExistsException` | 符合 | — | UNIT |
| 相对路径 | 按工作目录解析 | 符合 | — | UNIT |
| 100 层深的树 | 创建成功且不栈溢出 | 未验证 | FN-05 | NOT_RUN |
| 官方 `FileContext` create-mkdir 套件 | 通过 | 未验证 | CT-03 | NOT_RUN |
| `FileSystem` 与 `FileContext` 的通用操作大套件 | 通过 | 未验证 | CT-01, CT-02 | NOT_RUN |

## 维护规则

- 先把决策条件作为一行加进来，再去写它的断言，这样缺口是可见的。
- `用例 ID` 为 `—` 且状态为 `GAP` 的行，要么在用例文件里补一条定义，要么明确决定它保持无断言。
- **粗体**的当前行为若属缺陷，登记到 [`KNOWN-LIMITATIONS.md`](KNOWN-LIMITATIONS.md)；若属有意偏离，
  登记到 ADR 或契约声明，并从该行引用过去。
- 要放宽或收紧一条期望，改这里，不要改用例文件。委托的用例根本没有期望文字可改——这正是委托的意义。
- 删除一行之前，先确认没有用例委托到它。删掉某个委托用例的最后一行，会让该用例完全没有期望结果。
- `scripts/check-docs.sh` 校验这里引用的每个用例 ID 在用例文件中恰好定义一次，并校验每个委托用例至少被
  一行引用。
- 套件级、性能、spike 与生态用例保留各自的期望结果。它们的期望不在决策条件粒度上，因此即使有行引用它们，
  本矩阵也不拥有其期望。
