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

import com.ceph.fs.CephFileExtent;
import com.ceph.fs.CephStat;
import com.ceph.fs.CephStatVFS;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;

/**
 * 连接器需要的全部 CephFS 原语的抽象层（见 docs/ARCHITECTURE.md）。
 *
 * <p>{@code CephFileSystem} 及流类只通过本抽象类访问 CephFS；生产实现为
 * {@link CephTalker}（封装 {@code com.ceph.fs.CephMount}），单元测试可用
 * Mockito mock 替换，无需真实集群。
 *
 * <p><b>签名权威来源</b>：本类与实际 Ceph Java 绑定源码。
 *
 * <p><b>通用异常约定</b>：底层 JNI 将 errno 映射为 Java 异常
 * （见 {@code libcephfs_jni.cc} 的 {@code handle_error}）：
 * <ul>
 *   <li>ENOENT → {@link java.io.FileNotFoundException}</li>
 *   <li>EEXIST → {@code com.ceph.fs.CephFileAlreadyExistsException}（IOException 子类）</li>
 *   <li>ENOTDIR → {@code com.ceph.fs.CephNotDirectoryException}（IOException 子类）</li>
 *   <li>其余 errno → {@link IOException}（消息为 strerror 文本）</li>
 * </ul>
 * 未 {@link #initialize} 或已 {@link #shutdown} 后调用任何原语，抛
 * {@code com.ceph.fs.CephNotMountedException}（IOException 子类）。
 *
 * <p><b>路径约定</b>：所有 {@link Path} 参数由实现类转换为 CephFS 绝对路径
 * （剥离 scheme/authority），调用方无须预处理。
 *
 * <p><b>时间戳约定</b>：{@link CephStat} 的 {@code a_time}/{@code m_time}
 * 单位为毫秒（JNI 层已完成纳秒→毫秒换算）。
 */
abstract class CephFsProto {

  // ── 生命周期 ──────────────────────────────────────────────────────────

  /**
   * 建立与 Ceph 集群的连接（mount）。
   *
   * @param uri  文件系统 URI；authority（host:port）若存在则覆盖 ceph.conf 的
   *             mon 地址（下发为 {@code mon_host}），可为 null
   * @param conf Hadoop 配置，识别 {@link CephConfigKeys} 中的全部键
   * @throws IOException 配置文件不存在、认证失败、mon 不可达等
   * @throws IllegalStateException 已 initialize 过（本抽象层实例不可复用）
   */
  abstract void initialize(URI uri, Configuration conf) throws IOException;

  /**
   * 断开连接（unmount 并释放原生资源）。<b>幂等</b>：重复调用为 no-op。
   *
   * @throws IOException unmount 失败
   */
  abstract void shutdown() throws IOException;

  // ── 元数据 ────────────────────────────────────────────────────────────

  /**
   * 获取文件系统统计信息（容量/剩余等）。
   *
   * @param path 文件系统内任一存在的路径
   * @return 填充完毕的 {@link CephStatVFS}
   * @throws java.io.FileNotFoundException 路径不存在
   * @throws IOException 其他错误
   */
  abstract CephStatVFS statfs(Path path) throws IOException;

  /**
   * 获取文件状态（不跟随符号链接）。
   *
   * @param path 目标路径
   * @param stat 出参，调用成功后填充
   * @throws java.io.FileNotFoundException 路径不存在
   * @throws com.ceph.fs.CephNotDirectoryException 路径的某个父级不是目录
   * @throws IOException 其他错误
   */
  abstract void lstat(Path path, CephStat stat) throws IOException;

  /**
   * 列出目录内容（不含 "." 与 ".."）。
   *
   * @param path 目录路径
   * @return 子项名字数组（可能为空数组）；<b>path 存在但不是目录时返回 null</b>
   * @throws java.io.FileNotFoundException 路径不存在
   * @throws IOException 其他错误
   */
  abstract String[] listdir(Path path) throws IOException;

  /**
   * 递归创建目录（等价 mkdir -p；libcephfs 原生支持）。
   *
   * <p><b>已存在语义（实测钉死，见 ITestCephTalker）</b>：{@code Client::mkdirs}
   * 只要路径各级均可解析即返回成功 —— 已存在目录为 no-op；<b>末段为已存在文件
   * 时同样静默成功（不创建、不报错）</b>。Hadoop mkdirs 对"已存在同名文件应
   * 失败"的检查必须由上层（T03 CephFileSystem）自行 lstat 完成。
   *
   * @param path 目录路径
   * @param mode POSIX 权限位（如 0755）
   * @throws com.ceph.fs.CephNotDirectoryException 中间某级已存在且不是目录
   * @throws IOException 其他错误
   */
  abstract void mkdirs(Path path, int mode) throws IOException;

  /**
   * 删除<b>空</b>目录。
   *
   * @param path 目录路径
   * @throws java.io.FileNotFoundException 路径不存在
   * @throws IOException 目录非空（ENOTEMPTY，消息为 strerror 文本）等其他错误
   */
  abstract void rmdir(Path path) throws IOException;

  /**
   * 删除文件（或符号链接）。
   *
   * @param path 文件路径
   * @throws java.io.FileNotFoundException 路径不存在
   * @throws IOException 路径是目录（EISDIR）等其他错误
   */
  abstract void unlink(Path path) throws IOException;

  /**
   * 为普通文件创建硬链接。目标已存在时由 CephFS MDS 原子返回 EEXIST。
   *
   * @param existing 已存在的普通文件
   * @param newName   新目录项
   * @throws com.ceph.fs.CephFileAlreadyExistsException newName 已存在
   * @throws IOException 其他错误（目录硬链接返回 EPERM）
   */
  abstract void link(Path existing, Path newName) throws IOException;

