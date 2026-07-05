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

import static org.apache.hadoop.fs.ceph.CephFsTestHelper.dirStat;
import static org.apache.hadoop.fs.ceph.CephFsTestHelper.fileStat;
import static org.apache.hadoop.fs.ceph.CephFsTestHelper.mockLstat;
import static org.apache.hadoop.fs.ceph.CephFsTestHelper.mockLstatMissing;
import static org.apache.hadoop.fs.ceph.CephFsTestHelper.newFs;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;

import com.ceph.fs.CephMount;
import com.ceph.fs.CephStat;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.security.UserGroupInformation;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * CephFileSystem 生命周期 + 查询类 + setter 类的 mock 单测（无集群）。
 *
 * <p>覆盖架构文档 §4 表条目：4（setReplication）、6（uid→用户名映射）、
 * 7（workingDir 纯 Java 维护）、8（时间戳毫秒直传）。
 * mkdirs/delete/rename 见 {@link TestCephFileSystemMetaMutations} /
 * {@link TestCephFileSystemMetaRename}。
 */
public class TestCephFileSystemMeta {

  private CephFsProto proto;
  private CephFileSystem fs;

  @Before
  public void setUp() throws Exception {
    proto = mock(CephFsProto.class);
    fs = newFs(proto);
  }

  @After
  public void tearDown() throws Exception {
    if (fs != null) {
      fs.close();
    }
  }

  // ── 生命周期 ──────────────────────────────────────────────────────────

  @Test
  public void testInitializePropagatesToProto() throws Exception {
    verify(proto).initialize(eq(URI.create("ceph:///")), any(Configuration.class));
  }

  @Test
  public void testGetSchemeAndUri() {
    assertEquals("ceph", fs.getScheme());
    assertEquals(URI.create("ceph:///"), fs.getUri());
  }

  @Test
  public void testUriKeepsAuthority() throws Exception {
    CephFsProto p = mock(CephFsProto.class);
    CephFileSystem f = new CephFileSystem(p);
    f.initialize(URI.create("ceph://monhost:6789/some/path"), new Configuration(false));
    assertEquals(URI.create("ceph://monhost:6789/"), f.getUri());
    f.close();
  }

  @Test
  public void testCloseIsIdempotent() throws Exception {
    fs.close();
    fs.close();
    verify(proto, times(1)).shutdown();
  }

  @Test
  public void testInitialWorkingDirIsUserHome() throws Exception {
    String user = UserGroupInformation.getCurrentUser().getShortUserName();
    assertEquals("/user/" + user, fs.getWorkingDirectory().toUri().getPath());
    assertEquals("ceph", fs.getWorkingDirectory().toUri().getScheme());
  }

  // ── workingDir / 相对路径解析（§4-7：纯 Java 侧维护） ─────────────────

  @Test
  public void testSetWorkingDirectoryAbsoluteAndRelative() {
    fs.setWorkingDirectory(new Path("/base"));
    assertEquals("/base", fs.getWorkingDirectory().toUri().getPath());
    // 相对路径基于当前 workingDir 解析
    fs.setWorkingDirectory(new Path("sub"));
    assertEquals("/base/sub", fs.getWorkingDirectory().toUri().getPath());
  }

  @Test
  public void testRelativePathResolvedAgainstWorkingDir() throws Exception {
    fs.setWorkingDirectory(new Path("/base"));
    mockLstat(proto, new Path("/base/rel.txt"), fileStat());

    FileStatus st = fs.getFileStatus(new Path("rel.txt"));
    assertTrue(st.isFile());
    verify(proto).lstat(eq(new Path("/base/rel.txt")), any(CephStat.class));
  }

  // ── getFileStatus ────────────────────────────────────────────────────

