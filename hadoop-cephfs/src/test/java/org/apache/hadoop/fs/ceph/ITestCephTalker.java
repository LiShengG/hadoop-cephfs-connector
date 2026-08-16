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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Random;

import com.ceph.fs.CephFileExtent;
import com.ceph.fs.CephMount;
import com.ceph.fs.CephNotMountedException;
import com.ceph.fs.CephStat;
import com.ceph.fs.CephStatVFS;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * CephTalker 对真实 vstart 集群的集成测试（T02 验收标准 2）。
 *
 * <p>门控：环境变量 {@code CEPH_CONTRACT_TEST=1} 时才执行，否则整类 skip，
 * 保证无集群环境 {@code mvn test/verify} 绿色（见 AGENTS.md）。
 *
 * <p>集群连接信息以 docs/environments/E1.md 为准：mon 地址一律经 ceph.conf 读取，
 * 不硬编码。ceph.conf 路径可用系统属性 {@code ceph.conf.file} 或环境变量
 * {@code CEPH_CONF_FILE} 覆盖，默认 vstart 位置。
 *
 * <p>运行方式（集群启动后）：
 * <pre>
 *   CEPH_CONTRACT_TEST=1 mvn verify -Dit.test=ITestCephTalker
 * </pre>
 */
public class ITestCephTalker {

  private static final String DEFAULT_CEPH_CONF = "/home/lsh/code/ceph/build/ceph.conf";
  private CephTalker talker;
  private Path testDir;

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

    Configuration conf = new Configuration(false);
    conf.set(CephConfigKeys.CEPH_CONF_FILE_KEY, cephConfFile());
    CephTestConfig.applyAuth(conf);

    talker = new CephTalker();
    // URI 无 authority：mon 地址完全来自 ceph.conf（docs/environments/E1.md，勿硬编码）
    talker.initialize(URI.create("ceph:///"), conf);

