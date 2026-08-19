# 语义覆盖矩阵

## 用途

本文档按操作而非测试阶段来索引核心文件系统语义。[`TEST-PLAN.md`](TEST-PLAN.md) 与
[`TEST-CASES-ECO.md`](TEST-CASES-ECO.md) 按层次和组件组织定义，一个操作因此被打散到多个分组里。
本矩阵提供正交视角：一行一个决策条件，说明该操作应当做什么、依据是什么、今天实际做了什么，以及哪一个精确
断言守着它。

本矩阵拥有决策条件粒度上的期望行为。凡是本会重复陈述期望的用例定义，一律委托到这里，使一条期望只存在于
一处，无法在文件之间发生漂移。实际行为由源码拥有，用例的身份与范围由两个用例文件拥有，执行结果由带日期的
[`reports/`](reports/) 拥有。

## Current scope（当前范围）

当前清单覆盖 13 个轴、169 个决策条件：rename、create、delete、sync、visibility、identity、append、mkdirs、
path-entrypoint、metadata、read、status-defaults 与 block-location。这是当前已盘点范围，不是完整的 Hadoop
FileSystem SPI 语义清单，也不构成未列出能力已经兼容的声明。

rename 是第一个完成新八列 schema 迁移的轴；原来的同路径条件按普通文件与目录拆开，因此由 26 行变为 27 行。
其余轴暂时保留五列形式，后续按同一 schema 逐轴迁移；它们的 `Guard / 用例` 与 `Coverage` 已先完成解耦。

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

`Basis` 可以组合以下值：

