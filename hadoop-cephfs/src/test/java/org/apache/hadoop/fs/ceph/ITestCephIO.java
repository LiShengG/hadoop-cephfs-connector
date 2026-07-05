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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * CephFileSystem 数据流对真实 vstart 集群的集成测试（T04）。
 *
 * <p>门控：环境变量 {@code CEPH_CONTRACT_TEST=1}；集群信息以 docs/ENV.md 为准。
 *
 * <p>运行方式：
 * <pre>
 *   CEPH_CONTRACT_TEST=1 mvn verify -Dit.test=ITestCephIO
 * </pre>
 */
public class ITestCephIO {

  private static final String DEFAULT_CEPH_CONF = "/home/lsh/code/ceph/build/ceph.conf";
  private static final String DEFAULT_AUTH_ID = "admin";
  private static final int LARGE_FILE_SIZE = 200 * 1024 * 1024;
  private static final int IO_CHUNK = 1024 * 1024;

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
    conf.set(CephConfigKeys.CEPH_AUTH_ID_KEY, DEFAULT_AUTH_ID);
    conf.setBoolean("fs.ceph.impl.disable.cache", true);
    conf.setInt(CephConfigKeys.CEPH_CLIENT_BUFFER_SIZE_KEY, 4 * 1024 * 1024);

    fs = newFileSystem();
    base = new Path("/itest-t04-" + System.nanoTime());
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

  @Test
  public void testLargeFileMd5AndRandomPread() throws Exception {
    Path file = new Path(base, "large.bin");
    byte[] data = new byte[LARGE_FILE_SIZE];
    new Random(20260705L).nextBytes(data);
    byte[] expectedMd5 = digest(data);

    try (FSDataOutputStream out = fs.create(file, FsPermission.getFileDefault(),
        false, IO_CHUNK, (short) 3, CephConfigKeys.CEPH_OBJECT_SIZE_DEFAULT, null)) {
      for (int off = 0; off < data.length; off += IO_CHUNK) {
        out.write(data, off, Math.min(IO_CHUNK, data.length - off));
      }
    }
    assertNoOpenFds(fs);
    assertEquals(LARGE_FILE_SIZE, fs.getFileStatus(file).getLen());

    MessageDigest md5 = MessageDigest.getInstance("MD5");
    long total = 0;
    byte[] buf = new byte[IO_CHUNK];
    try (FSDataInputStream in = fs.open(file)) {
      int n;
      while ((n = in.read(buf, 0, buf.length)) >= 0) {
        md5.update(buf, 0, n);
        total += n;
      }
    }
    assertNoOpenFds(fs);
    assertEquals(LARGE_FILE_SIZE, total);
    assertArrayEquals(expectedMd5, md5.digest());

    Random random = new Random(20260706L);
    try (FSDataInputStream in = fs.open(file)) {
      for (int i = 0; i < 100; i++) {
        int len = 1 + random.nextInt(64 * 1024);
        int pos = random.nextInt(data.length - len + 1);
        byte[] actual = new byte[len];
        in.readFully(pos, actual, 0, len);
        assertArrayEquals(Arrays.copyOfRange(data, pos, pos + len), actual);
      }
    }
    assertNoOpenFds(fs);
  }

  @Test
  public void testAppendCorrectness() throws Exception {
    Path file = new Path(base, "append.txt");
    byte[] first = bytes("hello");
    byte[] second = bytes(" world");

    try (FSDataOutputStream out = fs.create(file, FsPermission.getFileDefault(),
        false, 4096, (short) 3, fs.getDefaultBlockSize(), null)) {
      out.write(first);
    }
    assertNoOpenFds(fs);

    try (FSDataOutputStream out = fs.append(file, 4096, null)) {
      assertEquals(first.length, out.getPos());
      out.write(second);
    }
    assertNoOpenFds(fs);

    byte[] actual = new byte[first.length + second.length];
    try (FSDataInputStream in = fs.open(file)) {
      in.readFully(0, actual);
    }
    assertNoOpenFds(fs);
    assertArrayEquals(bytes("hello world"), actual);
    assertEquals(actual.length, fs.getFileStatus(file).getLen());
  }

  @Test
  public void testCreateOverwriteTruncatesExisting() throws Exception {
    // T04-verify 补充：overwrite=true 走 O_TRUNC + 7 参 layout open 打开已存在文件，
    // 原 ITest 仅覆盖 overwrite=false，此分支此前未在真实集群上验证过。
    Path file = new Path(base, "overwrite.bin");
    byte[] big = new byte[3 * IO_CHUNK];
    new Random(20260707L).nextBytes(big);
    byte[] small = bytes("overwritten");

    try (FSDataOutputStream out = fs.create(file, FsPermission.getFileDefault(),
        false, 4096, (short) 3, fs.getDefaultBlockSize(), null)) {
      out.write(big);
    }
    assertNoOpenFds(fs);
    assertEquals(big.length, fs.getFileStatus(file).getLen());

    try (FSDataOutputStream out = fs.create(file, FsPermission.getFileDefault(),
        true, 4096, (short) 3, fs.getDefaultBlockSize(), null)) {
      out.write(small);
    }
    assertNoOpenFds(fs);
    assertEquals(small.length, fs.getFileStatus(file).getLen());

    byte[] actual = new byte[small.length];
    try (FSDataInputStream in = fs.open(file)) {
      in.readFully(0, actual);
    }
    assertNoOpenFds(fs);
    assertArrayEquals(small, actual);
  }

  @Test
  public void testHflushVisibleFromAnotherFileSystem() throws Exception {
    Path file = new Path(base, "flush.txt");
    byte[] visible = bytes("visible after hflush");
    byte[] tail = bytes(" and close");

    try (FSDataOutputStream out = fs.create(file, FsPermission.getFileDefault(),
        false, 4096, (short) 3, fs.getDefaultBlockSize(), null)) {
      out.write(visible);
      out.hflush();

      CephFileSystem other = newFileSystem();
      try {
        byte[] actual = new byte[visible.length];
        try (FSDataInputStream in = other.open(file)) {
          in.readFully(0, actual);
        }
        assertNoOpenFds(other);
        assertArrayEquals(visible, actual);
      } finally {
        other.close();
      }

      out.write(tail);
    }
    assertNoOpenFds(fs);

    byte[] all = new byte[visible.length + tail.length];
    try (FSDataInputStream in = fs.open(file)) {
      in.readFully(0, all);
    }
    assertNoOpenFds(fs);
    assertArrayEquals(bytes("visible after hflush and close"), all);
  }

  private CephFileSystem newFileSystem() throws IOException {
    FileSystem created = FileSystem.get(URI.create("ceph:///"), conf);
    assertTrue("expected CephFileSystem, got " + created.getClass(),
        created instanceof CephFileSystem);
    return (CephFileSystem) created;
  }

  private static void assertNoOpenFds(CephFileSystem fileSystem) {
    assertEquals("open fd count", 0, fileSystem.openFileDescriptorCountForTests());
  }

  private static byte[] digest(byte[] data) throws Exception {
    MessageDigest md5 = MessageDigest.getInstance("MD5");
    md5.update(data);
    return md5.digest();
  }

  private static byte[] bytes(String text) {
    return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }
}
