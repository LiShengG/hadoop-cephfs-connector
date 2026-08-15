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

import java.io.FileNotFoundException;
import java.io.IOException;

import static org.apache.hadoop.fs.ceph.CephFsTestHelper.dirStat;
import static org.apache.hadoop.fs.ceph.CephFsTestHelper.fileStat;
import static org.apache.hadoop.fs.ceph.CephFsTestHelper.mockLstat;
import static org.apache.hadoop.fs.ceph.CephFsTestHelper.mockLstatMissing;
import static org.apache.hadoop.fs.ceph.CephFsTestHelper.newFs;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ceph.fs.CephFileAlreadyExistsException;

import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.Options;
import org.apache.hadoop.fs.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * rename 全分支矩阵 mock 单测（架构文档 §4 表 1：禁止 POSIX 静默覆盖、
 * dst 目录移入其下、src 不存在返回 false 等）。
 */
public class TestCephFileSystemMetaRename {

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

  private void verifyNoRename() throws Exception {
    verify(proto, never()).rename(any(Path.class), any(Path.class));
  }

  /** SP-04B：FileContext NONE rename 的普通文件走原子 link + unlink。 */
  @Test
  public void testNoOverwriteFileRenameUsesLinkThenUnlink() throws Exception {
    mockLstat(proto, new Path("/src"), fileStat());
    mockLstatMissing(proto, new Path("/dst"));
    mockLstat(proto, new Path("/"), dirStat());

    fs.rename(new Path("/src"), new Path("/dst"), Options.Rename.NONE);

    verify(proto).link(eq(new Path("/src")), eq(new Path("/dst")));
    verify(proto).unlink(eq(new Path("/src")));
    verifyNoRename();
  }

  /** SP-04B：link 原子竞争的落败方映射为 Hadoop FileAlreadyExistsException。 */
  @Test(expected = FileAlreadyExistsException.class)
  public void testNoOverwriteFileRenameMapsAtomicLinkConflict() throws Exception {
    mockLstat(proto, new Path("/src"), fileStat());
    mockLstatMissing(proto, new Path("/dst"));
    mockLstat(proto, new Path("/"), dirStat());
    doThrow(new CephFileAlreadyExistsException("File exists"))
        .when(proto).link(new Path("/src"), new Path("/dst"));

    fs.rename(new Path("/src"), new Path("/dst"), Options.Rename.NONE);
  }

  /** link 成功后源已被并发移除，目标已提交，不应再回滚。 */
  @Test
  public void testNoOverwriteFileRenameAcceptsConcurrentSourceRemoval() throws Exception {
    mockLstat(proto, new Path("/src"), fileStat());
    mockLstatMissing(proto, new Path("/dst"));
    mockLstat(proto, new Path("/"), dirStat());
    doThrow(new FileNotFoundException("gone"))
        .when(proto).unlink(new Path("/src"));

    fs.rename(new Path("/src"), new Path("/dst"), Options.Rename.NONE);

    verify(proto, never()).unlink(new Path("/dst"));
  }

  /** link 后移除源失败时回滚新目标，避免向调用方暴露半提交结果。 */
  @Test(expected = IOException.class)
  public void testNoOverwriteFileRenameRollsBackFailedSourceUnlink() throws Exception {
    mockLstat(proto, new Path("/src"), fileStat());
    mockLstatMissing(proto, new Path("/dst"));
    mockLstat(proto, new Path("/"), dirStat());
    doThrow(new IOException("unlink failed"))
        .when(proto).unlink(new Path("/src"));

    try {
      fs.rename(new Path("/src"), new Path("/dst"), Options.Rename.NONE);
    } finally {
      verify(proto).unlink(new Path("/dst"));
    }
  }

  /** §4-1：src 不存在 → false。 */
  @Test
  public void testSrcMissingReturnsFalse() throws Exception {
    mockLstatMissing(proto, new Path("/no-src"));
    assertFalse(fs.rename(new Path("/no-src"), new Path("/dst")));
    verifyNoRename();
  }

  /** §4-1：src == dst 且存在 → true（不触发底层 rename）。 */
  @Test
  public void testSrcEqualsDstReturnsTrue() throws Exception {
    mockLstat(proto, new Path("/same"), fileStat());
    assertTrue(fs.rename(new Path("/same"), new Path("/same")));
    verifyNoRename();
  }

  /** 根目录不可 rename。 */
  @Test
  public void testRenameRootReturnsFalse() throws Exception {
    mockLstat(proto, new Path("/"), dirStat());
    mockLstatMissing(proto, new Path("/elsewhere"));
    assertFalse(fs.rename(new Path("/"), new Path("/elsewhere")));
    verifyNoRename();
  }

  /** §4-1：dst 为已存在文件 → false（禁止 POSIX 静默覆盖）。 */
  @Test
  public void testDstExistingFileReturnsFalse() throws Exception {
    mockLstat(proto, new Path("/src"), fileStat());
    mockLstat(proto, new Path("/dst"), fileStat());
    assertFalse(fs.rename(new Path("/src"), new Path("/dst")));
    verifyNoRename();
  }

