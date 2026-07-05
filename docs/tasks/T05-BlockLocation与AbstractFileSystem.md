# T05 — BlockLocation 数据局部性与 AbstractFileSystem（CephFs）

## 目标

补齐数据局部性（`getFileBlockLocations`）与 `FileContext` 路径
（`CephFs extends DelegateToFileSystem`），使 YARN 等基于
`AbstractFileSystem` 的组件可用。

## 前置依赖

T04 DONE。必读：架构文档 §2（CephFs 定位）、§3.2（BlockLocation 行）、§5；
参考实现：`hadoop-3.3.6-src` 中 `org.apache.hadoop.fs.local.RawLocalFs` /
`org.apache.hadoop.fs.ftp.FtpFs`（DelegateToFileSystem 的两个内置范例）。

## 工作内容

1. **`getFileBlockLocations(FileStatus, start, len)`**：
   - 打开只读 fd，对 `[start, start+len)` 按文件 layout 步进调用
     `proto.getFileExtent(fd, offset)`，得到每段 extent 的 OSD 列表；
   - `proto.getOsdAddress(osd)` 解析为 host（`BlockLocation` 的 hosts/names）；
   - 单个 extent 解析失败降级为 `localhost` 并 warn（不让作业提交失败）；
   - 完成后关闭 fd；文件为空或目录时按基类规范返回。
2. **`CephFs extends DelegateToFileSystem`**：
   - 按 `RawLocalFs` 模式实现（构造器签名
     `CephFs(URI, Configuration)`，委托 new `CephFileSystem`；
     `getUriDefaultPort` 返回 6789）；
   - 验证 `FileContext.getFileContext(URI.create("ceph:///"), conf)` 全链路。
3. **收尾杂项**（架构文档 §3.2 中尚未落地的小方法）：
   - `getDefaultBlockSize(Path)`、`getServerDefaults`、`getStatus`
     （statfs → `FsStatus`：capacity/used/remaining）、
     `getContentSummary` 用基类默认实现确认可用。
4. **测试**：
   - mock 单测：extent→BlockLocation 的偏移/长度切分正确性（跨对象边界、
     start 非对齐、len 超文件尾）、OSD 解析失败降级路径；
   - `ITestCephBlockLocation`（门控）：真实集群写 3×objectSize 文件，
     BlockLocation 数量与 offset/length 正确、host 非空；
   - `ITestCephFileContext`（门控）：FileContext mkdir/create/open/rename/delete
     一轮走通。

## 交付物

- `CephFileSystem.getFileBlockLocations` 实现 + 收尾小方法
- `CephFs.java`
- mock 单测 + `ITestCephBlockLocation.java` + `ITestCephFileContext.java`

## 验收标准

1. `mvn clean test` 绿色；
2. 两个集成测试通过；BlockLocation 的 host 与 `ceph osd dump` 中 OSD 地址一致
   （vstart 单机环境即本机 IP/hostname）；
3. `FileContext` 路径可用（fs.AbstractFileSystem.ceph.impl 配置生效）；
4. `getStatus()` 容量数字与 `ceph df` 同量级。

## 边界与禁止事项

- 不为 crush 拓扑做机架感知（topologyPaths 留空，后续迭代）；
- 不改流实现与元数据行为；
- 不开始契约测试套件搭建（T06）。