  @Test
  public void testGetFileStatusFile() throws Exception {
    CephStat s = fileStat();
    s.size = 1234;
    s.mode = 0640;
    s.uid = CephFileSystem.PROCESS_UID + 1; // 非当前进程 uid → 数字字符串
    s.gid = 4321;
    s.m_time = 111_111L; // CephStat 时间戳单位毫秒（§4-8，JNI 已换算）
    s.a_time = 222_222L;
    s.blksize = 4 * 1024 * 1024;
    mockLstat(proto, new Path("/f"), s);

    FileStatus st = fs.getFileStatus(new Path("/f"));
    assertTrue(st.isFile());
    assertEquals(1234, st.getLen());
    assertEquals(new FsPermission((short) 0640), st.getPermission());
    assertEquals(String.valueOf(CephFileSystem.PROCESS_UID + 1), st.getOwner());
    assertEquals("4321", st.getGroup());
    assertEquals(111_111L, st.getModificationTime());
    assertEquals(222_222L, st.getAccessTime());
    assertEquals(4 * 1024 * 1024, st.getBlockSize());
    assertEquals(fs.getDefaultReplication(), st.getReplication());
    // 返回路径已 qualify
    assertEquals("ceph", st.getPath().toUri().getScheme());
    assertEquals("/f", st.getPath().toUri().getPath());
  }

  @Test
  public void testGetFileStatusOwnerMapsProcessUidToUserName() throws Exception {
    // §4-6：uid 与当前进程相同 → 当前用户名（PROCESS_UID=-1 时该分支不可测，跳过匹配断言）
    CephStat s = fileStat();
    s.uid = CephFileSystem.PROCESS_UID;
    mockLstat(proto, new Path("/mine"), s);

    FileStatus st = fs.getFileStatus(new Path("/mine"));
    if (CephFileSystem.PROCESS_UID != -1) {
      assertEquals(UserGroupInformation.getCurrentUser().getShortUserName(),
          st.getOwner());
    } else {
      assertEquals("-1", st.getOwner());
    }
  }

  @Test
  public void testGetFileStatusZeroBlksizeFallsBackToDefault() throws Exception {
    CephStat s = fileStat();
    s.blksize = 0;
    mockLstat(proto, new Path("/f"), s);

    assertEquals(fs.getDefaultBlockSize(),
        fs.getFileStatus(new Path("/f")).getBlockSize());
    assertEquals(CephConfigKeys.CEPH_OBJECT_SIZE_DEFAULT, fs.getDefaultBlockSize());
  }

  @Test
  public void testGetFileStatusDirectory() throws Exception {
    CephStat s = dirStat();
    s.size = 4096; // POSIX 目录 size 不上报
    mockLstat(proto, new Path("/d"), s);

    FileStatus st = fs.getFileStatus(new Path("/d"));
    assertTrue(st.isDirectory());
    assertEquals(0, st.getLen());
    assertEquals(new FsPermission((short) 0755), st.getPermission());
  }

  @Test
  public void testGetFileStatusMissingThrowsFileNotFound() throws Exception {
    mockLstatMissing(proto, new Path("/nope"));
    assertThrows(FileNotFoundException.class,
        () -> fs.getFileStatus(new Path("/nope")));
  }

  // ── listStatus ───────────────────────────────────────────────────────

  @Test
  public void testListStatusDirectory() throws Exception {
    Path dir = new Path("/d");
    when(proto.listdir(eq(dir))).thenReturn(new String[] {"a", "b"});
    mockLstat(proto, new Path("/d/a"), fileStat());
    mockLstat(proto, new Path("/d/b"), dirStat());

    FileStatus[] result = fs.listStatus(dir);
    assertEquals(2, result.length);
    assertTrue(result[0].isFile());
    assertEquals("/d/a", result[0].getPath().toUri().getPath());
    assertTrue(result[1].isDirectory());
    assertEquals("/d/b", result[1].getPath().toUri().getPath());
  }

  @Test
  public void testListStatusEmptyDirectory() throws Exception {
    when(proto.listdir(eq(new Path("/empty")))).thenReturn(new String[0]);
    assertEquals(0, fs.listStatus(new Path("/empty")).length);
  }

  @Test
  public void testListStatusOnFileReturnsSingleElement() throws Exception {
    Path file = new Path("/f");
    when(proto.listdir(eq(file))).thenReturn(null); // proto 契约：非目录 → null
    CephStat s = fileStat();
    s.size = 7;
    mockLstat(proto, file, s);

    FileStatus[] result = fs.listStatus(file);
    assertEquals(1, result.length);
    assertTrue(result[0].isFile());
    assertEquals(7, result[0].getLen());
    assertEquals("/f", result[0].getPath().toUri().getPath());
  }

