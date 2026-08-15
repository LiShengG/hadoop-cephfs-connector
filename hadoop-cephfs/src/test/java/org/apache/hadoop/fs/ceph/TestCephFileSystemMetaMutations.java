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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.FileNotFoundException;

import com.ceph.fs.CephFileAlreadyExistsException;
import com.ceph.fs.CephNotDirectoryException;
import com.ceph.fs.CephStat;

import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.ParentNotDirectoryException;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathIsNotEmptyDirectoryException;
import org.apache.hadoop.fs.permission.FsPermission;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

/**
 * mkdirs / delete 全分支 mock 单测（架构文档 §4 表 2、3 与 T02 交接的
 * "libcephfs mkdirs 末段为已存在文件时静默成功" 陷阱）。
 */
public class TestCephFileSystemMetaMutations {

  private CephFsProto proto;
  private CephFileSystem fs;

  @Before
  public void setUp() throws Exception {
    proto = mock(CephFsProto.class);
    fs = newFs(proto);
  }

  @After
  public void tearDown() throws Exception {
    fs.close();
  }

  // ── mkdirs（§4 表 3 + T02 最大陷阱） ──────────────────────────────────

  @Test
  public void testMkdirsCreatesWithUmaskedMode() throws Exception {
    Path d = new Path("/a/b");
    mockLstatMissing(proto, d);

    assertTrue(fs.mkdirs(d, new FsPermission((short) 0777)));
    // 默认 umask 022：0777 → 0755
    verify(proto).mkdirs(eq(d), eq(0755));
  }

  @Test
  public void testMkdirsExistingDirectoryReturnsTrueWithoutProtoCall()
      throws Exception {
    Path d = new Path("/exists");
    mockLstat(proto, d, dirStat());

    assertTrue(fs.mkdirs(d, FsPermission.getDirDefault()));
    verify(proto, never()).mkdirs(any(Path.class), anyInt());
  }

  @Test
  public void testMkdirsOverExistingFileThrows() throws Exception {
    // libcephfs mkdirs 对"末段为已存在文件"静默成功（T02 钉死），
    // 必须由 CephFileSystem 先 lstat 拦截。
    Path f = new Path("/occupied");
    mockLstat(proto, f, fileStat());

    assertThrows(FileAlreadyExistsException.class,
        () -> fs.mkdirs(f, FsPermission.getDirDefault()));
    verify(proto, never()).mkdirs(any(Path.class), anyInt());
  }

  @Test
  public void testMkdirsParentIsFileThrowsParentNotDirectory() throws Exception {
    // 中间级为文件：proto.mkdirs 抛 CephNotDirectoryException（ENOTDIR）
    Path d = new Path("/file/sub");
    mockLstatMissing(proto, d);
    doThrow(new CephNotDirectoryException("/file"))
        .when(proto).mkdirs(eq(d), anyInt());

    assertThrows(ParentNotDirectoryException.class,
        () -> fs.mkdirs(d, FsPermission.getDirDefault()));
  }

  @Test
  public void testMkdirsConcurrentDirectoryCreationIsIdempotent() throws Exception {
    Path d = new Path("/raced-directory");
    doThrow(new FileNotFoundException(d.toString()))
        .doAnswer(invocation -> {
          CephFsTestHelper.copyStat(dirStat(), invocation.getArgument(1));
          return null;
        })
        .when(proto).lstat(eq(d), any(CephStat.class));
    doThrow(new CephFileAlreadyExistsException("File exists"))
        .when(proto).mkdirs(eq(d), anyInt());

    assertTrue(fs.mkdirs(d, FsPermission.getDirDefault()));
    verify(proto).mkdirs(eq(d), anyInt());
  }

  @Test
  public void testMkdirsConcurrentFileCreationStillFails() throws Exception {
    Path d = new Path("/raced-file");
    doThrow(new FileNotFoundException(d.toString()))
        .doAnswer(invocation -> {
          CephFsTestHelper.copyStat(fileStat(), invocation.getArgument(1));
          return null;
        })
        .when(proto).lstat(eq(d), any(CephStat.class));
    doThrow(new CephFileAlreadyExistsException("File exists"))
        .when(proto).mkdirs(eq(d), anyInt());

    assertThrows(FileAlreadyExistsException.class,
        () -> fs.mkdirs(d, FsPermission.getDirDefault()));
  }

