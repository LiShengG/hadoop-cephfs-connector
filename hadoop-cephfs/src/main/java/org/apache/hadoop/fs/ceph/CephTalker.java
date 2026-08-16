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

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import com.ceph.fs.CephFileExtent;
import com.ceph.fs.CephMount;
import com.ceph.fs.CephNotDirectoryException;
import com.ceph.fs.CephNotMountedException;
import com.ceph.fs.CephStat;
import com.ceph.fs.CephStatVFS;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link CephFsProto} 的生产实现：封装 {@code com.ceph.fs.CephMount}（JNI）。
 *
 * <p>线程模型：libcephfs 自身线程安全（{@code CephMount} 内部有读写锁保护
 * native 指针）；本类不额外加锁。一个 {@code CephTalker} 对应一个 mount，
 * 由所属 {@code CephFileSystem} 实例独占。
 *
 * <p>运行前提（docs/environments/E1.md）：JVM 须以
 * {@code -Djava.library.path=<dist/native>:<ceph/build/lib>} 启动，且进程环境含
 * {@code LD_LIBRARY_PATH=<ceph/build/lib>}，否则 {@code CephMount} 类加载即抛
 * {@code UnsatisfiedLinkError}。
 */
class CephTalker extends CephFsProto {

  private static final Logger LOG = LoggerFactory.getLogger(CephTalker.class);

  /** null 表示未 initialize 或已 shutdown。 */
  private volatile CephMount mount;
  private final AtomicInteger openFileDescriptors = new AtomicInteger();

  @Override
  void initialize(URI uri, Configuration conf) throws IOException {
    if (mount != null) {
      throw new IllegalStateException("CephTalker already initialized");
    }

    // 1. 创建挂载句柄（authId 为 null 时用 libcephfs 默认 client id）
    String authId = conf.get(CephConfigKeys.CEPH_AUTH_ID_KEY,
        CephConfigKeys.CEPH_AUTH_ID_DEFAULT);
    CephMount m = new CephMount(authId);

    // 2. 读 ceph.conf（mon 地址等以此为准，勿硬编码 —— docs/environments/E1.md）
    String confFile = conf.get(CephConfigKeys.CEPH_CONF_FILE_KEY,
        CephConfigKeys.CEPH_CONF_FILE_DEFAULT);
    if (confFile != null) {
      m.conf_read_file(confFile);
    }

    // 3. 逐项下发额外配置（后写的覆盖 ceph.conf 中的同名项）
    String opts = conf.get(CephConfigKeys.CEPH_CONF_OPTIONS_KEY,
        CephConfigKeys.CEPH_CONF_OPTIONS_DEFAULT);
    if (opts != null) {
      for (String opt : opts.split(";")) {
        opt = opt.trim();
        if (opt.isEmpty()) {
          continue;
        }
        int eq = opt.indexOf('=');
        if (eq <= 0) {
          throw new IllegalArgumentException(
              "Invalid " + CephConfigKeys.CEPH_CONF_OPTIONS_KEY
                  + " entry (expected key=value): " + opt);
        }
        m.conf_set(opt.substring(0, eq).trim(), opt.substring(eq + 1).trim());
      }
    }

    String keyring = conf.get(CephConfigKeys.CEPH_AUTH_KEYRING_KEY,
        CephConfigKeys.CEPH_AUTH_KEYRING_DEFAULT);
    if (keyring != null) {
      m.conf_set("keyring", keyring);
    }

    // URI authority（host[:port]）存在时覆盖 mon 地址（docs/ARCHITECTURE.md）
    if (uri != null && uri.getHost() != null && !uri.getHost().isEmpty()) {
      String monHost = (uri.getPort() != -1)
          ? uri.getHost() + ":" + uri.getPort()
          : uri.getHost();
      m.conf_set("mon_host", monHost);
    }

    // 预读窗口与流缓冲对齐
    int bufferSize = conf.getInt(CephConfigKeys.CEPH_CLIENT_BUFFER_SIZE_KEY,
        CephConfigKeys.CEPH_CLIENT_BUFFER_SIZE_DEFAULT);
    m.conf_set("client_readahead_max_bytes", String.valueOf(bufferSize));

    // 4. mount：ceph.root.dir 作为文件系统根
    String root = conf.get(CephConfigKeys.CEPH_ROOT_DIR_KEY,
        CephConfigKeys.CEPH_ROOT_DIR_DEFAULT);
    m.mount(root);

    // 5. mount 后属性：本地副本优先读
    m.localize_reads(conf.getBoolean(CephConfigKeys.CEPH_LOCALIZE_READS_KEY,
        CephConfigKeys.CEPH_LOCALIZE_READS_DEFAULT));

    this.mount = m;
    LOG.debug("CephFS mounted: root={}, authId={}, conf={}", root, authId, confFile);
  }

  @Override
  void shutdown() throws IOException {
    CephMount m = mount;
    if (m == null) {
      return; // 幂等：未挂载/已关闭时 no-op
    }
    mount = null;
    m.unmount();
    // ceph_release 为 CephMount 私有 native，由其 finalize() 在 GC 时调用；
    // 此处丢弃引用即完成移交。
    LOG.debug("CephFS unmounted");
  }

