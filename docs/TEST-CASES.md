# Core test cases

This file defines stable core test IDs. Execution results belong in dated reports; current gate status belongs in `READINESS.md`. The former ECO-01–ECO-13 summary rows were removed because the detailed ecosystem cases are canonical in `TEST-CASES-ECO.md`.

Priorities: P0 blocks its gate; P1 is required; P2 requires an explicit support conclusion.

**Group: Unit semantics**

## UT-01: mapCephException 全矩阵：CephFileAlreadyExistsException/CephNotDirectoryException/裸 IOException/CephNotMountedException

- Purpose: [P1] `mapCephException` 全矩阵：`CephFileAlreadyExistsException`/`CephNotDirectoryException`/裸 IOException/`CephNotMountedException`
- Preconditions: Implementation or test basis: `CephFileSystem#mapCephException`.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 异常类型与 cause 链逐一符合规范
- Required environment: No cluster

## UT-02: lstatResolved 查询/变更两语境 × 祖先为文件/目录权限不足/纯 FNF

- Purpose: [P1] `lstatResolved` 查询/变更两语境 × 祖先为文件/目录权限不足/纯 FNF
- Preconditions: Implementation or test basis: `lstatResolved`.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 变更→`ParentNotDirectoryException`，查询→FNF，权限不足原样抛
- Required environment: No cluster

## UT-03: normalizeLayoutSize 边界：0、1、64K-1、64K、64K+1、Long.MAX_VALUE、负数

- Purpose: [P1] `normalizeLayoutSize` 边界：0、1、64K-1、64K、64K+1、`Long.MAX_VALUE`、负数
- Preconditions: Implementation or test basis: `normalizeLayoutSize`.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 恒为 64KB 整数倍且 ≤ `CEPH_MAX_LAYOUT_SIZE`，无溢出
- Required environment: No cluster

## UT-04: getStatus 的 frsize=0/bsize=0/超大 blocks（PB 级）溢出

- Purpose: [P1] `getStatus` 的 `frsize=0`/`bsize=0`/超大 blocks（PB 级）溢出
- Preconditions: Implementation or test basis: `getStatus`.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 无负值、无溢出，`used = capacity - remaining`
- Required environment: No cluster

## UT-05: setOwner：数字 uid+gid / 仅 uid / 非数字 / 空串 / null

- Purpose: [P1] `setOwner`：数字 uid+gid / 仅 uid / 非数字 / 空串 / null
- Preconditions: Implementation or test basis: `setOwner`.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 非数字仅 warn 且 mask 不置位；mask=0 时不调 `setattr`
- Required environment: No cluster

## UT-06: toFileStatus 权限位：setuid/setgid/sticky/0777

- Purpose: [P1] `toFileStatus` 权限位：setuid/setgid/sticky/0777
- Preconditions: Implementation or test basis: `toFileStatus`.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: `mode & 01777` 口径固化（setuid/setgid 丢弃）并附注释说明
- Required environment: No cluster

## UT-07: pathString：null、空、相对路径、含空格/UTF-8/%/#、ceph://host/x

- Purpose: [P1] `pathString`：null、空、相对路径、含空格/UTF-8/`%`/`#`、`ceph://host/x`
- Preconditions: Implementation or test basis: `CephTalker#pathString`.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 唯一转换入口行为固化
- Required environment: No cluster

## UT-08: getFileBlockLocations 降级：getFileExtent 抛异常 / extent 长度 ≤0 / OSD 列表空 / getOsdAddress 返回 null

- Purpose: [P1] `getFileBlockLocations` 降级：`getFileExtent` 抛异常 / extent 长度 ≤0 / OSD 列表空 / `getOsdAddress` 返回 null
- Preconditions: Implementation or test basis: `getFileBlockLocations`.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 降级为 localhost 并按 blockSize 步进，块覆盖无空洞无重叠，fd 必被 close
- Required environment: No cluster

## UT-09: getFileBlockLocations 入参：file=null、start<0、len<0、start≥len(file)、len=Long.MAX_VALUE

- Purpose: [P1] `getFileBlockLocations` 入参：`file=null`、start<0、len<0、start≥len(file)、`len=Long.MAX_VALUE`
- Preconditions: Implementation or test basis: 同上.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 分别 null / IAE / 空数组 / 末端钳制无溢出
- Required environment: No cluster

