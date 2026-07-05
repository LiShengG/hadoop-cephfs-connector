# T03 — CephFileSystem 骨架与元数据操作

## 目标

实现 `CephFileSystem`（extends `org.apache.hadoop.fs.FileSystem`）的生命周期与
全部**元数据**操作；数据流方法留桩（抛 `UnsupportedOperationException`，T04 实现）。

## 前置依赖

T02 DONE（抽象层已冻结）。
必读：架构文档 §3.2、§4（语义差异表——本任务核心）、§5；
Hadoop FS 语义规范：`hadoop-3.3.6-src/hadoop-common-project/hadoop-common/src/site/markdown/filesystem/filesystem.md`。

## 工作内容

1. **生命周期**：`initialize(uri, conf)`（构造 `CephTalker`、解析 §5 配置、
   设置 workingDir=`/user/<ugi.shortUserName>`）、`getUri`、`getScheme`、
   `close()`（幂等，调用 `proto.shutdown()`）、`setWorkingDirectory` /
   `getWorkingDirectory`（纯 Java 侧，见 §4-7）、`makeAbsolute` 路径规范化。
   构造器支持注入 `CephFsProto`（包级可见），供 mock 测试。
2. **查询类**：
   - `getFileStatus`：`lstat` → `FileStatus`（isDir、length、blockSize=文件实际
     layout 或默认、mtime/atime 毫秒、`FsPermission(mode)`、owner/group 按 §4-6 映射）；
   - `listStatus`：不存在→`FileNotFoundException`；文件→单元素数组；目录→逐项 status；
   - `exists`/`isFile`/`isDirectory` 依赖基类默认实现即可。
3. **修改类**（严格按 §4 语义差异表）：
   - `mkdirs(path, perm)`：父路径为文件→`ParentNotDirectoryException`；已存在目录→true；
   - `delete(path, recursive)`：文件→unlink；空目录→rmdir；非空目录且 !recursive→
     `PathIsNotEmptyDirectoryException`；recursive→Java 侧后序遍历删除；
     根目录删除保护（delete("/") 且 !recursive → false）；
   - `rename(src, dst)`：按 §4-1 策略（dst 存在文件→false；dst 存在目录→移入其下；
     src 不存在→false；src==dst→存在即 true）；
   - `setTimes`、`setPermission`、`setOwner`（chmod/setattr；owner 仅在传入数字
     uid/gid 可解析时生效，否则 no-op + warn 日志——CephFS 无用户名映射）；
   - `setReplication`→false，`getDefaultReplication`→固定值（读配置，缺省 3）。
4. **异常映射工具**：私有 helper 将 proto 抛出的 IOException/errno 语义统一转为
   Hadoop 规范异常类型，集中一处。
5. **注册**：`src/main/resources/META-INF/services/org.apache.hadoop.fs.FileSystem`
   写入实现类全名（ServiceLoader 自动发现）。
6. **测试**：
   - mock 单测（重点交付物）：rename 全分支矩阵、delete 全分支、mkdirs 父为文件、
     getFileStatus 不存在、workingDir 相对路径解析——每个 §4 条目至少一个用例；
   - `ITestCephFileSystemMeta`（门控）：真实集群上 mkdir/ls/status/rename/delete 走通。

## 交付物

- `CephFileSystem.java`（元数据完整、数据流留桩）、ServiceLoader 注册文件
- `TestCephFileSystemMeta*.java`（mock 单测）、`ITestCephFileSystemMeta.java`

## 验收标准

1. `mvn clean test` 绿色；mock 单测覆盖 §4 表 1/2/3/7 各分支；
2. 集群上集成测试通过；
3. `FileSystem.get(URI.create("ceph:///"), conf)` 能经 ServiceLoader 拿到本实现
   （集成测试中体现）；
4. 数据流方法（open/create/append）为显式留桩，不含半成品实现。

## 边界与禁止事项

- 不实现流类与 create/open/append 实体逻辑（T04）；
- 不实现 `getFileBlockLocations` 与 `CephFs`（T05，可留桩调用基类默认实现）；
- 不改 `CephFsProto` 签名。