  /**
   * Hadoop {@link Path} → CephFS 绝对路径字符串的<b>唯一</b>转换入口。
   *
   * <p>规则：剥离 scheme/authority；空路径与 null 视为根 "/"；
   * 相对路径解析到根（连接器不使用 ceph_chdir，libcephfs 侧 cwd 恒为根，
   * workingDir 语义由上层 CephFileSystem 纯 Java 维护（docs/ARCHITECTURE.md）。
   * {@code Path} 本身已保证规范化（无重复/尾部斜杠）；URI 编码字符
   * （如空格）由 {@code URI#getPath()} 解码还原。
   *
   * <p>包可见仅为单元测试；生产代码一律经由本类的 proto 方法间接使用。
   */
  static String pathString(Path path) {
    if (path == null) {
      return "/";
    }
    String p = path.toUri().getPath();
    if (p == null || p.isEmpty()) {
      return "/";
    }
    if (p.charAt(0) != '/') {
      p = "/" + p;
    }
    return p;
  }

  /** 取当前 mount，未挂载时抛 CephNotMountedException。 */
  private CephMount mnt() throws IOException {
    CephMount m = mount;
    if (m == null) {
      throw new CephNotMountedException("This client is not mounted");
    }
    return m;
  }

  // ── 元数据 ────────────────────────────────────────────────────────────

  @Override
  CephStatVFS statfs(Path path) throws IOException {
    CephStatVFS stat = new CephStatVFS();
    mnt().statfs(pathString(path), stat);
    return stat;
  }

  @Override
  void lstat(Path path, CephStat stat) throws IOException {
    mnt().lstat(pathString(path), stat);
  }

  @Override
  String[] listdir(Path path) throws IOException {
    CephMount m = mnt();
    try {
      return m.listdir(pathString(path));
    } catch (IOException e) {
      // 契约（docs/ARCHITECTURE.md）：存在但非目录 → null。
      // 注：JNI 对 ENOTDIR 实际抛 CephNotDirectoryException（native 方法可
      // 绕过 listdir 的 throws 声明；JNI 源码注释"返回 NULL"与行为不符，
      // 以行为为准），此处适配回 null 契约。
      if (e instanceof CephNotDirectoryException) {
        return null;
      }
      throw e;
    }
  }

  @Override
  void mkdirs(Path path, int mode) throws IOException {
    mnt().mkdirs(pathString(path), mode);
  }

  @Override
  void rmdir(Path path) throws IOException {
    mnt().rmdir(pathString(path));
  }

  @Override
  void unlink(Path path) throws IOException {
    mnt().unlink(pathString(path));
  }

  @Override
  void link(Path existing, Path newName) throws IOException {
    mnt().link(pathString(existing), pathString(newName));
  }

  @Override
  void rename(Path src, Path dst) throws IOException {
    mnt().rename(pathString(src), pathString(dst));
  }

  @Override
  void setattr(Path path, CephStat stat, int mask) throws IOException {
    mnt().setattr(pathString(path), stat, mask);
  }

  @Override
  void chmod(Path path, int mode) throws IOException {
    mnt().chmod(pathString(path), mode);
  }

  // ── 数据 ──────────────────────────────────────────────────────────────

  @Override
  int open(Path path, int flags, int mode) throws IOException {
    int fd = mnt().open(pathString(path), flags, mode);
    openFileDescriptors.incrementAndGet();
    return fd;
  }

  @Override
  int open(Path path, int flags, int mode, int stripeUnit, int stripeCount,
      int objectSize, String dataPool) throws IOException {
    int fd = mnt().open(pathString(path), flags, mode,
        stripeUnit, stripeCount, objectSize, dataPool);
    openFileDescriptors.incrementAndGet();
    return fd;
  }

  @Override
  long lseek(int fd, long offset, int whence) throws IOException {
    return mnt().lseek(fd, offset, whence);
  }

  @Override
  long read(int fd, byte[] buf, long size, long offset) throws IOException {
    return mnt().read(fd, buf, size, offset);
  }

  @Override
  long write(int fd, byte[] buf, long size, long offset) throws IOException {
    return mnt().write(fd, buf, size, offset);
  }

  @Override
  void fstat(int fd, CephStat stat) throws IOException {
    mnt().fstat(fd, stat);
  }

  @Override
  void close(int fd) throws IOException {
    mnt().close(fd);
    openFileDescriptors.decrementAndGet();
  }

  @Override
  void flush(int fd) throws IOException {
    mnt().fsync(fd, false);
  }

  @Override
  void ftruncate(int fd, long size) throws IOException {
    mnt().ftruncate(fd, size);
  }

  // ── 数据局部性 ────────────────────────────────────────────────────────

  @Override
  CephFileExtent getFileExtent(int fd, long offset) throws IOException {
    return mnt().get_file_extent(fd, offset);
  }

  @Override
  InetAddress getOsdAddress(int osd) throws IOException {
    return mnt().get_osd_address(osd);
  }

  int openFileDescriptorCount() {
    return openFileDescriptors.get();
  }
}