## UT-10: CephOutputStream.writeFully 部分写循环：底层每次只写 1 字节 / 返回 0 / 返回 > 请求数

- Purpose: [P1] `CephOutputStream.writeFully` 部分写循环：底层每次只写 1 字节 / 返回 0 / 返回 > 请求数
- Preconditions: Implementation or test basis: `writeFully`.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 循环直到写完；0 或超量→IOException
- Required environment: No cluster

## UT-11: CephOutputStream.close 失败路径：flush 抛异常 + close 也抛

- Purpose: [P1] `CephOutputStream.close` 失败路径：flush 抛异常 + close 也抛
- Preconditions: Implementation or test basis: `close`.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 首异常抛出，第二个进 suppressed，fd 不泄漏
- Required environment: No cluster

## UT-12: 流关闭后再 write/read/seek/getPos/hsync

- Purpose: [P1] 流关闭后再 write/read/seek/getPos/hsync
- Preconditions: Implementation or test basis: `ensureOpen`.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 一律 IOException("Stream is closed")，close 幂等
- Required environment: No cluster

## UT-13: CephInputStream 缓冲边界：seek 到缓冲头/尾/尾+1、跨缓冲读、read(pos,...) 越界

- Purpose: [P1] `CephInputStream` 缓冲边界：seek 到缓冲头/尾/尾+1、跨缓冲读、`read(pos,...)` 越界
- Preconditions: Implementation or test basis: `fillBuffer`/`seek`.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 数据正确、缓冲失效逻辑正确、越界 EOF 语义一致
- Required environment: No cluster

## UT-14: Statistics 计数：写失败后计数是否虚增；write(int) 单字节路径

- Purpose: [P2] `Statistics` 计数：写失败后计数是否虚增；`write(int)` 单字节路径
- Preconditions: Implementation or test basis: `incrementBytesWritten`.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 计数口径书面固化（当前先计数后落盘）
- Required environment: No cluster

## UT-15: initialize 配置解析：ceph.conf.options 非法项（无 =、空段、多 =）

- Purpose: [P1] `initialize` 配置解析：`ceph.conf.options` 非法项（无 `=`、空段、多 `=`）
- Preconditions: Implementation or test basis: `CephTalker#initialize`.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 非法抛 IAE 且消息含原文；空段跳过
- Required environment: No cluster

## UT-16: URI authority 覆盖 mon：有 host 有 port / 有 host 无 port / 无 authority / 含逗号的多 mon 写法

- Purpose: [P1] URI authority 覆盖 mon：有 host 有 port / 有 host 无 port / 无 authority / **含逗号的多 mon 写法**
- Preconditions: Implementation or test basis: 同上.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 前三种符合设计；多 mon 逗号写法 `getHost()` 为 null → 静默回退 ceph.conf，须固化并写入文档
- Required environment: No cluster

## UT-17: firstDataPool：空串、逗号开头、全空白

- Purpose: [P2] `firstDataPool`：空串、逗号开头、全空白
- Preconditions: Implementation or test basis: `firstDataPool`.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 返回 null 或首个非空
- Required environment: No cluster

## UT-18: streamBufferSize 非法值（0、负、超大）

- Purpose: [P2] `streamBufferSize` 非法值（0、负、超大）
- Preconditions: Implementation or test basis: `streamBufferSize`.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 回退默认值
- Required environment: No cluster

## UT-19: close() 幂等 + close 后所有 FS 操作

- Purpose: [P1] `close()` 幂等 + close 后所有 FS 操作
- Preconditions: Implementation or test basis: `CephFileSystem#close`.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 二次 close 无副作用；后续操作抛 `CephNotMountedException`
- Required environment: No cluster

## UT-20: initialize 二次调用

- Purpose: [P2] `initialize` 二次调用
- Preconditions: Implementation or test basis: `CephTalker#initialize`.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: `IllegalStateException("already initialized")`
- Required environment: No cluster

**Group: Hadoop contracts**

## CT-01: 接入 FSMainOperationsBaseTest → ITestCephFSMainOperations

- Purpose: [P0] 接入 `FSMainOperationsBaseTest` → `ITestCephFSMainOperations`
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 全绿；排除项有书面理由
- Required environment: E1 and E2

## CT-02: 接入 FileContextMainOperationsBaseTest → ITestCephFileContextMainOperations