| Basis | 依据 |
|---|---|
| `HADOOP-SPEC` | [Hadoop 3.3.6 FileSystem specification](https://hadoop.apache.org/docs/r3.3.6/hadoop-project-dist/hadoop-common/filesystem/filesystem.html) 与 [FileContext API](https://hadoop.apache.org/docs/r3.3.6/api/org/apache/hadoop/fs/FileContext.html) |
| `HDFS-3.3.6` | [HDFS 3.3.6 `FSDirRenameOp`](https://github.com/apache/hadoop/blob/rel/release-3.3.6/hadoop-hdfs-project/hadoop-hdfs/src/main/java/org/apache/hadoop/hdfs/server/namenode/FSDirRenameOp.java) 的实际分支顺序与结果 |
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

一个用例 ID 可以出现在多行上。这些行合起来才是该用例完整的期望结果，单独一行永远不是全部。已迁移轴的
自动化覆盖必须写出 `Class#method`，不能要求读者按条件搜索测试树；待迁移轴尚未补齐这层追溯。
`NOT_RUN`、`PASS`、`FAIL` 等执行状态不进入本矩阵，只由带日期的 [`reports/`](reports/) 与
[`READINESS.md`](READINESS.md) 持有。

尚未迁移的五列表格以 `符合`、`符合（源码判断）`、**粗体差异**与`未知`临时表达分类；新 schema 不再使用这种
隐式约定。

## Classification 概览

| 范围 | 行数 | MATCH | DIFFERENT | UNSUPPORTED | UNKNOWN |
|---|---|---|---|---|---|
| rename（已迁移） | 27 | 20 | 5 | 0 | 2 |
| 其余 12 个轴（待迁移） | 142 | — | — | — | — |

## Coverage 概览

| 轴 | 行数 | UNIT | CONTRACT | CLUSTER | SPIKE | NONE |
|---|---|---|---|---|---|---|
| rename | 27 | 18 | 4 | 6 | 1 | 8 |
| create | 21 | 11 | 2 | 1 | 0 | 10 |
| delete | 10 | 7 | 2 | 1 | 0 | 3 |
| sync | 16 | 7 | 3 | 0 | 0 | 8 |
| visibility | 5 | 1 | 1 | 1 | 1 | 1 |
| identity | 20 | 11 | 0 | 1 | 6 | 6 |
| append | 5 | 3 | 0 | 1 | 0 | 2 |
| mkdirs | 10 | 7 | 1 | 0 | 0 | 3 |
| path-entrypoint | 10 | 9 | 0 | 3 | 0 | 0 |
| metadata | 13 | 12 | 1 | 2 | 0 | 1 |
| read | 10 | 9 | 0 | 1 | 0 | 1 |
| status-defaults | 8 | 7 | 0 | 1 | 0 | 1 |
| block-location | 14 | 12 | 0 | 5 | 0 | 2 |
| **合计** | **169** | **114** | **14** | **23** | **8** | **46** |

一行可以同时带多个 Coverage，因此各列之和不等于行数；`NONE` 不与其他 Coverage 组合。数字应从下面的表格
重新算出，而不是手工编辑。`SPIKE` 只能说明曾有探针观察，不能代替回归断言或最近执行结果。

## rename

`FileSystem#rename(Path, Path)` —— 返回布尔值的入口。它以返回 `false` 表示失败，于是大量互不相同的条件
坍缩到同一个返回值上，每种都需要单独一行。

Hadoop 3.3.6 的规范没有规定目录 self-rename 的返回值；这里采用 HDFS 3.3.6 实现。旧 rename 先通过
`dstForRenameTo()` 把目录目的路径展开为 `dst/basename(src)`，再检查路径相等，因此目录 `/a/dir` 到自身会变成
`/a/dir/dir` 并失败；普通文件不会发生这次展开。

| 决策条件 | 期望行为 | Basis | 当前行为 | Classification | Guard / 用例 | Coverage | Limitation / ADR |
|---|---|---|---|---|---|---|---|
| src 不存在 | `false` | `HDFS-3.3.6` | 返回 `false` | `MATCH` | `TestCephFileSystemMetaRename#testSrcMissingReturnsFalse`<br>`ITestCephContractRename#testRenameNonexistentFile`<br>`ITestCephFileSystemMeta#testRenameSemantics` | UNIT + CONTRACT + CLUSTER | — |
| src 是根目录 | `false` | `HADOOP-SPEC` + `HDFS-3.3.6` | 返回 `false` | `MATCH` | `TestCephFileSystemMetaRename#testRenameRootReturnsFalse` | UNIT | — |
| 普通文件，src 与 dst 相同且存在 | `true`，空操作 | `HADOOP-SPEC` | 返回 `true`，不调用底层 rename | `MATCH` | `TestCephFileSystemMetaRename#testSrcEqualsDstReturnsTrue` | UNIT | — |
| 目录，src 与 dst 相同且存在 | `false`，目录保持不变 | `HDFS-3.3.6` | 在目录目的路径展开前直接返回 `true` | `DIFFERENT` | — | NONE | [LIM-010](KNOWN-LIMITATIONS.md#lim-010-legacy-directory-self-rename-diverges-from-hdfs) |
| dst 已存在且是文件 | `false`，目标不受影响 | `HDFS-3.3.6` | 返回 `false`，目标不受影响 | `MATCH` | `TestCephFileSystemMetaRename#testDstExistingFileReturnsFalse`<br>`ITestCephContractRename#testRenameFileOverExistingFile`<br>`ITestCephFileSystemMeta#testRenameSemantics`<br>FN-18 | UNIT + CONTRACT + CLUSTER | — |
| dst 已存在且是目录 | 以源名移入该目录之下 | `HADOOP-SPEC` + `HDFS-3.3.6` | 将目标展开为 `dst/basename(src)` 后调用底层 rename | `MATCH` | `TestCephFileSystemMetaRename#testDstExistingDirectoryMovesUnderIt`<br>`ITestCephFileSystemMeta#testRenameSemantics`<br>FN-18 | UNIT + CLUSTER | — |
| dst 是目录且目标名已被占用 | `false` | `HDFS-3.3.6` | 返回 `false` | `MATCH` | `TestCephFileSystemMetaRename#testDstDirectoryWithOccupiedNameReturnsFalse`<br>FN-18 | UNIT | — |
| rename 到自己的父目录 | `true`，空操作 | `HDFS-3.3.6` | 展开后的目标等于 src，返回 `true` | `MATCH` | `TestCephFileSystemMetaRename#testRenameIntoOwnParentReturnsTrue` | UNIT | — |
| dst 的父目录不存在 | `false`，不创建任何目录 | `HDFS-3.3.6` | 返回 `false`，不创建目录 | `MATCH` | `TestCephFileSystemMetaRename#testDstParentMissingReturnsFalse`<br>`ITestCephContractRename#testRenameFileNonexistentDir` | UNIT + CONTRACT | — |
| dst 的父路径是文件 | `false` | `HDFS-3.3.6` | 返回 `false` | `MATCH` | `TestCephFileSystemMetaRename#testDstParentIsFileReturnsFalse` | UNIT | — |
| 目录 rename 到自身子树内 | `false` | `HDFS-3.3.6` | 返回 `false` | `MATCH` | `TestCephFileSystemMetaRename#testRenameDirectoryIntoOwnSubtreeReturnsFalse`<br>FN-18 | UNIT | — |
| 文件 rename 到不存在的 dst | `true` | `HADOOP-SPEC` | 调用底层 rename 并返回 `true` | `MATCH` | `TestCephFileSystemMetaRename#testSimpleRenameSucceeds`<br>`ITestCephContractRename#testRenameNewFileSameDir`<br>`ITestCephFileSystemMeta#testRenameSemantics` | UNIT + CONTRACT + CLUSTER | — |
| 目录 rename 到不存在的 dst | `true` | `HADOOP-SPEC` | 调用底层 rename 并返回 `true` | `MATCH` | `TestCephFileSystemMetaRename#testRenameDirectory` | UNIT | — |
| 在根一级上 rename | `true` | `HADOOP-SPEC` | 调用底层 rename 并返回 `true` | `MATCH` | `TestCephFileSystemMetaRename#testRenameAtRootLevel` | UNIT | — |
| 相对路径 | 按工作目录解析 | `HADOOP-SPEC` | 先按工作目录转成绝对路径 | `MATCH` | `TestCephFileSystemMetaRename#testRenameRelativePaths` | UNIT | — |
| 绑定层抛出"路径不存在"以外的错误 | 转换为 Hadoop 异常后抛出 | `HADOOP-SPEC` | 经 `mapCephException` 转换后抛出 | `MATCH` | — | NONE | — |
| 存在性检查与调用之间 dst 被创建 | 目标绝不被静默替换 | `HADOOP-SPEC` + `HDFS-3.3.6` | 底层 POSIX rename 可静默覆盖竞态创建的 dst | `DIFFERENT` | — | NONE | [LIM-005](KNOWN-LIMITATIONS.md#lim-005-no-replace-rename-boundaries) |
| 两个线程 rename 同一个 src | 恰有一个成功；失败方报 `false` 或"路径不存在"，无中间态 | `HADOOP-SPEC` | 尚未验证并发可观察结果 | `UNKNOWN` | FN-15 | NONE | — |
| 在 100 层深的树内 rename | 成功且不栈溢出 | `HADOOP-SPEC` | 尚未验证深路径结果 | `UNKNOWN` | FN-05 | NONE | — |
| 目录 rename 到一个已存在的空目录 | 移入其中，而非替换它 | `HDFS-3.3.6` | 源码将目标展开为 `dst/basename(src)` | `MATCH` | FN-18 | NONE | — |

`FileSystem#rename(Path, Path, Options.Rename...)` —— `FileContext` 的入口，以抛异常表示失败。普通文件的
no-replace rename 用一个 MDS 硬链接抢占目标
（[ADR-0004](adr/0004-atomic-no-replace-for-regular-files.md)）。

| 决策条件 | 期望行为 | Basis | 当前行为 | Classification | Guard / 用例 | Coverage | Limitation / ADR |
|---|---|---|---|---|---|---|---|
| 普通文件，no-replace，dst 不存在 | 成功；dst 抢占是原子的 | `HADOOP-SPEC` + `PROJECT-ADR-0004` | 以 hard link 原子抢占，再移除源 | `MATCH` | `TestCephFileSystemMetaRename#testNoOverwriteFileRenameUsesLinkThenUnlink`<br>`ITestCephFileContext#testRenameToAbsentDestination`<br>SP-04 | UNIT + CLUSTER | [ADR-0004](adr/0004-atomic-no-replace-for-regular-files.md) |
| 普通文件，no-replace，dst 已存在 | `FileAlreadyExistsException`，两端不变 | `HADOOP-SPEC` + `PROJECT-ADR-0004` | 预检查或 hard-link 抢占冲突均映射为该异常 | `MATCH` | `TestCephFileSystemMetaRename#testNoOverwriteFileRenameMapsAtomicLinkConflict`<br>`ITestCephFileContext#testRenameWithoutOverwriteRejectsExistingDestination`<br>SP-04 | UNIT + CLUSTER | [ADR-0004](adr/0004-atomic-no-replace-for-regular-files.md) |
| 抢占成功，源已被移除 | 视为成功 | `PROJECT-ADR-0004` | 保留已提交的目标并返回成功 | `MATCH` | `TestCephFileSystemMetaRename#testNoOverwriteFileRenameAcceptsConcurrentSourceRemoval` | UNIT | [ADR-0004](adr/0004-atomic-no-replace-for-regular-files.md) |
| 抢占成功，但移除源失败 | 回滚目标并抛出错误 | `PROJECT-ADR-0004` | 尝试移除目标后抛出原错误 | `MATCH` | `TestCephFileSystemMetaRename#testNoOverwriteFileRenameRollsBackFailedSourceUnlink` | UNIT | [ADR-0004](adr/0004-atomic-no-replace-for-regular-files.md) |
| 请求 overwrite，dst 已存在 | 顺序结果替换目标，且替换期间保持原子可见性 | `HADOOP-SPEC` | 顺序结果可完成覆盖；基类先 `delete(dst, false)` 再 `rename(src, dst)`，存在 transient ENOENT 与 crash window | `DIFFERENT` | CT-02（顺序功能兼容性）<br>FN-31（atomic visibility / crash window） | NONE | [LIM-009](KNOWN-LIMITATIONS.md#lim-009-replacing-rename-is-not-atomic) |
| 源是目录，no-replace | 原子地保证 no-replace | `HADOOP-SPEC` | 委托基类检查后 rename；目录不能用 hard link 抢占 | `DIFFERENT` | — | NONE | [LIM-005](KNOWN-LIMITATIONS.md#lim-005-no-replace-rename-boundaries) |
| 普通文件抢占与移除源之间发生崩溃 | rename 只留下一个名字 | `HADOOP-SPEC` | 两个名字都可能保留 | `DIFFERENT` | SP-04 | SPIKE | [LIM-005](KNOWN-LIMITATIONS.md#lim-005-no-replace-rename-boundaries) |

## create

`FileSystem#create` 与两个 `createNonRecursive` 重载共用同一条内部路径，因此在一个入口上得到验证的条件，
并不自动在其他入口上成立。

| 决策条件 | 期望行为 | 当前行为 | Guard / 用例 | Coverage |
|---|---|---|---|---|
| `overwrite=false`，dst 已存在且是文件 | `FileAlreadyExistsException` | 符合 | — | UNIT |
| `overwrite=true`，dst 已存在且是文件 | 原有内容被截断 | 符合 | — | UNIT + CLUSTER |
| `overwrite=true`，dst 已存在且是目录 | `FileAlreadyExistsException` | 符合 | — | UNIT + CONTRACT |
| `overwrite=false`，dst 已存在且是目录 | `FileAlreadyExistsException` | 符合（源码判断） | — | NONE |
| 父目录不存在，递归 create | 先建父目录，再建文件 | 符合 | — | UNIT |
| 父目录不存在，`createNonRecursive` | `FileNotFoundException`；不创建父目录 | 符合 | — | UNIT |
| 父路径是文件，递归 create | `ParentNotDirectoryException` | 符合 | — | UNIT + CONTRACT |
| 父路径是文件，`createNonRecursive` | `ParentNotDirectoryException` | 符合（源码判断） | — | NONE |
| 绑定层报告目标不是目录 | `ParentNotDirectoryException` | 符合 | UT-01 | UNIT |
| 更上层的某个祖先是文件 | `ParentNotDirectoryException` | 符合（源码判断） | UT-02 | NONE |
| flag 重载，仅 create | 独占创建 | 符合 | — | UNIT |
| flag 重载，overwrite | 原有内容被截断 | 符合 | — | UNIT |
| flag 重载，不支持的 flag 组合 | 被 flag 校验拒绝 | **被忽略；flag 从不校验** | — | NONE |
| 两个线程 create 同一路径，`overwrite=false` | 恰有一个成功；其余全部得到 `FileAlreadyExistsException` | 未知 | FN-17 | NONE |
| 前置检查之后路径被并发创建，`overwrite=true` | 成功并截断 | **以 `FileAlreadyExistsException` 失败** | — | NONE |
| 调用方给定的 block size | 向上取整到 64 KiB 的整数倍，以 layout 上限封顶，且永不溢出 | 符合 | UT-03 | UNIT |
| 配置了多个 data pool | 使用第一个 | 符合 | — | UNIT |
| 空文件与稀疏写 | 长度与内容符合 POSIX；block location 仍能解析 | 未知 | FN-09 | NONE |
| 文件名含空格、UTF-8 或 URI 保留字符 | 经 API 与 CLI 创建、列举、读取、删除的行为一致 | 未知 | FN-06 | NONE |
| 文件名处于以及超过 255 字节上限 | 处于上限时成功；超过时以可诊断的错误失败 | 未知 | FN-07 | NONE |
| 路径总长接近 4096 字节 | 有一个固定且已记录在案的结果 | 未知 | FN-08 | NONE |

## delete

根目录非递归删除返回 `false` 是本项目有意选择的语义：`filesystem.md` 允许对根目录做特殊处理，
契约基类期望抛出异常，`ITestCephContractRootDirectory` 以 `@Override` 改写为验证本实现的语义。

`fs.trash.interval` 是 `FsShell`/`Trash` 包装层的行为，包装层通过 `rename` 把路径移进回收站，`CephFileSystem#delete`
不读取该配置，因此回收站语义由 FN-23 与 ECO-CLI-06 自行拥有，不在本矩阵内。

| 决策条件 | 期望行为 | 当前行为 | Guard / 用例 | Coverage |
|---|---|---|---|---|
| 路径不存在 | `false` | 符合 | — | UNIT + CONTRACT |
| 文件 | 被删除，`true` | 符合 | — | UNIT |
| 空目录，非递归 | 被删除，`true` | 符合 | — | UNIT |
| 非空目录，非递归 | `PathIsNotEmptyDirectoryException` | 符合 | — | UNIT + CONTRACT |
| 非空目录，递归 | 整棵子树被删除 | 符合；遍历非原子 | — | UNIT |
| 根目录，非递归 | 返回 `false`，根目录与其子项均保留 | 符合 | — | UNIT + CLUSTER |
| 根目录，递归 | 子项被删除，根目录保留 | 符合 | — | UNIT |
| 递归遍历中途失败 | 有明确定义的返回值或异常 | **无定义；部分删除** | — | NONE |
| 递归 delete 期间有子项被创建 | 不抛非预期错误，且最终状态可解释 | 未知 | FN-16 | NONE |
| 100 层深的树，递归 | 成功 | 未知 | FN-05 | NONE |

## sync

`hflush` 与 `hsync` 的开销差异属于性能问题，由 PERF-10 判定，不在本矩阵内。

| 决策条件 | 期望行为 | 当前行为 | Guard / 用例 | Coverage |
|---|---|---|---|---|
| 跨缓冲区边界的缓冲写 | 所有字节按序送达 | 符合 | — | UNIT |
| `hflush` | 已写数据对新开的 reader 可见 | 符合 | — | UNIT + CONTRACT |
| `hsync` | 已写数据落到持久化 | 符合 | — | UNIT + CONTRACT |
| `hflush` 与 `hsync` 的相对强度 | `hflush` 至少保证"对新 reader 可见"，允许与 `hsync` 同等强度 | 符合（源码判断）；`hflush` 直接委托 `hsync`，没有廉价的仅可见性 flush | — | NONE |
| 普通 `flush()` | 排空缓冲区；不承诺持久化 | 符合（源码判断） | — | NONE |
| 缓冲区为空时调用 `hsync` | 仍然发起 sync | 符合（源码判断） | — | NONE |
| `close` | 排空、sync、释放；幂等 | 符合 | — | UNIT |
| 排空失败之后再 `close` | 释放描述符并抛出错误 | 符合 | UT-11 | UNIT |
| 排空失败且释放也失败 | 抛出第一个错误，第二个进 suppressed | 符合（源码判断） | UT-11 | NONE |
| close 之后再 write/read/seek/sync | 以 `IOException` 拒绝 | 符合 | UT-12 | UNIT |
| 底层写只接受了缓冲区的一部分 | 重试直到写完；返回 0 或超过请求量则为 `IOException` | 未知 | UT-10 | NONE |
| 流声明的 capability | 声明支持 flush 与 sync | 符合 | — | UNIT |
| 同一 mount 内 sync 之后的长度 | 立即反映出来 | 符合 | — | CONTRACT |
| `hsync` 之后杀掉客户端 | 已 sync 的数据全部存活，且文件仍可 append | 未知 | FN-13 | NONE |
| 任何 flush 之前杀掉客户端 | 允许丢数据，但长度与内容不得互相矛盾 | 未知 | FN-14 | NONE |
| 高频小量 `hflush` | 吞吐可接受 | 未知 | PERF-10 | NONE |

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

| 决策条件 | 期望行为 | 当前行为 | Guard / 用例 | Coverage |
|---|---|---|---|---|
| 已存在的文件 | 在末尾打开并追加 | 符合 | — | UNIT + CLUSTER |
| 不存在的文件 | 拒绝；不创建该文件 | 符合 | — | UNIT |
| 目录 | 以 `IOException` 拒绝 | 符合；但报出的类型是"路径不存在" | — | UNIT |
| 另一个 writer 正在追加时报告的起始位置 | 反映真实长度 | **是 open 时刻的陈旧快照** | — | NONE |
| shell 的 append 命令 | 经发行版 CLI 完成追加 | 未知 | ECO-CLI-04 | NONE |

## mkdirs

| 决策条件 | 期望行为 | 当前行为 | Guard / 用例 | Coverage |
|---|---|---|---|---|
| 新目录 | 创建时应用 umask | 符合 | — | UNIT + CONTRACT |
| 目标已经是目录 | `true`，幂等 | 符合 | — | UNIT |
| 目标已经是文件 | `FileAlreadyExistsException` | 符合 | — | UNIT |
| 父路径是文件 | `ParentNotDirectoryException` | 符合 | — | UNIT |
| 被并发创建为目录 | `true`，幂等 | 符合 | — | UNIT |
| 被并发创建为文件 | `FileAlreadyExistsException` | 符合 | — | UNIT |
| 相对路径 | 按工作目录解析 | 符合 | — | UNIT |
| 100 层深的树 | 创建成功且不栈溢出 | 未知 | FN-05 | NONE |
| 官方 `FileContext` create-mkdir 套件 | 通过 | 未知 | CT-03 | NONE |
| `FileSystem` 与 `FileContext` 的通用操作大套件 | 通过 | 未知 | CT-01, CT-02 | NONE |

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

- 先把决策条件及其 Basis 作为一行加进来，再去写断言；项目自定语义必须先有 ADR 或 limitation。
- 已迁移轴中，`Guard / 用例` 为 `—` 且 Coverage 为 `NONE` 的行，要么补精确断言或稳定用例定义，要么明确保留
  为无守护项。
- `DIFFERENT` 必须链接 [`KNOWN-LIMITATIONS.md`](KNOWN-LIMITATIONS.md) 或 ADR；不能再用粗体隐含分类。
- Coverage 只描述守护机制。是否运行以及运行结果只更新 dated report 与 `READINESS.md`。
- 要放宽或收紧一条期望，改这里，不要改用例文件。委托的用例根本没有期望文字可改——这正是委托的意义。
- 删除一行之前，先确认没有用例委托到它。删掉某个委托用例的最后一行，会让该用例完全没有期望结果。
- `scripts/check-docs.sh` 校验新 schema 的 Basis、Classification、Coverage 与偏离跟踪，并校验这里引用的每个
  用例 ID 在用例文件中恰好定义一次、每个委托用例至少被一行引用。
- 套件级、性能、spike 与生态用例保留各自的期望结果。它们的期望不在决策条件粒度上，因此即使有行引用它们，
  本矩阵也不拥有其期望。