    testDir = new Path("/itest-t02-" + System.nanoTime());
  }

  @After
  public void tearDown() throws Exception {
    if (talker == null) {
      return;
    }
    // 尽力清理测试目录（目录内容各用例自删；这里兜底）
    try {
      String[] entries = talker.listdir(testDir);
      if (entries != null) {
        for (String name : entries) {
          try {
            talker.unlink(new Path(testDir, name));
          } catch (IOException ignored) {
            // 兜底清理，忽略
          }
        }
        talker.rmdir(testDir);
      }
    } catch (IOException ignored) {
      // 目录不存在或已关闭，忽略
    }
    talker.shutdown();
    talker = null;
  }

  /** 任务书指定的全流程：mkdirs → lstat → listdir → open/write/read/close → unlink。 */
  @Test
  public void testFullRoundTrip() throws Exception {
    final int size = 256 * 1024;
    Path file = new Path(testDir, "data.bin");

    // mkdirs + lstat 目录
    talker.mkdirs(testDir, 0755);
    CephStat dirStat = new CephStat();
    talker.lstat(testDir, dirStat);
    assertTrue("expected a directory", dirStat.isDir());

    // 写入
    byte[] wbuf = new byte[size];
    new Random(20260705L).nextBytes(wbuf);
    int wfd = talker.open(file,
        CephMount.O_WRONLY | CephMount.O_CREAT | CephMount.O_EXCL, 0644);
    assertTrue("fd should be non-negative", wfd >= 0);
    long written = talker.write(wfd, wbuf, size, 0);
    assertEquals(size, written);

    // flush + fstat 可见性
    talker.flush(wfd);
    CephStat openStat = new CephStat();
    talker.fstat(wfd, openStat);
    assertEquals(size, openStat.size);
    talker.close(wfd);

    // lstat 文件
    CephStat fileStat = new CephStat();
    talker.lstat(file, fileStat);
    assertTrue(fileStat.isFile());
    assertEquals(size, fileStat.size);

    // listdir 包含该文件
    String[] entries = talker.listdir(testDir);
    assertNotNull(entries);
    assertTrue("listdir should contain data.bin",
        Arrays.asList(entries).contains("data.bin"));

    // 回读比对（显式 offset）
    int rfd = talker.open(file, CephMount.O_RDONLY, 0);
    byte[] rbuf = new byte[size];
    long total = 0;
    while (total < size) {
      byte[] chunk = new byte[size - (int) total];
      long n = talker.read(rfd, chunk, chunk.length, total);
      assertTrue("premature EOF at offset " + total, n > 0);
      System.arraycopy(chunk, 0, rbuf, (int) total, (int) n);
      total += n;
    }
    assertArrayEquals(wbuf, rbuf);

    // lseek + 隐式 offset（-1 = 当前位置）读
    long pos = talker.lseek(rfd, 1024, CephMount.SEEK_SET);
    assertEquals(1024, pos);
    byte[] seekBuf = new byte[512];
    long n = talker.read(rfd, seekBuf, seekBuf.length, -1);
    assertEquals(512, n);
    assertArrayEquals(Arrays.copyOfRange(wbuf, 1024, 1536), seekBuf);

    // 数据局部性：extent 与 OSD 地址
    CephFileExtent extent = talker.getFileExtent(rfd, 0);
    assertNotNull(extent);
    assertEquals(0, extent.getOffset());
    assertTrue(extent.getLength() > 0);
    assertTrue("extent should live on at least one OSD", extent.getOSDs().length > 0);
    InetAddress osdAddr = talker.getOsdAddress(extent.getOSDs()[0]);
    assertNotNull(osdAddr);
    talker.close(rfd);

    // chmod + 校验
    talker.chmod(file, 0600);
    CephStat chmodStat = new CephStat();
    talker.lstat(file, chmodStat);
    assertEquals(0600, chmodStat.mode & 07777);

    // setattr mtime（毫秒）+ 校验
    CephStat attr = new CephStat();
    attr.m_time = 1234567890000L;
    talker.setattr(file, attr, CephMount.SETATTR_MTIME);
    CephStat attrStat = new CephStat();
    talker.lstat(file, attrStat);
    assertEquals(1234567890000L, attrStat.m_time);

    // ftruncate + 校验
    int tfd = talker.open(file, CephMount.O_RDWR, 0);
    talker.ftruncate(tfd, 1000);
    CephStat truncStat = new CephStat();
    talker.fstat(tfd, truncStat);
    assertEquals(1000, truncStat.size);
    talker.close(tfd);

    // rename + 校验（旧路径消失、新路径存在）
    Path renamed = new Path(testDir, "renamed.bin");
    talker.rename(file, renamed);
    try {
      talker.lstat(file, new CephStat());
      fail("old path should be gone after rename");
    } catch (FileNotFoundException expected) {
      // 预期
    }
    CephStat renamedStat = new CephStat();
    talker.lstat(renamed, renamedStat);
    assertEquals(1000, renamedStat.size);

    // statfs
    CephStatVFS vfs = talker.statfs(testDir);
    assertTrue(vfs.bsize > 0);
    assertTrue(vfs.blocks > 0);

    // unlink + rmdir + 消失校验
    talker.unlink(renamed);
    talker.rmdir(testDir);
    try {
      talker.lstat(testDir, new CephStat());
      fail("test dir should be gone after rmdir");
    } catch (FileNotFoundException expected) {
      // 预期
    }
  }

  /**
   * 钉死 libcephfs mkdirs 对"已存在"的实际语义（Client::mkdirs：路径各级
   * 均可解析即返回 0）：对已存在目录、甚至末段为已存在<b>文件</b>都静默成功，
   * 不抛 EEXIST。Hadoop mkdirs 的"已存在文件应失败"检查必须由 T03 上层完成。
   */
  @Test
  public void testMkdirsExistingSemantics() throws Exception {
    talker.mkdirs(testDir, 0755);
    // 已存在目录：no-op，不抛
    talker.mkdirs(testDir, 0755);

    Path file = new Path(testDir, "occupied");
    int fd = talker.open(file, CephMount.O_WRONLY | CephMount.O_CREAT, 0644);
    talker.close(fd);
    // 末段为已存在文件：libcephfs 同样返回成功（不创建、不报错）
    talker.mkdirs(file, 0755);
    CephStat stat = new CephStat();
    talker.lstat(file, stat);
    assertTrue("path must still be a regular file", stat.isFile());

    talker.unlink(file);
    talker.rmdir(testDir);
  }

  @Test
  public void testLstatNonexistentThrowsFileNotFound() throws Exception {
    try {
      talker.lstat(new Path(testDir, "no-such-file"), new CephStat());
      fail("expected FileNotFoundException");
    } catch (FileNotFoundException expected) {
      // 预期
    }
  }

  @Test
  public void testListdirOnFileReturnsNull() throws Exception {
    talker.mkdirs(testDir, 0755);
    Path file = new Path(testDir, "plain.txt");
    int fd = talker.open(file, CephMount.O_WRONLY | CephMount.O_CREAT, 0644);
    talker.close(fd);

    assertNull("listdir on a regular file must return null (架构文档 §3.1)",
        talker.listdir(file));

    talker.unlink(file);
    talker.rmdir(testDir);
  }

  @Test
  public void testShutdownIdempotentAndGuard() throws Exception {
    talker.shutdown();
    talker.shutdown(); // 幂等：第二次调用不抛
    try {
      talker.lstat(new Path("/"), new CephStat());
      fail("expected CephNotMountedException after shutdown");
    } catch (CephNotMountedException expected) {
      // 预期
    }
  }

  @Test
  public void testDoubleInitializeRejected() throws Exception {
    Configuration conf = new Configuration(false);
    conf.set(CephConfigKeys.CEPH_CONF_FILE_KEY, cephConfFile());
    CephTestConfig.applyAuth(conf);
    try {
      talker.initialize(URI.create("ceph:///"), conf);
      fail("expected IllegalStateException on double initialize");
    } catch (IllegalStateException expected) {
      // 预期
    }
  }
}