- Purpose: [P0] 接入 `FileContextMainOperationsBaseTest` → `ITestCephFileContextMainOperations`
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 同上（补强 `CephFs`/YARN 路径）
- Required environment: E1 and E2

## CT-03: FileContextCreateMkdirBaseTest

- Purpose: [P1] `FileContextCreateMkdirBaseTest`
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 全绿
- Required environment: E1 and E2

## CT-04: FileContextPermissionBase

- Purpose: [P1] `FileContextPermissionBase`
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 全绿或差异书面化（uid/gid 数字化影响）
- Required environment: E1 and E2

## CT-05: FileContextURIBase

- Purpose: [P1] `FileContextURIBase`
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 全绿
- Required environment: E1 and E2

## CT-06: 现有 9 套契约在 E2 三副本非调试构建上重跑

- Purpose: [P0] 现有 9 套契约在 **E2 三副本非调试构建**上重跑
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 与 E1 结果一致；`lockdep` workaround 不需要
- Required environment: E1 and E2

## CT-07: 契约套件在 ceph.root.dir=/subdir 子目录挂载下重跑

- Purpose: [P1] 契约套件在 `ceph.root.dir=/subdir` 子目录挂载下重跑
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 结果一致（根语义相对子目录成立）
- Required environment: E1 and E2

## CT-08: 契约套件在非 admin、最小 caps 用户下重跑

- Purpose: [P1] 契约套件在**非 admin、最小 caps** 用户下重跑
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 除 root 目录类外全绿
- Required environment: E1 and E2

## CT-09: 契约套件连续 20 轮稳定性

- Purpose: [P2] 契约套件连续 20 轮稳定性
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: flaky < 1%，不稳定用例建单
- Required environment: E1 and E2

**Group: Functional depth**

## FN-01: 单文件 1GB / 10GB 写入读回

- Purpose: [P0] 单文件 1GB / 10GB 写入读回
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: md5 一致；耗时记录
- Required environment: E2

## FN-02: 单文件 > 2^31 字节的 seek/pread（偏移超 int）

- Purpose: [P0] 单文件 > 2^31 字节的 seek/pread（偏移超 int）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 无溢出，随机 100 点 pread 全部正确
- Required environment: E2

## FN-03: 单次 write(byte[]) 传入 > 缓冲区的大数组（16MB/64MB）

- Purpose: [P1] 单次 `write(byte[])` 传入 > 缓冲区的大数组（16MB/64MB）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 走直写分支正确，无越界
- Required environment: E2

## FN-04: 目录 1 万 / 10 万条目 listStatus/-ls

- Purpose: [P1] 目录 1 万 / 10 万条目 `listStatus`/`-ls`
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 结果完整、无 OOM；耗时进性能基线（N+1 RPC 观察点）
- Required environment: E2

## FN-05: 100 层深目录的 mkdirs/递归 delete/rename

- Purpose: [P1] 100 层深目录的 mkdirs/递归 delete/rename
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 正确；无栈溢出（递归实现 `deleteSubtree`）
- Required environment: E2

## FN-06: 特殊文件名：空格、UTF-8 中文、%20、#、+、:、单引号

- Purpose: [P1] 特殊文件名：空格、UTF-8 中文、`%20`、`#`、`+`、`:`、单引号
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 创建/列举/读写/删除全链路一致（含 FsShell 层）
- Required environment: E2

## FN-07: 文件名 255 字节上限与超限

- Purpose: [P1] 文件名 255 字节上限与超限
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 上限成功；超限失败且异常可诊断
- Required environment: E2

## FN-08: 路径总长接近 4096

- Purpose: [P1] 路径总长接近 4096
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 行为固化
- Required environment: E2

## FN-09: 空文件、稀疏写（seek 后写，中间空洞）

- Purpose: [P2] 空文件、稀疏写（seek 后写，中间空洞）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 长度与内容符合 POSIX，`getFileBlockLocations` 不崩
- Required environment: E2

## FN-10: 5 万文件并发打开（不关闭）后统一关闭

- Purpose: [P1] 5 万文件并发打开（不关闭）后统一关闭
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 无 fd 泄漏（`openFileDescriptorCount` 归零），无 MDS 告警残留
- Required environment: E2

## FN-11: 写方 append + 读方已打开流：读方能否看到新数据

- Purpose: [P0] **写方 append + 读方已打开流**：读方能否看到新数据
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 固化结论；若 open 时刻长度快照仍成立，更新 LIM-002
- Required environment: E2