  @Test
  public void testListStatusMissingThrowsFileNotFound() throws Exception {
    when(proto.listdir(eq(new Path("/nope"))))
        .thenThrow(new FileNotFoundException("/nope"));
    assertThrows(FileNotFoundException.class,
        () -> fs.listStatus(new Path("/nope")));
  }

  // ── setTimes / setPermission / setOwner / setReplication ─────────────

  @Test
  public void testSetTimesMtimeOnly() throws Exception {
    fs.setTimes(new Path("/f"), 5000L, -1);

    ArgumentCaptor<CephStat> cap = ArgumentCaptor.forClass(CephStat.class);
    verify(proto).setattr(eq(new Path("/f")), cap.capture(),
        eq(CephMount.SETATTR_MTIME));
    assertEquals(5000L, cap.getValue().m_time);
  }

  @Test
  public void testSetTimesAtimeOnly() throws Exception {
    fs.setTimes(new Path("/f"), -1, 6000L);

    ArgumentCaptor<CephStat> cap = ArgumentCaptor.forClass(CephStat.class);
    verify(proto).setattr(eq(new Path("/f")), cap.capture(),
        eq(CephMount.SETATTR_ATIME));
    assertEquals(6000L, cap.getValue().a_time);
  }

  @Test
  public void testSetTimesBoth() throws Exception {
    fs.setTimes(new Path("/f"), 5000L, 6000L);
    verify(proto).setattr(eq(new Path("/f")), any(CephStat.class),
        eq(CephMount.SETATTR_MTIME | CephMount.SETATTR_ATIME));
  }

  @Test
  public void testSetTimesNoopWhenBothMinusOne() throws Exception {
    fs.setTimes(new Path("/f"), -1, -1);
    verify(proto, never()).setattr(any(Path.class), any(CephStat.class), anyInt());
  }

  @Test
  public void testSetPermission() throws Exception {
    fs.setPermission(new Path("/f"), new FsPermission((short) 0600));
    verify(proto).chmod(eq(new Path("/f")), eq(0600));
  }

  @Test
  public void testSetOwnerNumericUidGid() throws Exception {
    fs.setOwner(new Path("/f"), "1001", "1002");

    ArgumentCaptor<CephStat> cap = ArgumentCaptor.forClass(CephStat.class);
    verify(proto).setattr(eq(new Path("/f")), cap.capture(),
        eq(CephMount.SETATTR_UID | CephMount.SETATTR_GID));
    assertEquals(1001, cap.getValue().uid);
    assertEquals(1002, cap.getValue().gid);
  }

  @Test
  public void testSetOwnerNumericGidOnly() throws Exception {
    fs.setOwner(new Path("/f"), null, "1002");
    verify(proto).setattr(eq(new Path("/f")), any(CephStat.class),
        eq(CephMount.SETATTR_GID));
  }

  @Test
  public void testSetOwnerNonNumericIsNoop() throws Exception {
    fs.setOwner(new Path("/f"), "alice", "staff");
    verify(proto, never()).setattr(any(Path.class), any(CephStat.class), anyInt());
  }

  @Test
  public void testSetReplicationReturnsFalse() throws Exception {
    // §4-4：CephFS 副本数由 pool 决定
    assertFalse(fs.setReplication(new Path("/f"), (short) 2));
  }

  @Test
  public void testDefaultReplicationFromConf() throws Exception {
    assertEquals(3, fs.getDefaultReplication());

    Configuration conf = new Configuration(false);
    conf.setInt(CephConfigKeys.CEPH_REPLICATION_KEY, 2);
    CephFileSystem f = newFs(mock(CephFsProto.class), conf);
    assertEquals(2, f.getDefaultReplication());
    f.close();
  }

  // ── 异常映射（工作内容 4：集中 helper） ────────────────────────────────

  @Test
  public void testSetattrIoErrorPassesThrough() throws Exception {
    doThrow(new IOException("Disk quota exceeded"))
        .when(proto).setattr(any(Path.class), any(CephStat.class), anyInt());
    IOException e = assertThrows(IOException.class,
        () -> fs.setTimes(new Path("/f"), 1L, -1));
    assertEquals("Disk quota exceeded", e.getMessage());
  }
}