  /** §4-1：dst 为已存在目录 → 移入其下（dst/srcName）。 */
  @Test
  public void testDstExistingDirectoryMovesUnderIt() throws Exception {
    mockLstat(proto, new Path("/src.txt"), fileStat());
    mockLstat(proto, new Path("/destdir"), dirStat());
    mockLstatMissing(proto, new Path("/destdir/src.txt"));

    assertTrue(fs.rename(new Path("/src.txt"), new Path("/destdir")));
    verify(proto).rename(eq(new Path("/src.txt")), eq(new Path("/destdir/src.txt")));
  }

  /** dst 目录下已有同名条目 → false（与 HDFS 一致，不覆盖）。 */
  @Test
  public void testDstDirectoryWithOccupiedNameReturnsFalse() throws Exception {
    mockLstat(proto, new Path("/src.txt"), fileStat());
    mockLstat(proto, new Path("/destdir"), dirStat());
    mockLstat(proto, new Path("/destdir/src.txt"), fileStat());

    assertFalse(fs.rename(new Path("/src.txt"), new Path("/destdir")));
    verifyNoRename();
  }

  /** rename 到自身所在目录（dst = src 的父目录）→ no-op true。 */
  @Test
  public void testRenameIntoOwnParentReturnsTrue() throws Exception {
    mockLstat(proto, new Path("/d/f"), fileStat());
    mockLstat(proto, new Path("/d"), dirStat());

    assertTrue(fs.rename(new Path("/d/f"), new Path("/d")));
    verifyNoRename();
  }

  /** dst 不存在且其父目录也不存在 → false。 */
  @Test
  public void testDstParentMissingReturnsFalse() throws Exception {
    mockLstat(proto, new Path("/src"), fileStat());
    mockLstatMissing(proto, new Path("/no-parent/dst"));
    mockLstatMissing(proto, new Path("/no-parent"));

    assertFalse(fs.rename(new Path("/src"), new Path("/no-parent/dst")));
    verifyNoRename();
  }

  /** dst 不存在且其父路径是文件 → false。 */
  @Test
  public void testDstParentIsFileReturnsFalse() throws Exception {
    mockLstat(proto, new Path("/src"), fileStat());
    mockLstatMissing(proto, new Path("/plainfile/dst"));
    mockLstat(proto, new Path("/plainfile"), fileStat());

    assertFalse(fs.rename(new Path("/src"), new Path("/plainfile/dst")));
    verifyNoRename();
  }

  /** 正常改名：dst 不存在、父目录存在 → 调 proto.rename，true。 */
  @Test
  public void testSimpleRenameSucceeds() throws Exception {
    mockLstat(proto, new Path("/a/src"), fileStat());
    mockLstatMissing(proto, new Path("/a/dst"));
    mockLstat(proto, new Path("/a"), dirStat());

    assertTrue(fs.rename(new Path("/a/src"), new Path("/a/dst")));
    verify(proto).rename(eq(new Path("/a/src")), eq(new Path("/a/dst")));
  }

  /** 根下改名（父为根，无须 lstat 根）。 */
  @Test
  public void testRenameAtRootLevel() throws Exception {
    mockLstat(proto, new Path("/src"), fileStat());
    mockLstatMissing(proto, new Path("/dst"));

    assertTrue(fs.rename(new Path("/src"), new Path("/dst")));
    verify(proto).rename(eq(new Path("/src")), eq(new Path("/dst")));
  }

  /** 目录 rename：dst 不存在 → 直接改名。 */
  @Test
  public void testRenameDirectory() throws Exception {
    mockLstat(proto, new Path("/olddir"), dirStat());
    mockLstatMissing(proto, new Path("/newdir"));

    assertTrue(fs.rename(new Path("/olddir"), new Path("/newdir")));
    verify(proto).rename(eq(new Path("/olddir")), eq(new Path("/newdir")));
  }

  /** 目录不可移入自身子树。 */
  @Test
  public void testRenameDirectoryIntoOwnSubtreeReturnsFalse() throws Exception {
    mockLstat(proto, new Path("/a"), dirStat());
    mockLstatMissing(proto, new Path("/a/b/c"));
    mockLstat(proto, new Path("/a/b"), dirStat());

    assertFalse(fs.rename(new Path("/a"), new Path("/a/b/c")));
    verifyNoRename();
  }

  /** 相对路径 src/dst 按 workingDir 解析（§4-7）。 */
  @Test
  public void testRenameRelativePaths() throws Exception {
    fs.setWorkingDirectory(new Path("/base"));
    mockLstat(proto, new Path("/base/s"), fileStat());
    mockLstatMissing(proto, new Path("/base/t"));
    mockLstat(proto, new Path("/base"), dirStat());

    assertTrue(fs.rename(new Path("s"), new Path("t")));
    verify(proto).rename(eq(new Path("/base/s")), eq(new Path("/base/t")));
  }
}
