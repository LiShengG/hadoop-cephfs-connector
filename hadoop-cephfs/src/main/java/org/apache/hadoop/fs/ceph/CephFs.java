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
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.DelegateToFileSystem;

/**
 * CephFS 的 {@link org.apache.hadoop.fs.AbstractFileSystem} 实现
 * （FileContext 路径，YARN NodeManager 等使用 —— 架构文档 §2）。
 *
 * <p>按 Hadoop 内置 {@code RawLocalFs} / {@code FtpFs} 模式经
 * {@link DelegateToFileSystem} 委托给 {@link CephFileSystem}，零重复逻辑。
 *
 * <p>启用方式（core-site.xml）：
 * <pre>
 *   fs.AbstractFileSystem.ceph.impl = org.apache.hadoop.fs.ceph.CephFs
 * </pre>
 * 之后 {@code FileContext.getFileContext(URI.create("ceph:///"), conf)} 可用。
 */
public class CephFs extends DelegateToFileSystem {

  /** Ceph monitor 默认端口（ceph://host 缺省端口按此解析）。 */
  static final int CEPH_DEFAULT_PORT = 6789;

  /**
   * 本构造器签名为
   * {@link org.apache.hadoop.fs.AbstractFileSystem#createFileSystem(URI, Configuration)}
   * 反射所需（{@code (URI, Configuration)}）。
   *
   * @param theUri ceph:// URI（authority 可为空，mon 地址从 ceph.conf 读取）
   * @param conf   Hadoop 配置
   * @throws IOException mount 失败等
   * @throws URISyntaxException URI 非法
   */
  public CephFs(final URI theUri, final Configuration conf)
      throws IOException, URISyntaxException {
    // authorityRequired=false：支持 ceph:///（无 authority，完全依赖 ceph.conf）
    super(theUri, new CephFileSystem(), conf, CephConfigKeys.CEPH_URI_SCHEME, false);
  }

  @Override
  public int getUriDefaultPort() {
    return CEPH_DEFAULT_PORT;
  }
}
