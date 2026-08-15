/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.fs.ceph;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ceph.fs.CephFileAlreadyExistsException;
import com.ceph.fs.CephFileExtent;
import com.ceph.fs.CephMount;
import com.ceph.fs.CephNotDirectoryException;
import com.ceph.fs.CephStat;
import com.ceph.fs.CephStatVFS;

import static org.apache.hadoop.fs.impl.PathCapabilitiesSupport.validatePathCapabilityArgs;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.fs.CommonPathCapabilities;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FsServerDefaults;
import org.apache.hadoop.fs.FsStatus;
import org.apache.hadoop.fs.Options;
import org.apache.hadoop.fs.ParentNotDirectoryException;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathIsNotEmptyDirectoryException;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.DataChecksum;
import org.apache.hadoop.util.Progressable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CephFS 的 Hadoop {@link FileSystem} 实现（{@code ceph://} scheme）。
 *
 * <p>本类只做 Hadoop 语义适配（路径解析、异常映射、rename/delete/mkdirs 语义
 * 修正 —— 架构文档 §4 差异表），所有 CephFS 调用经由 {@link CephFsProto}
 * （生产实现 {@link CephTalker}；单测注入 Mockito mock）。
 *
 * <p><b>T03 交付范围</b>：生命周期 + 全部元数据操作。T04 补齐数据流方法
 * {@link #open(Path, int)} / {@link #create} / {@link #append}。
 *
 * <p><b>workingDir</b>：纯 Java 侧维护（不使用 ceph_chdir），初始
 * {@code /user/<ugi.shortUserName>}（架构文档 §4-7）。
 *
 * <p><b>uid/gid → 用户名</b>（架构文档 §4-6）：不做 NSS 查询。uid 与当前进程
 * uid 相同时报告当前用户名，否则报告数字字符串；gid 一律数字字符串
 * （无法在不查 NSS 的前提下取得组名）。
 */
public class CephFileSystem extends FileSystem {

  private static final Logger LOG = LoggerFactory.getLogger(CephFileSystem.class);

  /** 当前进程 uid/gid（Linux 读 /proc/self/status；失败为 -1，永不匹配）。 */
  static final int PROCESS_UID = readProcSelfStatusId("Uid:");
  static final int PROCESS_GID = readProcSelfStatusId("Gid:");

  private final CephFsProto proto;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private URI uri;
  private Path workingDir;
  private String userName;
  private short defaultReplication = 3;
  private long defaultBlockSize = CephConfigKeys.CEPH_OBJECT_SIZE_DEFAULT;

  /** 生产构造器：ServiceLoader / 反射使用，抽象层为真实 {@link CephTalker}。 */
  public CephFileSystem() {
    this(new CephTalker());
  }

  /** 测试构造器（包可见）：注入 mock {@link CephFsProto}。 */
  CephFileSystem(CephFsProto proto) {
    this.proto = proto;
  }

  // ── 生命周期 ──────────────────────────────────────────────────────────

  @Override
  public void initialize(URI uri, Configuration conf) throws IOException {
    super.initialize(uri, conf);
    setConf(conf);

    proto.initialize(uri, conf);

    String authority = uri.getAuthority();
    this.uri = URI.create(CephConfigKeys.CEPH_URI_SCHEME + "://"
        + (authority == null ? "" : authority) + "/");

    try {
      this.userName = UserGroupInformation.getCurrentUser().getShortUserName();
    } catch (IOException e) {
      this.userName = System.getProperty("user.name", "hadoop");
      LOG.warn("Failed to resolve UGI current user, falling back to {}", userName, e);
    }
    this.workingDir = getHomeDirectory();

    this.defaultReplication = (short) conf.getInt(
        CephConfigKeys.CEPH_REPLICATION_KEY, CephConfigKeys.CEPH_REPLICATION_DEFAULT);
    this.defaultBlockSize = conf.getLong(
        CephConfigKeys.CEPH_OBJECT_SIZE_KEY, CephConfigKeys.CEPH_OBJECT_SIZE_DEFAULT);

    LOG.debug("CephFileSystem initialized: uri={}, workingDir={}", this.uri, workingDir);
  }

  @Override
  public String getScheme() {
    return CephConfigKeys.CEPH_URI_SCHEME;
  }

  @Override
  public URI getUri() {
    return uri;
  }

  @Override
  public void close() throws IOException {
    if (closed.getAndSet(true)) {
      return; // 幂等
    }
    try {
      // 先走基类（处理 deleteOnExit、摘除缓存），此时 proto 仍可用
      super.close();
    } finally {
      proto.shutdown();
    }
  }

  @Override
  public Path getHomeDirectory() {
    // 与 initialize 中 workingDir 初值一致：/user/<ugi.shortUserName>（§4-7）
    return makeQualified(new Path(USER_HOME_PREFIX + "/" + userName));
  }

  @Override
  public void setWorkingDirectory(Path dir) {
    workingDir = makeAbsolute(dir);
  }

  @Override
  public Path getWorkingDirectory() {
    return workingDir;
  }

  /** 相对路径按 workingDir 解析为绝对路径（纯 Java，架构文档 §4-7）。 */
  private Path makeAbsolute(Path path) {
    if (path == null) {
      return workingDir;
    }
    return path.isAbsolute() ? path : new Path(workingDir, path);
  }

  // ── 查询类 ────────────────────────────────────────────────────────────

  @Override
  public FileStatus getFileStatus(Path f) throws IOException {
    Path abs = makeAbsolute(f);
    CephStat stat = new CephStat();
    // 不存在 → FileNotFoundException 直接透传；祖先为文件同样归一化为 FNF
    lstatResolved(abs, stat, false);
    return toFileStatus(abs, stat);
  }

  @Override
  public FileStatus[] listStatus(Path f) throws FileNotFoundException, IOException {
    Path abs = makeAbsolute(f);
    // 不存在 → FNF 透传；存在但非目录 → null（CephFsProto 契约）
    String[] names = proto.listdir(abs);
    if (names == null) {
      return new FileStatus[] { getFileStatus(abs) };
    }
    FileStatus[] result = new FileStatus[names.length];
    for (int i = 0; i < names.length; i++) {
      result[i] = getFileStatus(new Path(abs, names[i]));
    }
    return result;
  }

  /** lstat 结果 → Hadoop FileStatus（架构文档 §3.2 / §4-4 / §4-6 / §4-8）。 */
  private FileStatus toFileStatus(Path abs, CephStat stat) {
    boolean isDir = stat.isDir();
    long length = isDir ? 0 : stat.size;
    short replication = isDir ? 0 : defaultReplication;
    long blockSize = isDir ? 0
        : (stat.blksize > 0 ? stat.blksize : getDefaultBlockSize());
    // CephStat 时间戳单位已是毫秒（T02 交接：JNI 层完成纳秒→毫秒换算）
    return new FileStatus(length, isDir, replication, blockSize,
        stat.m_time, stat.a_time,
        new FsPermission((short) (stat.mode & 01777)),
        ownerName(stat.uid), groupName(stat.gid),
        makeQualified(abs));
  }

  private String ownerName(int uid) {
    if (uid == PROCESS_UID && userName != null) {
      return userName;
    }
    return Integer.toString(uid);
  }

  private String groupName(int gid) {
    // 不做 NSS 查询（§4-6），一律数字字符串
    return Integer.toString(gid);
  }

  // ── 修改类（严格按架构文档 §4 语义差异表） ──────────────────────────────

  @Override
  public boolean mkdirs(Path f, FsPermission permission) throws IOException {
    Path abs = makeAbsolute(f);

    // libcephfs mkdirs 在末段为已存在文件时静默成功（T02 钉死），
    // Hadoop 语义必须先 lstat 自查：已存在目录→true；已存在文件→异常。
    // 祖先为文件 → lstatResolved 归一化为 ParentNotDirectoryException（T06）。
    CephStat stat = new CephStat();
    try {
      lstatResolved(abs, stat, true);
      if (stat.isDir()) {
        return true;
      }
      throw new FileAlreadyExistsException(
          "mkdirs: " + abs + " already exists but is not a directory");
    } catch (FileNotFoundException ignored) {
      // 不存在，正常创建
    }

    FsPermission perm = (permission != null) ? permission : FsPermission.getDirDefault();
    int mode = perm.applyUMask(FsPermission.getUMask(getConf())).toShort();
    try {
      proto.mkdirs(abs, mode);
    } catch (IOException e) {
      // 中间级为已存在文件 → CephNotDirectoryException → ParentNotDirectoryException
      throw mapCephException(e, abs);
    }
    return true;
  }

  @Override
  public boolean delete(Path f, boolean recursive) throws IOException {
    Path abs = makeAbsolute(f);

    CephStat stat = new CephStat();
    try {
      lstatResolved(abs, stat, false);
    } catch (FileNotFoundException e) {
      return false; // 不存在（含祖先为文件）→ false（filesystem.md：delete 缺失路径为 no-op）
    }

    if (!stat.isDir()) {
      try {
        proto.unlink(abs);
      } catch (FileNotFoundException e) {
        return false; // 并发删除竞态
      } catch (IOException e) {
        throw mapCephException(e, abs);
      }
      return true;
    }

    boolean isRoot = abs.isRoot();
    if (isRoot && !recursive) {
      // 根目录删除保护（任务书）：delete("/") 且 !recursive → false
      LOG.warn("Refusing to delete root directory (recursive=false)");
      return false;
    }

    String[] children = proto.listdir(abs);
    if (children == null) {
      // 竞态：lstat 后目录被替换为文件
      proto.unlink(abs);
      return true;
    }
    if (!recursive && children.length > 0) {
      throw new PathIsNotEmptyDirectoryException(abs.toString());
    }
    for (String name : children) {
      deleteSubtree(new Path(abs, name)); // §4-2：Java 侧后序遍历递归删除
    }
    if (isRoot) {
      return true; // 只清空内容，根本身保留
    }
    try {
      proto.rmdir(abs);
    } catch (FileNotFoundException e) {
      return false; // 并发删除竞态
    } catch (IOException e) {
      throw mapCephException(e, abs);
    }
    return true;
  }

  /** 后序遍历删除子树（libcephfs rmdir 仅支持空目录，架构文档 §4-2）。 */
  private void deleteSubtree(Path p) throws IOException {
    CephStat stat = new CephStat();
    try {
      proto.lstat(p, stat);
    } catch (FileNotFoundException e) {
      return; // 并发已删，视为成功
    }
    if (stat.isDir()) {
      String[] children = proto.listdir(p);
      if (children != null) {
        for (String name : children) {
          deleteSubtree(new Path(p, name));
        }
        proto.rmdir(p);
        return;
      }
      // children == null：竞态变为文件，落入 unlink
    }
    proto.unlink(p);
  }

  /**
   * FileContext 的无覆盖文件 rename 使用 MDS 原子 hard-link 抢占目标，再移除源目录项。
   * Ceph 16 的 {@code ceph_rename} 没有 RENAME_NOREPLACE 标志，直接使用基类的
   * “检查后 rename”会留下 TOCTOU 窗口。目录不能创建硬链接，OVERWRITE 也需要真正的
   * POSIX rename，二者继续走 Hadoop 基类路径。
   */
  @Override
  protected void rename(Path src, Path dst, Options.Rename... options)
      throws IOException {
    boolean overwrite = false;
    if (options != null) {
      for (Options.Rename option : options) {
        overwrite |= option == Options.Rename.OVERWRITE;
      }
    }

    Path absSrc = makeAbsolute(src);
    Path absDst = makeAbsolute(dst);
    FileStatus sourceStatus = getFileLinkStatus(absSrc);
    if (overwrite || !sourceStatus.isFile() || sourceStatus.isSymlink()) {
      super.rename(absSrc, absDst, options);
      return;
    }

    try {
      getFileLinkStatus(absDst);
      throw new FileAlreadyExistsException(
          "rename destination " + absDst + " already exists.");
    } catch (FileNotFoundException destinationAbsent) {
      // link() 将在竞争窗口内原子地再次检查目标不存在。
    }

    Path parent = absDst.getParent();
    if (parent != null) {
      FileStatus parentStatus = getFileStatus(parent);
      if (!parentStatus.isDirectory()) {
        throw new ParentNotDirectoryException(
            "rename destination parent " + parent + " is a file.");
      }
    }

    try {
      proto.link(absSrc, absDst);
    } catch (CephFileAlreadyExistsException raced) {
      throw new FileAlreadyExistsException(
          "rename destination " + absDst + " already exists.");
    }

    try {
      proto.unlink(absSrc);
    } catch (FileNotFoundException alreadyUnlinked) {
      // link 已成功，源又被并发移除时目标仍是唯一有效目录项，rename 结果可视为成功。
    } catch (IOException unlinkFailure) {
      try {
        proto.unlink(absDst);
      } catch (IOException rollbackFailure) {
        unlinkFailure.addSuppressed(rollbackFailure);
      }
      throw unlinkFailure;
    }
  }

  @Override
  public boolean rename(Path src, Path dst) throws IOException {
    Path absSrc = makeAbsolute(src);
    Path absDst = makeAbsolute(dst);

    CephStat srcStat = new CephStat();
    try {
      lstatResolved(absSrc, srcStat, false);
    } catch (FileNotFoundException e) {
      return false; // §4-1：src 不存在（含祖先为文件）→ false
    }
    if (absSrc.isRoot()) {
      return false; // 根目录不可重命名
    }
    if (samePath(absSrc, absDst)) {
      return true; // §4-1：src==dst 且存在 → true
    }

    CephStat dstStat = new CephStat();
    boolean dstExists = true;
    try {
      lstatResolved(absDst, dstStat, false);
    } catch (FileNotFoundException e) {
      dstExists = false;
    }

    if (dstExists && dstStat.isDir()) {
      // §4-1：dst 为已存在目录 → 移入其下（与 HDFS 一致）
      absDst = new Path(absDst, absSrc.getName());
      if (samePath(absSrc, absDst)) {
        return true; // 移动到自身所在目录 = no-op
      }
      try {
        proto.lstat(absDst, new CephStat());
        return false; // dst/srcName 已被占用（文件或目录均拒绝，与 HDFS 一致）
      } catch (FileNotFoundException available) {
        // 目标位可用
      }
    } else if (dstExists) {
      return false; // §4-1：dst 为已存在文件 → false（禁止 POSIX 静默覆盖）
    } else {
      // dst 不存在：其父目录必须已存在且为目录，否则 false
      Path parent = absDst.getParent();
      if (parent != null && !parent.isRoot()) {
        CephStat parentStat = new CephStat();
        try {
          lstatResolved(parent, parentStat, false);
        } catch (FileNotFoundException e) {
          return false;
        }
        if (!parentStat.isDir()) {
          return false;
        }
      }
    }

    if (srcStat.isDir() && isAncestorOf(absSrc, absDst)) {
      return false; // 目录不可移入自身子树
    }

    try {
      proto.rename(absSrc, absDst);
    } catch (FileNotFoundException e) {
      return false; // 竞态：src 已消失
    } catch (IOException e) {
      throw mapCephException(e, absDst);
    }
    return true;
  }

  /** 忽略 scheme/authority，仅按 CephFS 内路径比较。 */
  private static boolean samePath(Path a, Path b) {
    return a.toUri().getPath().equals(b.toUri().getPath());
  }

  private static boolean isAncestorOf(Path ancestor, Path child) {
    String a = ancestor.toUri().getPath();
    String c = child.toUri().getPath();
    return c.startsWith(a.endsWith("/") ? a : a + "/");
  }

  @Override
  public void setTimes(Path p, long mtime, long atime) throws IOException {
    Path abs = makeAbsolute(p);
    CephStat stat = new CephStat();
    int mask = 0;
    if (mtime != -1) {
      stat.m_time = mtime; // 毫秒（CephStat 时间戳约定）
      mask |= CephMount.SETATTR_MTIME;
    }
    if (atime != -1) {
      stat.a_time = atime;
      mask |= CephMount.SETATTR_ATIME;
    }
    if (mask == 0) {
      return;
    }
    try {
      proto.setattr(abs, stat, mask);
    } catch (IOException e) {
      throw mapCephException(e, abs);
    }
  }

  @Override
  public void setPermission(Path p, FsPermission permission) throws IOException {
    if (permission == null) {
      return;
    }
    Path abs = makeAbsolute(p);
    try {
      proto.chmod(abs, permission.toShort());
    } catch (IOException e) {
      throw mapCephException(e, abs);
    }
  }

  @Override
  public void setOwner(Path p, String username, String groupname) throws IOException {
    Path abs = makeAbsolute(p);
    CephStat stat = new CephStat();
    int mask = 0;
    if (username != null && !username.isEmpty()) {
      try {
        stat.uid = Integer.parseInt(username);
        mask |= CephMount.SETATTR_UID;
      } catch (NumberFormatException e) {
        LOG.warn("setOwner: CephFS has no user-name mapping; "
            + "ignoring non-numeric owner '{}' for {}", username, abs);
      }
    }
    if (groupname != null && !groupname.isEmpty()) {
      try {
        stat.gid = Integer.parseInt(groupname);
        mask |= CephMount.SETATTR_GID;
      } catch (NumberFormatException e) {
        LOG.warn("setOwner: CephFS has no group-name mapping; "
            + "ignoring non-numeric group '{}' for {}", groupname, abs);
      }
    }
    if (mask == 0) {
      return; // 无可生效项 → no-op（任务书：仅数字 uid/gid 可解析时生效）
    }
    try {
      proto.setattr(abs, stat, mask);
    } catch (IOException e) {
      throw mapCephException(e, abs);
    }
  }

  @Override
  public boolean setReplication(Path src, short replication) throws IOException {
    // §4-4：CephFS 无副本数概念（由 pool 决定）
    LOG.debug("setReplication is not supported on CephFS (pool-managed); "
        + "returning false for {}", src);
    return false;
  }

  @Override
  public short getDefaultReplication() {
    return defaultReplication;
  }

  @Override
  public long getDefaultBlockSize() {
    return defaultBlockSize;
  }

  @Override
  public long getDefaultBlockSize(Path f) {
    // CephFS 的 blockSize 是全局 layout 配置（ceph.object.size），与路径无关
    return getDefaultBlockSize();
  }

  @SuppressWarnings("deprecation")
  @Override
  @Deprecated
  public FsServerDefaults getServerDefaults() throws IOException {
    Configuration conf = getConf();
    // CephFS 无客户端校验和（数据完整性由 RADOS 负责）→ checksum type NULL；
    // writePacketSize 沿用 Hadoop 惯例值 64KB（对 CephFS 仅为报告值）。
    return new FsServerDefaults(
        getDefaultBlockSize(),
        conf.getInt("io.bytes.per.checksum", 512), // 与 FileSystem 基类同源（无公开常量）
        64 * 1024,
        getDefaultReplication(),
        streamBufferSize(),
        false,
        conf.getLong(CommonConfigurationKeysPublic.FS_TRASH_INTERVAL_KEY,
            CommonConfigurationKeysPublic.FS_TRASH_INTERVAL_DEFAULT),
        DataChecksum.Type.NULL,
        "");
  }

  @SuppressWarnings("deprecation")
  @Override
  public FsServerDefaults getServerDefaults(Path p) throws IOException {
    return getServerDefaults();
  }

  @Override
  public boolean hasPathCapability(Path path, String capability) throws IOException {
    // T06 契约测试要求声明能力（AbstractContractAppendTest.testFileSystemDeclaresCapability）：
    // CephFS 原生支持 append（O_APPEND）与 POSIX 权限位。其余沿用基类默认（false）。
    switch (validatePathCapabilityArgs(makeQualified(path), capability)) {
    case CommonPathCapabilities.FS_APPEND:
    case CommonPathCapabilities.FS_PERMISSIONS:
      return true;
    default:
      return super.hasPathCapability(path, capability);
    }
  }

  @Override
  public FsStatus getStatus(Path p) throws IOException {
    // p 为 null 时按基类约定取默认分区（CephFS 单一分区，用根即可，
    // 且根必然存在；workingDir 可能尚未创建，不能用 makeAbsolute(null)）
    Path abs = (p == null) ? new Path("/") : makeAbsolute(p);
    CephStatVFS stat;
    try {
      stat = proto.statfs(abs);
    } catch (IOException e) {
      throw mapCephException(e, abs);
    }
    // statvfs 语义：blocks/bavail 以 frsize 为单位（frsize 异常时回退 bsize）
    long unit = stat.frsize > 0 ? stat.frsize : Math.max(stat.bsize, 1);
    long capacity = stat.blocks * unit;
    long remaining = stat.bavail * unit;
    // CephStatVFS 无 bfree 字段（JNI 未透传），used 用 capacity - remaining 计算
    return new FsStatus(capacity, capacity - remaining, remaining);
  }

  // ── 数据局部性（T05，架构文档 §3.2 BlockLocation 行） ────────────────────

  /** OSD 地址解析失败时的降级 hosts（不让作业提交失败，任务书 T05）。 */
  private static final String[] LOCALHOST_HOSTS = { "localhost" };

  @Override
  public BlockLocation[] getFileBlockLocations(FileStatus file, long start, long len)
      throws IOException {
    // 入参契约与基类 FileSystem#getFileBlockLocations 一致
    if (file == null) {
      return null;
    }
    if (start < 0 || len < 0) {
      throw new IllegalArgumentException("Invalid start or len parameter");
    }
    if (file.isDirectory() || file.getLen() <= start) {
      return new BlockLocation[0];
    }

    Path abs = makeAbsolute(file.getPath());
    long fileLen = file.getLen();
    // 末端钳制到文件长度；len 可能为 Long.MAX_VALUE，先比差值避免溢出
    long end = (len > fileLen - start) ? fileLen : start + len;
    // getFileExtent 失败时的降级步长：按文件 blockSize（无效时取默认）
    long fallbackStep = file.getBlockSize() > 0 ? file.getBlockSize() : getDefaultBlockSize();

    int fd;
    try {
      fd = proto.open(abs, CephMount.O_RDONLY, 0);
    } catch (IOException e) {
      throw mapCephException(e, abs);
    }

    List<BlockLocation> blocks = new ArrayList<>();
    // 同一 OSD 在多个 extent 重复出现（vstart 单 OSD 时必然），调用内缓存地址
    Map<Integer, String> osdHostCache = new HashMap<>();
    try {
      long cur = start;
      while (cur < end) {
        long blockLen;
        String[] hosts;
        try {
          CephFileExtent extent = proto.getFileExtent(fd, cur);
          long extentLen = extent.getLength();
          if (extentLen <= 0) {
            throw new IOException("libcephfs returned empty extent at offset "
                + cur + ": " + extent);
          }
          // extent.getLength() 是从 cur 到该 extent（object）末尾的剩余字节数：
          // start 非对齐时首块自然变短，后续块按 object 边界切分
          blockLen = Math.min(extentLen, end - cur);
          hosts = resolveOsdHosts(extent.getOSDs(), osdHostCache);
        } catch (IOException e) {
          // 单个 extent 解析失败：降级 localhost 并按 blockSize 步进，
          // 不让作业提交失败（任务书 T05）
          LOG.warn("Failed to resolve block location for {} at offset {}; "
              + "falling back to localhost", abs, cur, e);
          blockLen = Math.min(fallbackStep, end - cur);
          hosts = LOCALHOST_HOSTS;
        }
        // 不做 crush 机架感知（任务书边界）：topologyPaths 留空
        blocks.add(new BlockLocation(hosts, hosts, cur, blockLen));
        cur += blockLen;
      }
    } finally {
      try {
        proto.close(fd);
      } catch (IOException e) {
        LOG.warn("Failed to close temporary fd {} for {}", fd, abs, e);
      }
    }
    return blocks.toArray(new BlockLocation[0]);
  }

  /**
   * OSD id 列表 → host 字符串数组（IP 文本，与 {@code ceph osd dump} 一致）。
   * 任一 OSD 解析失败即抛 IOException，由调用方整段降级为 localhost。
   */
  private String[] resolveOsdHosts(int[] osds, Map<Integer, String> cache)
      throws IOException {
    if (osds == null || osds.length == 0) {
      throw new IOException("extent has no OSDs");
    }
    String[] hosts = new String[osds.length];
    for (int i = 0; i < osds.length; i++) {
      String host = cache.get(osds[i]);
      if (host == null) {
        InetAddress addr = proto.getOsdAddress(osds[i]);
        if (addr == null) {
          throw new IOException("null address for osd." + osds[i]);
        }
        // 用 IP 文本（不触发反向 DNS；与 ceph osd dump 的地址直接可比）
        host = addr.getHostAddress();
        cache.put(osds[i], host);
      }
      hosts[i] = host;
    }
    return hosts;
  }

  // ── 异常映射（集中一处，任务书工作内容 4） ──────────────────────────────

  /**
   * lstat，并把"祖先为普通文件"的路径解析失败归一化（T06 契约测试暴露）。
   *
   * <p>CephFS 客户端（Client::inode_permission）对经过普通文件的路径解析：
   * 文件无 x 位时返回 EACCES（POSIX 内核为 ENOTDIR，即使 uid=0 也如此），
   * 有 x 位时返回 ENOTDIR；JNI 不透传 errno，EACCES 仅表现为裸
   * IOException("Permission denied")。因此对 lstat 的非 FNF 失败补一次
   * 祖先探测：若最近的已存在祖先是普通文件，则按 Hadoop 语义归一化——
   * 变更语境（mkdirs/create）抛 {@link ParentNotDirectoryException}，
   * 查询语境（getFileStatus/open/delete/rename）抛
   * {@link FileNotFoundException}（该路径在 Hadoop 模型中不存在）。
   * 真正的目录权限不足不受影响（祖先探测判定为目录时原样抛出）。
   *
   * @param mutating true=变更语境；false=查询语境
   */
  private void lstatResolved(Path abs, CephStat stat, boolean mutating)
      throws IOException {
    try {
      proto.lstat(abs, stat);
    } catch (FileNotFoundException e) {
      throw e;
    } catch (IOException e) {
      if (e instanceof CephNotDirectoryException || nearestExistingAncestorIsFile(abs)) {
        if (mutating) {
          throw (IOException) new ParentNotDirectoryException(
              "Ancestor path is not a directory: " + abs).initCause(e);
        }
        throw (FileNotFoundException) new FileNotFoundException(
            "Path does not exist (ancestor is a file): " + abs).initCause(e);
      }
      throw mapCephException(e, abs);
    }
  }

  /** 自下而上找最近的可 lstat 祖先：是普通文件则返回 true。 */
  private boolean nearestExistingAncestorIsFile(Path p) {
    for (Path a = p.getParent(); a != null && !a.isRoot(); a = a.getParent()) {
      CephStat stat = new CephStat();
      try {
        proto.lstat(a, stat);
      } catch (IOException e) {
        continue; // 不存在或被更深层的文件祖先挡住，继续向上
      }
      return !stat.isDir();
    }
    return false; // 根必然是目录
  }

  /**
   * proto（libcephfs errno 语义）→ Hadoop FS 规范异常。
   *
   * <ul>
   *   <li>ENOENT：JNI 已抛 {@link FileNotFoundException}，原样返回；</li>
   *   <li>EEXIST（CephFileAlreadyExistsException）→
   *       {@link FileAlreadyExistsException}；</li>
   *   <li>ENOTDIR（CephNotDirectoryException）→
   *       {@link ParentNotDirectoryException}；</li>
   *   <li>其余保持 IOException 原样。</li>
   * </ul>
   */
  private IOException mapCephException(IOException e, Path path) {
    if (e instanceof CephFileAlreadyExistsException) {
      return (IOException) new FileAlreadyExistsException(
          "Path already exists: " + path).initCause(e);
    }
    if (e instanceof CephNotDirectoryException) {
      return (IOException) new ParentNotDirectoryException(
          "Parent path is not a directory: " + path).initCause(e);
    }
    return e;
  }

  // ── 数据流（T04 实现，此处显式留桩） ────────────────────────────────────

  @Override
  public FSDataInputStream open(Path f, int bufferSize) throws IOException {
    Path abs = makeAbsolute(f);
    CephStat stat = new CephStat();
    lstatResolved(abs, stat, false);
    if (stat.isDir()) {
      throw new FileNotFoundException("Cannot open directory for read: " + abs);
    }

    int streamBufferSize = streamBufferSize();
    int fd;
    try {
      fd = proto.open(abs, CephMount.O_RDONLY, 0);
    } catch (IOException e) {
      throw mapCephException(e, abs);
    }
    return new FSDataInputStream(new CephInputStream(proto, fd, stat.size,
        streamBufferSize, statistics));
  }

  @Override
  public FSDataOutputStream create(Path f, FsPermission permission,
      boolean overwrite, int bufferSize, short replication, long blockSize,
      Progressable progress) throws IOException {
    return createInternal(f, permission, overwrite, blockSize, true);
  }

  @Override
  public FSDataOutputStream createNonRecursive(Path f, FsPermission permission,
      boolean overwrite, int bufferSize, short replication, long blockSize,
      Progressable progress) throws IOException {
    return createInternal(f, permission, overwrite, blockSize, false);
  }

  /**
   * {@link EnumSet}&lt;CreateFlag&gt; 版重载（T06）：FileSystem 基类默认抛
   * "createNonRecursive unsupported"，而 createFile(path) 建造器（契约测试
   * AbstractContractCreateTest 的 builder 路径）与部分下游（HBase WAL）走的
   * 正是本重载。语义与 boolean 版一致：OVERWRITE 标志位映射 overwrite，
   * 父目录缺失不创建（与 RawLocalFileSystem 同法）。APPEND/SYNC_BLOCK 等
   * 其余标志不支持，交由 createInternal 的既有分支处理（APPEND 场景应走
   * {@link #append}）。
   */
  @Override
  public FSDataOutputStream createNonRecursive(Path f, FsPermission permission,
      EnumSet<CreateFlag> flags, int bufferSize, short replication, long blockSize,
      Progressable progress) throws IOException {
    return createInternal(f, permission, flags.contains(CreateFlag.OVERWRITE),
        blockSize, false);
  }

  @Override
  public FSDataOutputStream append(Path f, int bufferSize, Progressable progress)
      throws IOException {
    Path abs = makeAbsolute(f);
    CephStat stat = new CephStat();
    lstatResolved(abs, stat, false);
    if (stat.isDir()) {
      throw new FileNotFoundException("Cannot append to directory: " + abs);
    }

    int streamBufferSize = streamBufferSize();
    int fd;
    try {
      fd = proto.open(abs, CephMount.O_WRONLY | CephMount.O_APPEND, 0);
    } catch (IOException e) {
      throw mapCephException(e, abs);
    }
    CephOutputStream out =
        new CephOutputStream(proto, fd, streamBufferSize, statistics);
    return new FSDataOutputStream(out, null, stat.size);
  }

  private FSDataOutputStream createInternal(Path f, FsPermission permission,
      boolean overwrite, long blockSize, boolean createParent) throws IOException {
    Path abs = makeAbsolute(f);
    boolean exists = true;
    CephStat stat = new CephStat();
    try {
      // 祖先为文件 → ParentNotDirectoryException（file/file、file/dir/file 场景）
      lstatResolved(abs, stat, true);
      if (stat.isDir()) {
        throw new FileAlreadyExistsException(
            "Cannot create file over directory: " + abs);
      }
      if (!overwrite) {
        throw new FileAlreadyExistsException("File already exists: " + abs);
      }
    } catch (FileNotFoundException e) {
      exists = false;
    }

    ensureCreateParent(abs, createParent);

    FsPermission perm = permission != null ? permission : FsPermission.getFileDefault();
    int mode = perm.applyUMask(FsPermission.getUMask(getConf())).toShort();
    int flags = CephMount.O_WRONLY | CephMount.O_CREAT;
    if (exists && overwrite) {
      flags |= CephMount.O_TRUNC;
    } else {
      flags |= CephMount.O_EXCL;
    }

    // §4-5：blockSize → CephFS layout。Ceph 要求 stripe_unit / object_size 为
    // 64KB（CEPH_MIN_STRIPE_UNIT）的整数倍且 object_size 为 stripe_unit 的整数倍
    // （file_layout_t::is_valid），而 Hadoop 调用方（含契约测试的 writeDataset）
    // 会传任意正数 blockSize（如 1024）。此处按"blockSize 是提示值"的 Hadoop
    // 惯例向上归一化为合法 layout：object_size = roundUp(blockSize, 64KB)，
    // stripe_unit = object_size、stripe_count = 1（简单 layout），
    // 取代 T04 原 min(blockSize, 默认) 口径 —— 消除 T04 观察项中
    // "非 stripe_unit 整数倍 blockSize → EINVAL" 的隐晦报错（T06 定稿，见 PROGRESS.md）。
    long effectiveBlockSize = blockSize > 0 ? blockSize : getDefaultBlockSize();
    int objectSize = normalizeLayoutSize(effectiveBlockSize);
    int stripeUnit = objectSize;
    String dataPool = firstDataPool();
    int streamBufferSize = streamBufferSize();

    int fd;
    try {
      fd = proto.open(abs, flags, mode, stripeUnit, 1, objectSize, dataPool);
    } catch (IOException e) {
      throw mapCephException(e, abs);
    }
    CephOutputStream out =
        new CephOutputStream(proto, fd, streamBufferSize, statistics);
    return new FSDataOutputStream(out, null, 0);
  }

  private void ensureCreateParent(Path abs, boolean createParent) throws IOException {
    Path parent = abs.getParent();
    if (parent == null || parent.isRoot()) {
      return;
    }

    CephStat parentStat = new CephStat();
    try {
      // 祖先为文件（file/dir/file 的 file/dir 段）→ ParentNotDirectoryException
      lstatResolved(parent, parentStat, true);
      if (!parentStat.isDir()) {
        throw new ParentNotDirectoryException(
            "Parent path is not a directory: " + parent);
      }
      return;
    } catch (FileNotFoundException e) {
      if (!createParent) {
        throw new FileNotFoundException(
            "Parent directory does not exist: " + parent);
      }
    }

    mkdirs(parent, FsPermission.getDirDefault());
  }

  private int streamBufferSize() {
    int size = getConf().getInt(CephConfigKeys.CEPH_CLIENT_BUFFER_SIZE_KEY,
        CephConfigKeys.CEPH_CLIENT_BUFFER_SIZE_DEFAULT);
    return size > 0 ? size : CephConfigKeys.CEPH_CLIENT_BUFFER_SIZE_DEFAULT;
  }

  /** Ceph layout 尺寸约束（src/common/fs_types.cc file_layout_t::is_valid）。 */
  static final long CEPH_MIN_STRIPE_UNIT = 64L * 1024;
  /** int 可表示的最大 64KB 整数倍（layout 参数为 int）。 */
  static final long CEPH_MAX_LAYOUT_SIZE =
      Integer.MAX_VALUE - (Integer.MAX_VALUE % CEPH_MIN_STRIPE_UNIT);

  /**
   * 把调用方 blockSize 归一化为合法 Ceph layout 尺寸：
   * 向上取整到 64KB 的整数倍，并钳制到 [64KB, CEPH_MAX_LAYOUT_SIZE]。
   */
  static int normalizeLayoutSize(long requested) {
    // 先钳制再取整，避免 requested 接近 Long.MAX_VALUE 时加法溢出；
    // CEPH_MAX_LAYOUT_SIZE 本身是 64KB 的整数倍，取整结果不会越界
    long size = Math.min(Math.max(requested, 1), CEPH_MAX_LAYOUT_SIZE);
    return (int) (((size + CEPH_MIN_STRIPE_UNIT - 1) / CEPH_MIN_STRIPE_UNIT)
        * CEPH_MIN_STRIPE_UNIT);
  }

  private String firstDataPool() {
    String pools = getConf().get(CephConfigKeys.CEPH_DATA_POOLS_KEY,
        CephConfigKeys.CEPH_DATA_POOLS_DEFAULT);
    if (pools == null) {
      return null;
    }
    for (String pool : pools.split(",")) {
      String trimmed = pool.trim();
      if (!trimmed.isEmpty()) {
        return trimmed;
      }
    }
    return null;
  }

  int openFileDescriptorCountForTests() {
    if (proto instanceof CephTalker) {
      return ((CephTalker) proto).openFileDescriptorCount();
    }
    return -1;
  }

  // ── 工具 ──────────────────────────────────────────────────────────────

  /**
   * 从 /proc/self/status 读取进程真实 uid/gid（Linux）。失败返回 -1
   * （与任何 CephFS uid/gid 都不相等，owner/group 回落为数字字符串）。
   * 不做 NSS 查询（架构文档 §4-6）。
   */
  private static int readProcSelfStatusId(String prefix) {
    try {
      List<String> lines =
          Files.readAllLines(Paths.get("/proc/self/status"), StandardCharsets.UTF_8);
      for (String line : lines) {
        if (line.startsWith(prefix)) {
          String[] fields = line.substring(prefix.length()).trim().split("\\s+");
          return Integer.parseInt(fields[0]); // real id
        }
      }
    } catch (Exception e) {
      LOG.debug("Cannot determine process id from /proc/self/status", e);
    }
    return -1;
  }
}
