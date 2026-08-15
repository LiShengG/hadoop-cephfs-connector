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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;

import com.ceph.fs.CephMount;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathIsNotEmptyDirectoryException;
import org.apache.hadoop.fs.permission.FsPermission;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * CephFileSystem 元数据操作对真实 vstart 集群的集成测试（T03 验收标准 2/3）。
 *
 * <p>门控：环境变量 {@code CEPH_CONTRACT_TEST=1}；集群信息以 docs/ENV.md 为准
 * （mon 地址经 ceph.conf 读取，不硬编码）。
 *
 * <p>文件的创建借助独立 {@link CephTalker}（T03 中 create() 为 T04 留桩）。
 *
 * <p>运行方式（集群启动后）：
 * <pre>
 *   CEPH_CONTRACT_TEST=1 mvn verify -Dit.test=ITestCephFileSystemMeta
 * </pre>
 */
public class ITestCephFileSystemMeta {

  private static final String DEFAULT_CEPH_CONF = "/home/lsh/code/ceph/build/ceph.conf";
  private Configuration conf;
  private FileSystem fs;
  private CephTalker talker; // 辅助 mount：仅用于创建测试文件（T04 前无 create）
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
    // 各用例独立实例，避免跨用例共享已 close 的缓存实例
    conf.setBoolean("fs.ceph.impl.disable.cache", true);

    // 验收标准 3：不设置 fs.ceph.impl，完全依赖 ServiceLoader 发现
    fs = FileSystem.get(URI.create("ceph:///"), conf);

    talker = new CephTalker();
    Configuration talkerConf = new Configuration(false);
    talkerConf.set(CephConfigKeys.CEPH_CONF_FILE_KEY, cephConfFile());
    CephTestConfig.applyAuth(talkerConf);
    talker.initialize(URI.create("ceph:///"), talkerConf);