## FN-12: 写方 hflush/hsync 后，新开的 reader 能否读到

- Purpose: [P0] 写方 `hflush`/`hsync` 后，**新开**的 reader 能否读到
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 必须读到全部已 sync 数据
- Required environment: E2

## FN-13: hsync 后 kill -9 客户端

- Purpose: [P0] `hsync` 后 `kill -9` 客户端
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 已 sync 数据 100% 存活，文件可正常再 append/覆盖
- Required environment: E2

## FN-14: 未 flush 即 kill -9

- Purpose: [P1] 未 flush 即 `kill -9`
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 允许丢数据，但不得出现长度与内容不一致的损坏文件
- Required environment: E2

## FN-15: 并发 rename 竞态：两线程同时 rename 同一 src

- Purpose: [P1] 并发 rename 竞态：两线程同时 rename 同一 src
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 恰一个成功，另一个返回 false 或 FNF，无中间态
- Required environment: E2

## FN-16: 递归 delete 期间并发创建子文件

- Purpose: [P1] 递归 delete 期间并发创建子文件
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 不抛非预期异常；结果状态可解释（代码已有竞态分支）
- Required environment: E2

## FN-17: 并发 create 同一路径（overwrite=false）

- Purpose: [P1] 并发 create 同一路径（overwrite=false）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 恰一个成功，其余 `FileAlreadyExistsException`
- Required environment: E2

## FN-18: rename 目录到自身子树 / 到已存在目录 / 到已存在文件

- Purpose: [P1] rename 目录到自身子树 / 到已存在目录 / 到已存在文件
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 分别 false / 移入其下 / false（与 HDFS 一致）
- Required environment: E2

## FN-19: 32 线程共享同一 CephFileSystem 混合读写 30 min

- Purpose: [P1] 32 线程共享同一 `CephFileSystem` 混合读写 30 min
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 无死锁、无数据错乱、fd 归零（现有 4 线程冒烟的加强版）
- Required environment: E2

## FN-20: FileSystem.CACHE 行为：多次 FileSystem.get 复用同一 mount；fs.ceph.impl.disable.cache=true 时每次新 mount

- Purpose: [P2] `FileSystem.CACHE` 行为：多次 `FileSystem.get` 复用同一 mount；`fs.ceph.impl.disable.cache=true` 时每次新 mount
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 会话数变化符合预期（`ceph tell mds session ls`）
- Required environment: E2

## FN-21: 多 UGI（doAs 三个用户）访问

- Purpose: [P1] 多 UGI（`doAs` 三个用户）访问
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 每 UGI 一个 mount；CephFS 侧身份均为同一 cephx id（SEC-1 的功能面证据）
- Required environment: E2

## FN-22: ceph.root.dir=/tenantA 子目录挂载全功能

- Purpose: [P1] `ceph.root.dir=/tenantA` 子目录挂载全功能
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 根语义、`getStatus`、BlockLocation 均正确；无法越界访问上级
- Required environment: E2

## FN-23: Trash：fs.trash.interval>0 下 -rm

- Purpose: [P1] Trash：`fs.trash.interval>0` 下 `-rm`
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 移入 `/user/<user>/.Trash`；家目录不存在时行为可接受
- Required environment: E2

## FN-24: getContentSummary 深/宽目录树

- Purpose: [P1] `getContentSummary` 深/宽目录树
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 结果正确；耗时进性能基线
- Required environment: E2

## FN-25: getFileBlockLocations 在多 OSD 多主机下

- Purpose: [P1] `getFileBlockLocations` 在**多 OSD 多主机**下
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: hosts 为真实 OSD 主机 IP（与 `ceph osd dump` 可比），块切分与 object 边界一致
- Required environment: E2

## FN-26: Statistics 计数 vs 实际字节数（读/写各 1GB）

- Purpose: [P1] `Statistics` 计数 vs 实际字节数（读/写各 1GB）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 误差为 0 或书面解释
- Required environment: E2

## FN-27: 13 个配置键逐个改值 → 生效性验证 + 缺省回退

- Purpose: [P1] 13 个配置键逐个改值 → 生效性验证 + 缺省回退
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 每键至少 1 条断言（含 `ceph.localize.reads`、`ceph.replication`、`ceph.data.pools`）
- Required environment: E2

## FN-28: ceph.data.pools 指定非默认 data pool

