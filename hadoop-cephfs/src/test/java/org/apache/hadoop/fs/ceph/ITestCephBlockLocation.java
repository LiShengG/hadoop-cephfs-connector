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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FsStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * getFileBlockLocations / getStatus 对真实 vstart 集群的集成测试（T05）。
 *
 * <p>门控：环境变量 {@code CEPH_CONTRACT_TEST=1}；集群信息以 docs/environments/E1.md 为准。
 *
 * <p>运行方式：
 * <pre>
 *   CEPH_CONTRACT_TEST=1 mvn verify -Dit.test=ITestCephBlockLocation
 * </pre>
 *
 * <p>验收标准 2 的 host ↔ {@code ceph osd dump} 地址比对：本测试打印每个
 * BlockLocation 的 hosts（[T05-blockloc] 前缀），与
 * {@code /home/lsh/code/ceph/build/bin/ceph osd dump | grep '^osd\.'} 人工比对。
 */
public class ITestCephBlockLocation {

  private static final String DEFAULT_CEPH_CONF = "/home/lsh/code/ceph/build/ceph.conf";
  /** 测试布局 object size：4MB（3×objectSize 文件仅 12MB，秒级写入）。 */
  private static final long OBJECT_SIZE = 4L * 1024 * 1024;
  private static final int FILE_SIZE = (int) (3 * OBJECT_SIZE);

  private Configuration conf;
  private CephFileSystem fs;
  private Path base;

  private static String cephConfFile() {
    String fromProp = System.getProperty("ceph.conf.file");
    if (fromProp != null) {
      return fromProp;
    }
    String fromEnv = System.getenv("CEPH_CONF_FILE");
    return fromEnv != null ? fromEnv : DEFAULT_CEPH_CONF;
  }

  @Before
  public void setUp() throws Exception {
    Assume.assumeTrue("CEPH_CONTRACT_TEST != 1, skipping integration test",
        "1".equals(System.getenv("CEPH_CONTRACT_TEST")));

    conf = new Configuration();
    conf.set(CephConfigKeys.CEPH_CONF_FILE_KEY, cephConfFile());
    CephTestConfig.applyAuth(conf);
    conf.setBoolean("fs.ceph.impl.disable.cache", true);

    FileSystem created = FileSystem.get(URI.create("ceph:///"), conf);
    assertTrue("expected CephFileSystem, got " + created.getClass(),
        created instanceof CephFileSystem);
    fs = (CephFileSystem) created;
    base = new Path("/itest-t05-blockloc-" + System.nanoTime());
    assertTrue(fs.mkdirs(base, new FsPermission((short) 0755)));
  }

  @After
  public void tearDown() throws Exception {
    if (fs != null) {
      try {
        fs.delete(base, true);
      } catch (IOException ignored) {
        // 兜底清理
      }
      fs.close();
      fs = null;
    }
  }

  private Path writeFile(String name, int size) throws IOException {
    Path file = new Path(base, name);
    byte[] data = new byte[1024 * 1024];
    Random random = new Random(20260705L);
    try (FSDataOutputStream out = fs.create(file, FsPermission.getFileDefault(),
        false, 1024 * 1024, (short) 1, OBJECT_SIZE, null)) {
      int written = 0;
      while (written < size) {
        random.nextBytes(data);
        int n = Math.min(data.length, size - written);
        out.write(data, 0, n);
        written += n;
      }
    }
    assertNoOpenFds();
    return file;
  }

  private void assertNoOpenFds() {
    assertEquals("open fd count", 0, fs.openFileDescriptorCountForTests());
  }

  @Test
  public void testBlockLocationsOfThreeObjectFile() throws Exception {
    Path file = writeFile("three-objects.bin", FILE_SIZE);
    FileStatus status = fs.getFileStatus(file);
    assertEquals(FILE_SIZE, status.getLen());

    BlockLocation[] blocks = fs.getFileBlockLocations(status, 0, status.getLen());
    assertNoOpenFds(); // 临时只读 fd 无泄漏

    // 3×objectSize 文件、objectSize=blockSize → 恰好 3 个块
    assertEquals("block count", 3, blocks.length);
    for (int i = 0; i < 3; i++) {
      assertEquals("offset of block " + i, i * OBJECT_SIZE, blocks[i].getOffset());
      assertEquals("length of block " + i, OBJECT_SIZE, blocks[i].getLength());
      assertTrue("hosts of block " + i + " must be non-empty",
          blocks[i].getHosts().length > 0);
      for (String host : blocks[i].getHosts()) {
        assertFalse("host must be non-blank", host.trim().isEmpty());
        assertFalse("host must not be degraded localhost in a healthy cluster",
            "localhost".equals(host));
      }
      // 供人工与 `ceph osd dump` 比对（验收标准 2）
      System.out.println("[T05-blockloc] block " + i
          + " offset=" + blocks[i].getOffset()
          + " length=" + blocks[i].getLength()
          + " hosts=" + java.util.Arrays.toString(blocks[i].getHosts()));
    }
  }

  @Test
  public void testUnalignedRangeAndTail() throws Exception {
    Path file = writeFile("unaligned.bin", FILE_SIZE);
    FileStatus status = fs.getFileStatus(file);

    // start 落在第二个 object 中间，len 越过文件尾 → 首块缩短、末端钳制
    long start = OBJECT_SIZE + 12345;
    BlockLocation[] blocks =
        fs.getFileBlockLocations(status, start, FILE_SIZE); // len 超文件尾
    assertNoOpenFds();

    assertEquals("block count", 2, blocks.length);
    assertEquals(start, blocks[0].getOffset());
    assertEquals(OBJECT_SIZE - 12345, blocks[0].getLength());
    assertEquals(2 * OBJECT_SIZE, blocks[1].getOffset());
    assertEquals(OBJECT_SIZE, blocks[1].getLength());
    long covered = blocks[0].getLength() + blocks[1].getLength();
    assertEquals(FILE_SIZE - start, covered);
  }

  @Test
  public void testEmptyFileAndDirectory() throws Exception {
    Path empty = new Path(base, "empty.bin");
    fs.create(empty, FsPermission.getFileDefault(), false, 4096, (short) 1,
        OBJECT_SIZE, null).close();
    assertNoOpenFds();

    assertEquals(0,
        fs.getFileBlockLocations(fs.getFileStatus(empty), 0, 100).length);
    assertEquals(0,
        fs.getFileBlockLocations(fs.getFileStatus(base), 0, 100).length);
    assertNoOpenFds();
  }

  @Test
  public void testGetStatusMagnitude() throws Exception {
    FsStatus status = fs.getStatus();
    // 供人工与 `ceph df` 比对（验收标准 4）
    System.out.println("[T05-status] capacity=" + status.getCapacity()
        + " used=" + status.getUsed() + " remaining=" + status.getRemaining());
    assertTrue("capacity > 0", status.getCapacity() > 0);
    assertTrue("remaining >= 0", status.getRemaining() >= 0);
    assertTrue("used >= 0", status.getUsed() >= 0);
    assertTrue("remaining <= capacity", status.getRemaining() <= status.getCapacity());
    assertEquals("capacity = used + remaining",
        status.getCapacity(), status.getUsed() + status.getRemaining());

    // 带路径的重载同样可用
    FsStatus baseStatus = fs.getStatus(base);
    assertEquals(status.getCapacity(), baseStatus.getCapacity());
  }
}