  @Test
  public void testMkdirsRelativePathUsesWorkingDir() throws Exception {
    fs.setWorkingDirectory(new Path("/base"));
    mockLstatMissing(proto, new Path("/base/rel"));

    assertTrue(fs.mkdirs(new Path("rel"), new FsPermission((short) 0755)));
    verify(proto).mkdirs(eq(new Path("/base/rel")), eq(0755));
  }

  // ── delete（§4 表 2） ─────────────────────────────────────────────────

  @Test
  public void testDeleteMissingReturnsFalse() throws Exception {
    mockLstatMissing(proto, new Path("/nope"));
    assertFalse(fs.delete(new Path("/nope"), false));
    verify(proto, never()).unlink(any(Path.class));
    verify(proto, never()).rmdir(any(Path.class));
  }

  @Test
  public void testDeleteFile() throws Exception {
    Path f = new Path("/f");
    mockLstat(proto, f, fileStat());

    assertTrue(fs.delete(f, false));
    verify(proto).unlink(eq(f));
    verify(proto, never()).rmdir(any(Path.class));
  }

  @Test
  public void testDeleteEmptyDirectoryNonRecursive() throws Exception {
    Path d = new Path("/empty");
    mockLstat(proto, d, dirStat());
    when(proto.listdir(eq(d))).thenReturn(new String[0]);

    assertTrue(fs.delete(d, false));
    verify(proto).rmdir(eq(d));
    verify(proto, never()).unlink(any(Path.class));
  }

  @Test
  public void testDeleteNonEmptyDirectoryNonRecursiveThrows() throws Exception {
    Path d = new Path("/full");
    mockLstat(proto, d, dirStat());
    when(proto.listdir(eq(d))).thenReturn(new String[] {"child"});

    assertThrows(PathIsNotEmptyDirectoryException.class,
        () -> fs.delete(d, false));
    verify(proto, never()).rmdir(any(Path.class));
    verify(proto, never()).unlink(any(Path.class));
  }

  @Test
  public void testDeleteRecursivePostOrder() throws Exception {
    // 树：/d/{f1, sub/{f2}} —— Java 侧后序遍历（§4-2）
    Path d = new Path("/d");
    Path f1 = new Path("/d/f1");
    Path sub = new Path("/d/sub");
    Path f2 = new Path("/d/sub/f2");

    mockLstat(proto, d, dirStat());
    mockLstat(proto, f1, fileStat());
    mockLstat(proto, sub, dirStat());
    mockLstat(proto, f2, fileStat());
    when(proto.listdir(eq(d))).thenReturn(new String[] {"f1", "sub"});
    when(proto.listdir(eq(sub))).thenReturn(new String[] {"f2"});

    assertTrue(fs.delete(d, true));

    InOrder order = inOrder(proto);
    order.verify(proto).unlink(eq(f1));
    order.verify(proto).unlink(eq(f2));
    order.verify(proto).rmdir(eq(sub)); // 子目录先清空后 rmdir
    order.verify(proto).rmdir(eq(d));   // 最后删自身
  }

  @Test
  public void testDeleteRootNonRecursiveIsProtected() throws Exception {
    // 任务书：根目录删除保护 —— delete("/") 且 !recursive → false
    Path root = new Path("/");
    mockLstat(proto, root, dirStat());

    assertFalse(fs.delete(root, false));
    verify(proto, never()).rmdir(any(Path.class));
    verify(proto, never()).unlink(any(Path.class));
  }

  @Test
  public void testDeleteRootRecursiveClearsChildrenButKeepsRoot() throws Exception {
    Path root = new Path("/");
    Path child = new Path("/x");
    mockLstat(proto, root, dirStat());
    mockLstat(proto, child, fileStat());
    when(proto.listdir(eq(root))).thenReturn(new String[] {"x"});

    assertTrue(fs.delete(root, true));
    verify(proto).unlink(eq(child));
    verify(proto, never()).rmdir(any(Path.class)); // 根本身保留
  }
}