  /**
   * 重命名/移动。<b>POSIX 语义</b>：dst 为已存在文件时静默覆盖；
   * Hadoop rename 语义修正（返回 false / 移入目录）由 T03 在上层处理。
   *
   * @param src 源路径
   * @param dst 目标路径
   * @throws java.io.FileNotFoundException src 不存在
   * @throws IOException 其他错误
   */
  abstract void rename(Path src, Path dst) throws IOException;

  /**
   * 设置文件属性（uid/gid/mode/atime/mtime）。
   *
   * @param path 目标路径
   * @param stat 属性载体；仅 mask 指定的字段被使用（时间单位毫秒）
   * @param mask {@code CephMount.SETATTR_*} 的按位或
   * @throws java.io.FileNotFoundException 路径不存在
   * @throws IOException 其他错误
   */
  abstract void setattr(Path path, CephStat stat, int mask) throws IOException;

  /**
   * 修改权限位。
   *
   * @param path 目标路径
   * @param mode 新 POSIX 权限位
   * @throws java.io.FileNotFoundException 路径不存在
   * @throws IOException 其他错误
   */
  abstract void chmod(Path path, int mode) throws IOException;

  // ── 数据 ──────────────────────────────────────────────────────────────

  /**
   * 打开（或创建）文件。
   *
   * @param path  文件路径
   * @param flags {@code CephMount.O_*} 的按位或
   * @param mode  创建时的 POSIX 权限位（不创建时传 0）
   * @return 非负文件描述符
   * @throws java.io.FileNotFoundException 路径不存在且未带 O_CREAT
   * @throws com.ceph.fs.CephFileAlreadyExistsException O_CREAT|O_EXCL 且已存在
   * @throws IOException 其他错误
   */
  abstract int open(Path path, int flags, int mode) throws IOException;

  /**
   * 以指定 file layout 打开（创建）文件；layout 仅对新建文件生效。
   *
   * @param path        文件路径
   * @param flags       {@code CephMount.O_*} 的按位或
   * @param mode        创建时的 POSIX 权限位
   * @param stripeUnit  stripe unit 字节数（0 表示用默认值）
   * @param stripeCount stripe count（0 表示用默认值）
   * @param objectSize  object 大小字节数（0 表示用默认值）
   * @param dataPool    目标 data pool 名（null 表示默认 pool）
   * @return 非负文件描述符
   * @throws java.io.FileNotFoundException 路径不存在且未带 O_CREAT
   * @throws com.ceph.fs.CephFileAlreadyExistsException O_CREAT|O_EXCL 且已存在
   * @throws IOException layout/pool 非法等其他错误
   */
  abstract int open(Path path, int flags, int mode, int stripeUnit,
                    int stripeCount, int objectSize, String dataPool) throws IOException;

  /**
   * 移动文件读写位置。
   *
   * @param fd     文件描述符
   * @param offset 位移量
   * @param whence {@code CephMount.SEEK_SET / SEEK_CUR / SEEK_END}
   * @return 新的绝对偏移
   * @throws IOException fd 无效或 offset 非法
   */
  abstract long lseek(int fd, long offset, int whence) throws IOException;

  /**
   * 读文件。
   *
   * <p>返回类型与 {@code CephMount.read} 一致，为 <b>long</b>。
   *
   * @param fd     文件描述符
   * @param buf    目标缓冲区
   * @param size   期望读取的字节数（不大于 buf.length）
   * @param offset 读取起始偏移；-1 表示当前位置
   * @return 实际读取字节数；0 表示 EOF
   * @throws IOException fd 无效等错误
   */
  abstract long read(int fd, byte[] buf, long size, long offset) throws IOException;

  /**
   * 写文件。
   *
   * <p>返回类型与 {@code CephMount.write} 一致，为 <b>long</b>。
   *
   * @param fd     文件描述符
   * @param buf    数据缓冲区
   * @param size   期望写入的字节数（不大于 buf.length）
   * @param offset 写入起始偏移；-1 表示当前位置
   * @return 实际写入字节数
   * @throws IOException fd 无效、配额/空间不足等错误
   */
  abstract long write(int fd, byte[] buf, long size, long offset) throws IOException;

  /**
   * 获取打开文件的状态。
   *
   * @param fd   文件描述符
   * @param stat 出参，调用成功后填充
   * @throws IOException fd 无效等错误
   */
  abstract void fstat(int fd, CephStat stat) throws IOException;

  /**
   * 关闭文件描述符。
   *
   * @param fd 文件描述符
   * @throws IOException fd 无效等错误
   */
  abstract void close(int fd) throws IOException;

  /**
   * 将文件数据与元数据刷到集群（ceph fsync，dataonly=false）。
   *
   * @param fd 文件描述符
   * @throws IOException fd 无效或刷写失败
   */
  abstract void flush(int fd) throws IOException;

  /**
   * 截断打开的文件到指定长度。
   *
   * @param fd   文件描述符
   * @param size 新文件长度
   * @throws IOException fd 无效等错误
   */
  abstract void ftruncate(int fd, long size) throws IOException;

  // ── 数据局部性 ────────────────────────────────────────────────────────

  /**
   * 获取包含给定偏移的 file extent（起止与所在 OSD 列表）。
   *
   * @param fd     文件描述符
   * @param offset 文件内偏移
   * @return extent 信息（offset/length/OSD id 数组）
   * @throws IOException fd 无效等错误
   */
  abstract CephFileExtent getFileExtent(int fd, long offset) throws IOException;

  /**
   * 获取 OSD 的网络地址。
   *
   * @param osd OSD 设备 id
   * @return OSD 地址
   * @throws IOException osd id 无效等错误
   */
  abstract InetAddress getOsdAddress(int osd) throws IOException;
}
