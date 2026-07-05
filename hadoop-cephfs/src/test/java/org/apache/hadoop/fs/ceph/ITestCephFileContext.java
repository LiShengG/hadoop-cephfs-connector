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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FsStatus;
import org.apache.hadoop.fs.Options;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.UnsupportedFileSystemException;
import org.apache.hadoop.fs.permission.FsPermission;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * FileContext（AbstractFileSystem/CephFs）全链路集成测试（T05）。
 *
 * <p>门控：环境变量 {@code CEPH_CONTRACT_TEST=1}；集群信息以 docs/ENV.md 为准。
 *
 * <p>运行方式：
 * <pre>
 *   CEPH_CONTRACT_TEST=1 mvn verify -Dit.test=ITestCephFileContext
 * </pre>
 */
public class ITestCephFileContext {

  private static final String DEFAULT_CEPH_CONF = "/home/lsh/code/ceph/build/ceph.conf";
  private static final String DEFAULT_AUTH_ID = "admin";

  private Configuration conf;
  private FileContext fc;
  private Path base;

  private static String cephConfFile() {
    String fromProp = System.getProperty("ceph.conf.file");
    if (fromProp != null) {
      return fromProp;
    }
    String fromEnv = System.getenv("CEPH_CONF_FILE");
    return fromEnv != null ? fromEnv : DEFAULT_CEPH_CONF;
  }

  private Configuration newConf(boolean withAbstractFsImpl) {
    Configuration c = new Configuration();
    c.set(CephConfigKeys.CEPH_CONF_FILE_KEY, cephConfFile());
    c.set(CephConfigKeys.CEPH_AUTH_ID_KEY, DEFAULT_AUTH_ID);
    if (withAbstractFsImpl) {
      // T05 验收标准 3：fs.AbstractFileSystem.ceph.impl 配置生效
      c.set(CephConfigKeys.FS_ABSTRACTFILESYSTEM_CEPH_IMPL_KEY,
          CephFs.class.getName());
    }
    return c;
  }

  @Before
  public void setUp() throws Exception {
    Assume.assumeTrue("CEPH_CONTRACT_TEST != 1, skipping integration test",
        "1".equals(System.getenv("CEPH_CONTRACT_TEST")));

    conf = newConf(true);
    fc = FileContext.getFileContext(URI.create("ceph:///"), conf);
    base = new Path("/itest-t05-fc-" + System.nanoTime());
    fc.mkdir(base, new FsPermission((short) 0755), true);
  }

  @After
  public void tearDown() throws Exception {
    if (fc != null) {
      try {
        fc.delete(base, true);
      } catch (IOException ignored) {
        // 兜底清理
      }
      fc = null;
    }
  }

  @Test
  public void testAbstractFileSystemImplKeyIsRequired() throws Exception {
    // 未配置 fs.AbstractFileSystem.ceph.impl 时必须失败 → 证明该键即生效点
    try {
      FileContext.getFileContext(URI.create("ceph:///"), newConf(false));
      fail("expected UnsupportedFileSystemException without "
          + CephConfigKeys.FS_ABSTRACTFILESYSTEM_CEPH_IMPL_KEY);
    } catch (UnsupportedFileSystemException expected) {
      // AbstractFileSystem.createFileSystem: "No AbstractFileSystem configured"
    }
  }

  @Test
  public void testFileContextFullRoundTrip() throws Exception {
    byte[] payload = "hello via FileContext".getBytes(StandardCharsets.UTF_8);
    Path file = new Path(base, "fc.txt");
    Path renamed = new Path(base, "fc-renamed.txt");
    Path subDir = new Path(base, "subdir");

    // mkdir
    fc.mkdir(subDir, new FsPermission((short) 0755), false);
    assertTrue(fc.getFileStatus(subDir).isDirectory());

    // create + write
    try (FSDataOutputStream out = fc.create(file,
        EnumSet.of(CreateFlag.CREATE),
        Options.CreateOpts.perms(FsPermission.getFileDefault()))) {
      out.write(payload);
    }

    // getFileStatus + open + read
    FileStatus status = fc.getFileStatus(file);
    assertTrue(status.isFile());
    assertEquals(payload.length, status.getLen());
    byte[] actual = new byte[payload.length];
    try (FSDataInputStream in = fc.open(file)) {
      in.readFully(0, actual);
    }
    assertArrayEquals(payload, actual);

    // rename
    fc.rename(file, renamed);
    assertFalse(fc.util().exists(file));
    assertTrue(fc.util().exists(renamed));
    byte[] afterRename = new byte[payload.length];
    try (FSDataInputStream in = fc.open(renamed)) {
      in.readFully(0, afterRename);
    }
    assertArrayEquals(payload, afterRename);

    // delete
    assertTrue(fc.delete(renamed, false));
    assertFalse(fc.util().exists(renamed));
    assertTrue(fc.delete(subDir, false));

    // listStatus 确认 base 已空
    assertEquals(0, fc.util().listStatus(base).length);
  }

  @Test
  public void testFsStatusThroughFileContext() throws Exception {
    // getStatus 经 DelegateToFileSystem.getFsStatus 委托到 CephFileSystem.getStatus
    FsStatus status = fc.getFsStatus(base);
    assertTrue("capacity > 0", status.getCapacity() > 0);
    assertTrue("remaining <= capacity",
        status.getRemaining() <= status.getCapacity());
  }

  @Test
  public void testDefaultFileSystemIsCephFs() throws Exception {
    // 相对路径按 FileContext 默认 FS（ceph:///）解析；能解析出 ceph scheme
    // 的绝对路径即证明默认 FS 为 CephFs 委托的 CephFileSystem
    Path qualified = fc.makeQualified(new Path("relative-t05"));
    assertEquals("ceph", qualified.toUri().getScheme());
  }
}