- Purpose: [P2] `ceph.data.pools` 指定非默认 data pool
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 文件确实落在指定 pool（`ceph osd map` 校验）
- Required environment: E2

## FN-29: -df / getStatus 与 ceph df 交叉校验

- Purpose: [P1] `-df` / `getStatus` 与 `ceph df` 交叉校验
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 容量/可用量偏差可解释
- Required environment: E2

## FN-30: setTimes / mtime 精度（纳秒→毫秒截断）

- Purpose: [P2] `setTimes` / mtime 精度（纳秒→毫秒截断）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 与 `stat` 一致，无回退/漂移
- Required environment: E2

**Group: Reliability and failure injection**

## REL-F01: active MDS 崩溃（有 standby）

- Purpose: [P0] active MDS 崩溃（有 standby）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 进行中 IO 阻塞后自动恢复；阻塞 ≤ 阈值；md5 一致
- Required environment: E2

## REL-F02: MDS 全灭

- Purpose: [P0] MDS 全灭
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 固化挂起或报错行为，并给出恢复后是否需重建 FileSystem 的操作口径
- Required environment: E2

## REL-F03: 单 OSD down（副本充足）

- Purpose: [P0] 单 OSD down（副本充足）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: IO 无中断，无错误
- Required environment: E2

## REL-F04: PG inactive（多 OSD down）

- Purpose: [P0] PG inactive（多 OSD down）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 写阻塞可控；恢复后 md5 一致，无损坏
- Required environment: E2

## REL-F05: mon 部分/全部不可达

- Purpose: [P1] mon 部分/全部不可达
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: mount 期与运行期超时行为、报错文本可诊断
- Required environment: E2

## REL-F06: 客户端被 evict/blocklist

- Purpose: [P0] 客户端被 evict/blocklist
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 错误可诊断；已打开流的行为固化；恢复路径明确
- Required environment: E2

## REL-F07: 网络 100ms 延迟 + 1% 丢包

- Purpose: [P1] 网络 100ms 延迟 + 1% 丢包
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 吞吐退化曲线记录；无错误放大，无损坏
- Required environment: E2

## REL-F08: 集群整体重启

- Purpose: [P1] 集群整体重启
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 客户端恢复行为固化；运行中的 MR 作业结局记录
- Required environment: E2

## REL-F09: 池写满（ENOSPC）

- Purpose: [P1] 池写满（ENOSPC）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 异常可诊断；不产生半截损坏文件；记录安全恢复与清理口径
- Required environment: E2

## REL-F10: 目录配额触发

- Purpose: [P1] 目录配额触发
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 同上
- Required environment: E2

## REL-F11: caps 矩阵：r / rw(无 p) / 路径受限 / root_squash

- Purpose: [P0] caps 矩阵：`r` / `rw`(无 p) / 路径受限 / root_squash
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 每格记录可用与不可用操作及错误文本
- Required environment: E2

## REL-F12: 文件系统置只读

- Purpose: [P1] 文件系统置只读
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 写失败语义可诊断
- Required environment: E2

## REL-F13: 写入中 kill -9 客户端

- Purpose: [P0] 写入中 kill -9 客户端
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 同 FN-13/14
- Required environment: E2

## REL-F14: 双客户端并发写同一文件

- Purpose: [P1] 双客户端并发写同一文件
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 语义结论写入"已知限制"
- Required environment: E2

## REL-F15: MDS cache 压力（5 万打开文件）

- Purpose: [P1] MDS cache 压力（5 万打开文件）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 客户端内存与 MDS 健康可控，无 OOM
- Required environment: E2

**Group: Performance and capacity**

## PERF-01: 单流顺序写吞吐（1GB/10GB）

- Purpose: [P0] 单流顺序写吞吐（1GB/10GB）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: ≥ 内核 mount 70%
- Required environment: E2

## PERF-02: 单流顺序读吞吐

- Purpose: [P0] 单流顺序读吞吐
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: ≥ 内核 mount 70%
- Required environment: E2

## PERF-03: 8/16/64 并发聚合读写

- Purpose: [P0] 8/16/64 并发聚合读写
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 8 并发 ≥ 85%；16/64 记录扩展性曲线
- Required environment: E2

## PERF-04: 元数据 OPS：create/stat/list/rename/delete

- Purpose: [P0] 元数据 OPS：create/stat/list/rename/delete
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: ≥ 内核 mount 60%；p99 < 100 ms
- Required environment: E2

