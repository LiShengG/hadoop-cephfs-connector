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

/**
 * Hadoop CephFS 连接器全部配置键与默认值的权威来源。
 *
 * <p>命名约定：{@code *_KEY} 为配置键名，{@code *_DEFAULT} 为对应默认值；
 * 默认值为 {@code null} 表示"未配置则不生效/不下发"。
 */
public final class CephConfigKeys {

  private CephConfigKeys() {
    // 常量类，禁止实例化
  }

  /** URI scheme：{@code ceph://}。 */
  public static final String CEPH_URI_SCHEME = "ceph";

  /**
   * 用户 core-site.xml 中把 ceph scheme 绑定到 FileSystem 实现类的键。
   * 值应为 {@code org.apache.hadoop.fs.ceph.CephFileSystem}（T03 交付）。无默认值。
   */
  public static final String FS_CEPH_IMPL_KEY = "fs.ceph.impl";

  /**
   * 把 ceph scheme 绑定到 AbstractFileSystem 实现类的键。
   * 值应为 {@code org.apache.hadoop.fs.ceph.CephFs}（T05 交付）。无默认值。
   */
  public static final String FS_ABSTRACTFILESYSTEM_CEPH_IMPL_KEY =
      "fs.AbstractFileSystem.ceph.impl";

  /** ceph.conf 配置文件路径；vstart 环境为 {@code /home/lsh/code/ceph/build/ceph.conf}。 */
  public static final String CEPH_CONF_FILE_KEY = "ceph.conf.file";
  public static final String CEPH_CONF_FILE_DEFAULT = null;

  /** 分号分隔的额外 ceph 配置项，形如 {@code client_mount_timeout=30;debug_client=0}。 */
  public static final String CEPH_CONF_OPTIONS_KEY = "ceph.conf.options";
  public static final String CEPH_CONF_OPTIONS_DEFAULT = null;

  /** cephx 用户 id（如 {@code admin}，即 client.admin）。null 时用 libcephfs 默认 id。 */
  public static final String CEPH_AUTH_ID_KEY = "ceph.auth.id";
  public static final String CEPH_AUTH_ID_DEFAULT = null;

  /** keyring 文件路径；null 时依赖 ceph.conf 内的 keyring 配置。 */
  public static final String CEPH_AUTH_KEYRING_KEY = "ceph.auth.keyring";
  public static final String CEPH_AUTH_KEYRING_DEFAULT = null;

  /** 挂载后视为文件系统根的 CephFS 子目录。 */
  public static final String CEPH_ROOT_DIR_KEY = "ceph.root.dir";
  public static final String CEPH_ROOT_DIR_DEFAULT = "/";

  /** 新文件默认 object size（字节），兼作 Hadoop blockSize 报告值。默认 64MB。 */
  public static final String CEPH_OBJECT_SIZE_KEY = "ceph.object.size";
  public static final long CEPH_OBJECT_SIZE_DEFAULT = 64L * 1024 * 1024;

  /** 新文件写入的 data pool 名单（逗号分隔，取第一个匹配）。null 时用文件系统默认 pool。 */
  public static final String CEPH_DATA_POOLS_KEY = "ceph.data.pools";
  public static final String CEPH_DATA_POOLS_DEFAULT = null;

  /**
   * 上报的副本数（T03 增补，登记于 PROGRESS.md）。CephFS 的真实冗余由 pool
   * 决定（见 docs/ARCHITECTURE.md），本值仅用于 {@code getDefaultReplication} 与
   * {@code FileStatus.getReplication} 的报告，不影响实际存储。
   */
  public static final String CEPH_REPLICATION_KEY = "ceph.replication";
  public static final int CEPH_REPLICATION_DEFAULT = 3;

  /** 读时是否优先本地 OSD 副本。 */
  public static final String CEPH_LOCALIZE_READS_KEY = "ceph.localize.reads";
  public static final boolean CEPH_LOCALIZE_READS_DEFAULT = true;

  /** 流的读/写缓冲大小（字节），默认 4MB。 */
  public static final String CEPH_CLIENT_BUFFER_SIZE_KEY = "ceph.client.buffer.size";
  public static final int CEPH_CLIENT_BUFFER_SIZE_DEFAULT = 4 * 1024 * 1024;
}
