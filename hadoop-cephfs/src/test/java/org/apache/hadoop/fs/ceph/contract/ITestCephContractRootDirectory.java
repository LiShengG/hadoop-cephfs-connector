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
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.contract.AbstractContractRootDirectoryTest;
import org.apache.hadoop.fs.contract.AbstractFSContract;
import org.apache.hadoop.fs.contract.ContractTestUtils;
import org.junit.Test;

/**
 * CephFS 的 RootDirectory 契约测试（T06）。门控与集群约定见 {@link CephFSContract}。
 *
 * <p>注意：本套件会清空 CephFS 根目录下的全部内容（仅限可丢弃数据的
 * vstart 测试集群，ceph.xml 中 root-tests-enabled=true 的前提）。
 */
public class ITestCephContractRootDirectory extends AbstractContractRootDirectoryTest {

  @Override
  protected AbstractFSContract createContract(Configuration conf) {
    return new CephFSContract(conf);
  }

  /**
   * 排除项（当前契约语义见 docs/ARCHITECTURE.md，历史验收见 docs/archive/2026-07.md）：
   *
   * <p>契约基类期望非空根目录 {@code delete("/", false)} 抛 IOException。
   * 本实现按 T03 已验收的根目录保护条款，对 {@code !recursive} 的根目录删除
   * 一律直接返回 false（不触碰任何数据，也不抛异常）——filesystem.md 允许
   * 对根目录做特殊处理（"The root directory may have special handling"），
   * 且该口径已被 mock 单测与独立验收固化，按 AGENTS.md 优先保持既有行为、
   * 不为契约测试改动已验收语义。
   *
   * <p>此 override 改为验证本实现的既定语义：返回 false、根仍在、数据不丢。
   */
  @Override
  @Test
  public void testRmNonEmptyRootDirNonRecursive() throws Throwable {
    skipIfUnsupported(TEST_ROOT_TESTS_ENABLED);
    final FileSystem fs = getFileSystem();
    final Path root = new Path("/");
    final Path file = new Path("/testRmNonEmptyRootDirNonRecursive");
    ContractTestUtils.touch(fs, file);
    assertIsDirectory(root);
    try {
      boolean deleted = fs.delete(root, false);
      assertFalse("delete(/, false) must return false under root protection",
          deleted);
      // 文件必须原样保留
      assertIsFile(file);
    } finally {
      fs.delete(file, false);
    }
    assertIsDirectory(root);
  }
}