## PERF-05: 随机 pread（4KB/64KB/1MB，QD 1/16）

- Purpose: [P1] 随机 pread（4KB/64KB/1MB，QD 1/16）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 延迟分位记录，与内核 mount 对比
- Required environment: E2

## PERF-06: ceph.client.buffer.size ∈ {1,4,16} MB 敏感度

- Purpose: [P1] `ceph.client.buffer.size` ∈ {1,4,16} MB 敏感度
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 给出推荐默认值（含结论依据）
- Required environment: E2

## PERF-07: ceph.object.size ∈ {4,64,256} MB 敏感度

- Purpose: [P1] `ceph.object.size` ∈ {4,64,256} MB 敏感度
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 同上
- Required environment: E2

## PERF-08: ceph.localize.reads on/off

- Purpose: [P1] `ceph.localize.reads` on/off
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 量化本地读收益
- Required environment: E2

## PERF-09: 10 万条目目录 listStatus 耗时

- Purpose: [P1] 10 万条目目录 `listStatus` 耗时
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 与内核 `ls -l` 对比；记录是否需要优化或限制超大目录扫描
- Required environment: E2

## PERF-10: 小文件高频 hflush（4KB × 1 万次）

- Purpose: [P1] 小文件高频 `hflush`（4KB × 1 万次）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 延迟分布；判断 HBase 类工作负载可行性（ECO-7）
- Required environment: E2

## PERF-11: getFileBlockLocations 大文件 split 计算耗时

- Purpose: [P1] `getFileBlockLocations` 大文件 split 计算耗时
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 每次开关 fd 的开销量化
- Required environment: E2

## PERF-12: TestDFSIO write/read（-nrFiles 16 -fileSize 1GB）

- Purpose: [P1] TestDFSIO write/read（`-nrFiles 16 -fileSize 1GB`）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 记录基线；与 HDFS 同规模对照
- Required environment: E2

## PERF-13: TeraSort 100GB

- Purpose: [P1] TeraSort 100GB
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 完成时间与失败率；本地性命中率
- Required environment: E2

## PERF-14: getContentSummary 10 万文件树

- Purpose: [P2] `getContentSummary` 10 万文件树
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 耗时基线
- Required environment: E2

## PERF-15: 与上一版本基线的回归比较

- Purpose: [P0] 与上一版本基线的回归比较
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 无 > 10% 退化
- Required environment: E2

**Group: Soak and resources**

## SOAK-01: 72h 混合负载（读写 7:3 + 元数据 + 每 6h 故障注入）

- Purpose: [P0] 72h 混合负载（读写 7:3 + 元数据 + 每 6h 故障注入）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 0 失败；每小时 md5 抽样一致
- Required environment: E2

## SOAK-02: JVM heap / native RSS 趋势

- Purpose: [P0] JVM heap / **native RSS** 趋势
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 增长 < 5%/24h（NMT + pmap 佐证）
- Required environment: E2

## SOAK-03: fd 数量趋势

- Purpose: [P0] fd 数量趋势
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 稳态；负载结束后归零
- Required environment: E2

## SOAK-04: 线程数与 CephFS 会话数趋势

- Purpose: [P1] 线程数与 CephFS 会话数趋势
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 稳态不增（重点：`CephFs` 无 close 钩子的 mount 累积）
- Required environment: E2

## SOAK-05: 长跑 NodeManager 模拟（FileContext 路径 24h）

- Purpose: [P1] 长跑 NodeManager 模拟（FileContext 路径 24h）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: mount/会话不累积或给出运维口径
- Required environment: E2

**Group: Compatibility**

## COMPAT-01: Hadoop 3.3.6 + JDK 11 + Ceph 16.2.14（基线）

- Purpose: [P0] Hadoop 3.3.6 + JDK 11 + Ceph 16.2.14（基线）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 全绿
- Required environment: Isolated compatibility matrix environment

## COMPAT-02: Hadoop 3.3.9

- Purpose: [P1] Hadoop 3.3.9
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: L2 契约全绿
- Required environment: Isolated compatibility matrix environment

## COMPAT-03: Hadoop 3.4.x

- Purpose: [P2] Hadoop 3.4.x
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 结论化（差异/不支持写入文档）
- Required environment: Isolated compatibility matrix environment

## COMPAT-04: JDK 8 运行（字节码目标已是 8）

