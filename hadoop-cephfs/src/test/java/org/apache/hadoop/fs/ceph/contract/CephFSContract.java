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
package org.apache.hadoop.fs.ceph.contract;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ceph.CephConfigKeys;
import org.apache.hadoop.fs.ceph.CephTestConfig;
import org.apache.hadoop.fs.contract.AbstractBondedFSContract;

/**
 * CephFS（{@code ceph://}）的 Hadoop FileSystem 契约绑定（T06）。
 *
 * <p>能力声明见 {@code src/test/resources/contract/ceph.xml}。
 *
 * <p><b>门控</b>（见 AGENTS.md）：环境变量 {@code CEPH_CONTRACT_TEST=1} 时才把
 * {@code fs.contract.test.fs.ceph} 注入配置；未设置时
 * {@link AbstractBondedFSContract} 视为契约未启用，全部用例 skip，
 * 保证无集群环境 {@code mvn verify} 绿色。
 *
 * <p>集群信息以 docs/environments/E1.md 为准：mon 地址一律经 {@code ceph.conf.file} 读取
 * （可用 -Dceph.conf.file 或环境变量 CEPH_CONF_FILE 覆盖），auth id 用 admin。
 *
 * <p>运行方式（集群启动后）：
 * <pre>
 *   CEPH_CONTRACT_TEST=1 mvn verify -Dit.test='ITestCephContract*'
 * </pre>
 */
public class CephFSContract extends AbstractBondedFSContract {

  public static final String CONTRACT_XML = "contract/ceph.xml";

  private static final String GATE_ENV = "CEPH_CONTRACT_TEST";
  private static final String TEST_FS_KEY = "fs.contract.test.fs.ceph";
  private static final String TEST_FS_URI = "ceph:///";
  private static final String DEFAULT_CEPH_CONF = "/home/lsh/code/ceph/build/ceph.conf";
  public CephFSContract(Configuration conf) {
    super(conf);
    addConfResource(CONTRACT_XML);
    if (isGateOpen()) {
      conf.set(TEST_FS_KEY, TEST_FS_URI);
      conf.set(CephConfigKeys.CEPH_CONF_FILE_KEY, cephConfFile());
      CephTestConfig.applyAuth(conf);
    }
    // 门控关闭：不设置 fs.contract.test.fs.ceph → AbstractBondedFSContract
    // 在 init() 中置 disabled，AbstractFSContractTestBase.setup() 会 skip 全部用例
  }

  private static boolean isGateOpen() {
    return "1".equals(System.getenv(GATE_ENV));
  }

  private static String cephConfFile() {
    String fromProp = System.getProperty("ceph.conf.file");
    if (fromProp != null) {
      return fromProp;
    }
    String fromEnv = System.getenv("CEPH_CONF_FILE");
    return fromEnv != null ? fromEnv : DEFAULT_CEPH_CONF;
  }

  @Override
  public String getScheme() {
    return "ceph";
  }
}