    base = new Path("/itest-t03-" + System.nanoTime());
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
    if (talker != null) {
      talker.shutdown();
      talker = null;
    }
  }

  /** 通过辅助 mount 创建一个 size 字节的文件（T04 前 fs.create 为留桩）。 */
  private void createFile(Path path, int size) throws IOException {
    int fd = talker.open(path,
        CephMount.O_WRONLY | CephMount.O_CREAT | CephMount.O_EXCL, 0644);
    try {
      if (size > 0) {
        byte[] data = new byte[size];
        Arrays.fill(data, (byte) 'x');
        assertEquals(size, talker.write(fd, data, size, 0));
      }
      talker.flush(fd);
    } finally {
      talker.close(fd);
    }
  }

  /** 验收标准 3：FileSystem.get(ceph:///) 经 ServiceLoader 拿到本实现。 */
  @Test
  public void testServiceLoaderDiscovery() throws Exception {
    assertTrue("expected CephFileSystem via ServiceLoader, got " + fs.getClass(),
        fs instanceof CephFileSystem);
    assertEquals("ceph", fs.getScheme());
    assertEquals(URI.create("ceph:///"), fs.getUri());
  }

  /** mkdir/status/ls 全链路。 */
  @Test
  public void testMkdirsStatusAndList() throws Exception {
    Path nested = new Path(base, "d1/d2");
    assertTrue(fs.mkdirs(nested, new FsPermission((short) 0755)));

    FileStatus dirSt = fs.getFileStatus(nested);
    assertTrue(dirSt.isDirectory());

    createFile(new Path(nested, "f.bin"), 4096);
    FileStatus fileSt = fs.getFileStatus(new Path(nested, "f.bin"));
    assertTrue(fileSt.isFile());
    assertEquals(4096, fileSt.getLen());
    assertTrue(fileSt.getBlockSize() > 0);
    assertNotNull(fileSt.getOwner());

    FileStatus[] listing = fs.listStatus(nested);
    assertEquals(1, listing.length);
    assertEquals("f.bin", listing[0].getPath().getName());

    // listStatus 文件 → 单元素
    FileStatus[] single = fs.listStatus(new Path(nested, "f.bin"));
    assertEquals(1, single.length);
    assertTrue(single[0].isFile());

    // 不存在 → FNF
    try {
      fs.listStatus(new Path(base, "missing"));
      fail("expected FileNotFoundException");
    } catch (FileNotFoundException expected) {
      // 预期
    }

    // mkdirs 覆盖已存在文件 → 异常（T02 陷阱：libcephfs 会静默成功）
    try {
      fs.mkdirs(new Path(nested, "f.bin"));
      fail("expected FileAlreadyExistsException");
    } catch (FileAlreadyExistsException expected) {
      // 预期
    }
    // 已存在目录 → true
    assertTrue(fs.mkdirs(nested));
  }

  /** rename 语义修正（§4-1）在真实集群上生效。 */
  @Test
  public void testRenameSemantics() throws Exception {
    assertTrue(fs.mkdirs(base));
    Path f1 = new Path(base, "f1");
    Path f2 = new Path(base, "f2");
    createFile(f1, 128);
    createFile(f2, 256);

    // dst 为已存在文件 → false 且不覆盖
    assertFalse(fs.rename(f1, f2));
    assertEquals(256, fs.getFileStatus(f2).getLen());

    // src 不存在 → false
    assertFalse(fs.rename(new Path(base, "ghost"), new Path(base, "g2")));

    // 普通改名
    Path f3 = new Path(base, "f3");
    assertTrue(fs.rename(f1, f3));
    assertEquals(128, fs.getFileStatus(f3).getLen());
    try {
      fs.getFileStatus(f1);
      fail("old path should be gone");
    } catch (FileNotFoundException expected) {
      // 预期
    }

    // dst 为已存在目录 → 移入其下
    Path sub = new Path(base, "sub");
    assertTrue(fs.mkdirs(sub));
    assertTrue(fs.rename(f3, sub));
    assertTrue(fs.getFileStatus(new Path(sub, "f3")).isFile());
  }

  /** delete 语义修正（§4-2）在真实集群上生效。 */
  @Test
  public void testDeleteSemantics() throws Exception {
    Path sub = new Path(base, "sub");
    assertTrue(fs.mkdirs(sub));
    createFile(new Path(base, "top.bin"), 64);
    createFile(new Path(sub, "deep.bin"), 64);

    // 非空目录 + !recursive → PathIsNotEmptyDirectoryException
    try {
      fs.delete(base, false);
      fail("expected PathIsNotEmptyDirectoryException");
    } catch (PathIsNotEmptyDirectoryException expected) {
      // 预期
    }

    // 文件删除
    assertTrue(fs.delete(new Path(base, "top.bin"), false));
    assertFalse(fs.delete(new Path(base, "top.bin"), false)); // 已不存在 → false

    // 递归删除（Java 侧后序遍历）
    assertTrue(fs.delete(base, true));
    try {
      fs.getFileStatus(base);
      fail("base should be gone");
    } catch (FileNotFoundException expected) {
      // 预期
    }
  }

  /** workingDir 纯 Java 维护（§4-7）+ setTimes/setPermission。 */
  @Test
  public void testWorkingDirAndAttrs() throws Exception {
    assertTrue(fs.getWorkingDirectory().toUri().getPath().startsWith("/user/"));

    assertTrue(fs.mkdirs(base));
    fs.setWorkingDirectory(base);
    assertEquals(base.toUri().getPath(), fs.getWorkingDirectory().toUri().getPath());

    // 相对路径经 workingDir 解析
    assertTrue(fs.mkdirs(new Path("relsub")));
    assertTrue(fs.getFileStatus(new Path(base, "relsub")).isDirectory());

    Path f = new Path(base, "attr.bin");
    createFile(f, 32);

    fs.setPermission(f, new FsPermission((short) 0600));
    assertEquals(new FsPermission((short) 0600), fs.getFileStatus(f).getPermission());

    fs.setTimes(f, 1234567890000L, -1);
    assertEquals(1234567890000L, fs.getFileStatus(f).getModificationTime());

    assertFalse(fs.setReplication(f, (short) 2));
  }
}