- Purpose: [P1] JDK 8 运行（字节码目标已是 8）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: L2 契约全绿
- Required environment: Isolated compatibility matrix environment

## COMPAT-05: JDK 17 运行

- Purpose: [P2] JDK 17 运行
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 结论化（Hadoop 3.3 已知限制）
- Required environment: Isolated compatibility matrix environment

## COMPAT-06: Ceph 服务端 16.2 最新小版本

- Purpose: [P0] Ceph 服务端 16.2 最新小版本
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 全绿
- Required environment: Isolated compatibility matrix environment

## COMPAT-07: Ceph 17.2 Quincy 服务端 + 16.2 客户端

- Purpose: [P1] Ceph 17.2 Quincy 服务端 + 16.2 客户端
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 结论化
- Required environment: Isolated compatibility matrix environment

## COMPAT-08: Ceph 18.2 Reef 服务端

- Purpose: [P2] Ceph 18.2 Reef 服务端
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 结论化
- Required environment: Isolated compatibility matrix environment

## COMPAT-09: libcephfs.jar 与 libcephfs_jni.so 版本错配

- Purpose: [P0] libcephfs.jar 与 libcephfs_jni.so 版本错配
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 失败必须早且可诊断（不得运行到中途才崩）
- Required environment: Isolated compatibility matrix environment

## COMPAT-10: Ubuntu 22.04 / Rocky 8（glibc 差异）

- Purpose: [P1] Ubuntu 22.04 / Rocky 8（glibc 差异）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 加载与全链路正常
- Required environment: Isolated compatibility matrix environment

## COMPAT-11: 发行版自带 libcephfs2 的 jni 库替换本项目 so

- Purpose: [P1] 发行版自带 libcephfs2 的 jni 库替换本项目 so
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 可用（DEPLOY.md §1 声称的路径必须成立）
- Required environment: Isolated compatibility matrix environment

## COMPAT-12: 32 位/异构架构（arm64）

- Purpose: [P2] 32 位/异构架构（arm64）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 结论化（不支持则明确声明）
- Required environment: Isolated compatibility matrix environment

**Group: Security**

## SEC-01: Hadoop 用户身份是否下传 MDS（多 UGI 对照）

- Purpose: [P0] Hadoop 用户身份是否下传 MDS（多 UGI 对照）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 结论化：不下传 → DEPLOY.md 新增"安全模型与适用边界"章节
- Required environment: E2

## SEC-02: 最小 caps 矩阵（含 mds rwp 必要性复核）

- Purpose: [P0] 最小 caps 矩阵（含 `mds rwp` 必要性复核）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 每格可用操作表
- Required environment: E2

## SEC-03: 路径受限 caps（ceph fs authorize a client.x /tenantA rwp）+ ceph.root.dir

- Purpose: [P1] 路径受限 caps（`ceph fs authorize a client.x /tenantA rwp`）+ `ceph.root.dir`
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 越界访问必失败
- Required environment: E2

## SEC-04: root_squash caps

- Purpose: [P1] root_squash caps
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 行为与错误文本固化
- Required environment: E2

## SEC-05: 日志/异常栈不得含 cephx key

- Purpose: [P0] 日志/异常栈**不得含 cephx key**
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 自动 grep 断言全套测试日志，0 命中
- Required environment: E2

## SEC-06: keyring 文件权限异常（644/不可读/不存在）

- Purpose: [P1] keyring 文件权限异常（644/不可读/不存在）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 报错可诊断，不打印密钥内容
- Required environment: E2

## SEC-07: hadoop fs -chown user:group（非数字）

- Purpose: [P1] `hadoop fs -chown user:group`（非数字）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 静默 warn 行为固化并写入"已知限制"（SEC-6/A 相关）
- Required environment: E2

## SEC-08: -chmod 全权限位（含 setuid/setgid/sticky）

- Purpose: [P1] `-chmod` 全权限位（含 setuid/setgid/sticky）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: `mode & 01777` 差异书面化
- Required environment: E2

## SEC-09: 依赖 CVE 扫描

- Purpose: [P1] 依赖 CVE 扫描
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 无 CVSS ≥ 7.0 未豁免
- Required environment: E2

## SEC-10: ceph.conf.options 传入敏感项（如 key=）

- Purpose: [P2] `ceph.conf.options` 传入敏感项（如 `key=`）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 不落日志
- Required environment: E2

**Group: Deployment and operations**

## OPS-01: 用真实 Hadoop 3.3.6 发行版按 DEPLOY.md 全流程安装验证

- Purpose: [P0] 用**真实 Hadoop 3.3.6 发行版**按 DEPLOY.md 全流程安装验证
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: `hadoop fs -ls ceph:///` 起全链路通过（补上 T07 遗留 4）
- Required environment: E2 and E3

## OPS-02: native 库三种配置方式（java.library.path / LD_LIBRARY_PATH / 发行版 native 目录）

- Purpose: [P0] native 库三种配置方式（`java.library.path` / `LD_LIBRARY_PATH` / 发行版 native 目录）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 三种均实测有效
- Required environment: E2 and E3

## OPS-03: 1.0.0 → 1.1.0 升级与回滚

- Purpose: [P1] 1.0.0 → 1.1.0 升级与回滚
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 双向可行；无残留冲突
- Required environment: E2 and E3

## OPS-04: 集群内滚动更新连接器 jar（部分节点新版）

- Purpose: [P1] 集群内滚动更新连接器 jar（部分节点新版）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 混版可用或明确禁止
- Required environment: E2 and E3

## OPS-05: DEPLOY.md 排查表 6 类症状在 E2 上复现

- Purpose: [P1] DEPLOY.md 排查表 6 类症状在 E2 上复现
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 症状文本与生产构建一致（调试构建专属项须标注）
- Required environment: E2 and E3

## OPS-06: 错误可诊断性抽查：10 个典型误配

- Purpose: [P1] 错误可诊断性抽查：10 个典型误配
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 均能从日志定位根因
- Required environment: E2 and E3

## OPS-07: 监控采集点清单（fd/会话/吞吐/错误率）

- Purpose: [P1] 监控采集点清单（fd/会话/吞吐/错误率）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 给出 fd、会话、吞吐和错误率的可采集方案
- Required environment: E2 and E3

## OPS-08: 制品可重现性 + sha256 + so 纯净度

- Purpose: [P1] 制品可重现性 + sha256 + so 纯净度
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: L0 阈值
- Required environment: E2 and E3

## OPS-09: 容量规划建议（客户端内存 vs 并发流数）

- Purpose: [P2] 容量规划建议（客户端内存 vs 并发流数）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 给出经验公式与实测依据
- Required environment: E2 and E3

## OPS-10: 干净 shell 终验脚本在 E2/E3 重跑

- Purpose: [P1] 干净 shell 终验脚本在 E2/E3 重跑
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 退出码 0
- Required environment: E2 and E3

**Group: Unsupported and negative paths**

## NEG-01: setReplication

- Purpose: [P1] `setReplication`
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 返回 false（不抛异常），文档已声明
- Required environment: E2

## NEG-02: concat / truncate

- Purpose: [P1] `concat` / `truncate`
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: `UnsupportedOperationException` 或明确 IOException，消息可读
- Required environment: E2

## NEG-03: XAttr / ACL API 调用

- Purpose: [P1] XAttr / ACL API 调用
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 明确不支持异常，非 NPE/静默成功
- Required environment: E2

## NEG-04: 符号链接创建/解析

- Purpose: [P1] 符号链接创建/解析
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 明确不支持；`supportsSymlinks()` 声明一致
- Required environment: E2

## NEG-05: 快照 API

- Purpose: [P1] 快照 API
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 明确不支持
- Required environment: E2

## NEG-06: Kerberos 环境下使用（hadoop.security.authentication=kerberos）

- Purpose: [P1] Kerberos 环境下使用（`hadoop.security.authentication=kerberos`）
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 行为固化：不支持委托 Token，错误可诊断
- Required environment: E2

## NEG-07: fs.AbstractFileSystem.ceph.impl 未配置

- Purpose: [P1] `fs.AbstractFileSystem.ceph.impl` 未配置
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: `UnsupportedFileSystemException`（已有 ITest，纳入回归）
- Required environment: E2

## NEG-08: CephFS multi-fs 环境指定非默认 fs

- Purpose: [P2] CephFS multi-fs 环境指定非默认 fs
- Preconditions: The required fixture and test data are ready.
- Steps: Execute the case; record inputs, outputs, errors, resource state, and cleanup.
- Expected result: 明确不支持并给出规避方式（`ceph.conf.options`）
- Required environment: E2
