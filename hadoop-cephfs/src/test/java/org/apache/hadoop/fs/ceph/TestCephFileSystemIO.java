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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.FileNotFoundException;

import com.ceph.fs.CephMount;
import com.ceph.fs.CephNotDirectoryException;
import com.ceph.fs.CephStat;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.ParentNotDirectoryException;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * CephFileSystem 数据方法 open/create/append 的 mock 单测。
 */
public class TestCephFileSystemIO {

  private CephFsProto proto;
  private CephFileSystem fs;

  @Before
  public void setUp() throws Exception {
    proto = mock(CephFsProto.class);
    Configuration conf = new Configuration(false);
    conf.setInt(CephConfigKeys.CEPH_CLIENT_BUFFER_SIZE_KEY, 8);
    conf.setLong(CephConfigKeys.CEPH_OBJECT_SIZE_KEY, 64);
    fs = newFs(proto, conf);
  }

  @After
  public void tearDown() throws Exception {
    fs.close();
  }

  @Test
  public void testOpenFileUsesReadOnlyFd() throws Exception {
    CephStat stat = fileStat();
    stat.size = 3;
    mockLstat(proto, new Path("/f"), stat);
    when(proto.open(eq(new Path("/f")), eq(CephMount.O_RDONLY), eq(0)))
        .thenReturn(11);
    when(proto.read(eq(11), any(byte[].class), anyLong(), eq(0L))).thenReturn(0L);

    FSDataInputStream in = fs.open(new Path("/f"), 4096);
    assertNotNull(in);
    verify(proto).open(eq(new Path("/f")), eq(CephMount.O_RDONLY), eq(0));
    in.close();
    verify(proto).close(11);
  }

  @Test
  public void testOpenDirectoryThrowsFileNotFound() throws Exception {
    mockLstat(proto, new Path("/d"), dirStat());

    assertThrows(FileNotFoundException.class, () -> fs.open(new Path("/d"), 4096));
    verify(proto, never()).open(any(Path.class), anyInt(), anyInt());
  }

  @Test
  public void testCreateMissingParentMkdirsAndOpensWithLayout() throws Exception {
    Path file = new Path("/parent/f");
    mockLstatMissing(proto, file);
    mockLstatMissing(proto, new Path("/parent"));
    when(proto.open(eq(file), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any()))
        .thenReturn(12);

    FSDataOutputStream out = fs.create(file, new FsPermission((short) 0666),
        false, 4096, (short) 3, 32, null);

    verify(proto).mkdirs(eq(new Path("/parent")), eq(0755));
    verify(proto).open(eq(file),
        eq(CephMount.O_WRONLY | CephMount.O_CREAT | CephMount.O_EXCL),
        eq(0644), eq(32), eq(1), eq(32), eq(null));
    out.close();
  }

  @Test
  public void testCreateWithDataPoolUsesFirstConfiguredPool() throws Exception {
    Configuration conf = new Configuration(false);
    conf.setInt(CephConfigKeys.CEPH_CLIENT_BUFFER_SIZE_KEY, 8);
    conf.setLong(CephConfigKeys.CEPH_OBJECT_SIZE_KEY, 64);
    conf.set(CephConfigKeys.CEPH_DATA_POOLS_KEY, " poolA, poolB ");
    CephFileSystem poolFs = newFs(proto, conf);
    try {
      Path file = new Path("/f");
      mockLstatMissing(proto, file);
      when(proto.open(eq(file), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any()))
          .thenReturn(13);

      FSDataOutputStream out = poolFs.create(file, FsPermission.getFileDefault(),
          false, 4096, (short) 3, 128, null);

      verify(proto).open(eq(file),
          eq(CephMount.O_WRONLY | CephMount.O_CREAT | CephMount.O_EXCL),
          anyInt(), eq(64), eq(1), eq(128), eq("poolA"));
      out.close();
    } finally {
      poolFs.close();
    }
  }

  @Test
  public void testCreateExistingFileWithoutOverwriteThrows() throws Exception {
    mockLstat(proto, new Path("/f"), fileStat());

    assertThrows(FileAlreadyExistsException.class,
        () -> fs.create(new Path("/f"), FsPermission.getFileDefault(),
            false, 4096, (short) 3, 64, null));
    verify(proto, never()).open(any(Path.class), anyInt(), anyInt(), anyInt(),
        anyInt(), anyInt(), any());
  }

  @Test
  public void testCreateExistingDirectoryThrowsEvenWithOverwrite() throws Exception {
    mockLstat(proto, new Path("/d"), dirStat());

    assertThrows(FileAlreadyExistsException.class,
        () -> fs.create(new Path("/d"), FsPermission.getFileDefault(),
            true, 4096, (short) 3, 64, null));
    verify(proto, never()).open(any(Path.class), anyInt(), anyInt(), anyInt(),
        anyInt(), anyInt(), any());
  }

  @Test
  public void testCreateOverwriteExistingFileUsesTruncate() throws Exception {
    mockLstat(proto, new Path("/f"), fileStat());
    when(proto.open(eq(new Path("/f")), anyInt(), anyInt(), anyInt(), anyInt(),
        anyInt(), any())).thenReturn(14);

    FSDataOutputStream out = fs.create(new Path("/f"), FsPermission.getFileDefault(),
        true, 4096, (short) 3, 64, null);

    verify(proto).open(eq(new Path("/f")),
        eq(CephMount.O_WRONLY | CephMount.O_CREAT | CephMount.O_TRUNC),
        anyInt(), eq(64), eq(1), eq(64), eq(null));
    assertEquals(0, out.getPos());
    out.close();
  }

  @Test
  public void testCreateParentIsFileThrowsParentNotDirectory() throws Exception {
    Path file = new Path("/plain/child");
    mockLstatMissing(proto, file);
    mockLstat(proto, new Path("/plain"), fileStat());

    assertThrows(ParentNotDirectoryException.class,
        () -> fs.create(file, FsPermission.getFileDefault(),
            false, 4096, (short) 3, 64, null));
    verify(proto, never()).open(any(Path.class), anyInt(), anyInt(), anyInt(),
        anyInt(), anyInt(), any());
  }

  @Test
  public void testCreateTargetParentNotDirectoryFromLstatIsMapped() throws Exception {
    Path file = new Path("/plain/child");
    doThrow(new CephNotDirectoryException("/plain"))
        .when(proto).lstat(eq(file), any(CephStat.class));

    assertThrows(ParentNotDirectoryException.class,
        () -> fs.create(file, FsPermission.getFileDefault(),
            false, 4096, (short) 3, 64, null));
  }

  @Test
  public void testCreateNonRecursiveMissingParentThrows() throws Exception {
    Path file = new Path("/missing/f");
    mockLstatMissing(proto, file);
    mockLstatMissing(proto, new Path("/missing"));

    assertThrows(FileNotFoundException.class,
        () -> fs.createNonRecursive(file, FsPermission.getFileDefault(),
            false, 4096, (short) 3, 64, null));
    verify(proto, never()).mkdirs(any(Path.class), anyInt());
    verify(proto, never()).open(any(Path.class), anyInt(), anyInt(), anyInt(),
        anyInt(), anyInt(), any());
  }

  @Test
  public void testAppendExistingFileStartsAtFileLength() throws Exception {
    CephStat stat = fileStat();
    stat.size = 123;
    mockLstat(proto, new Path("/f"), stat);
    when(proto.open(eq(new Path("/f")),
        eq(CephMount.O_WRONLY | CephMount.O_APPEND), eq(0))).thenReturn(15);

    FSDataOutputStream out = fs.append(new Path("/f"), 4096, null);

    assertEquals(123, out.getPos());
    verify(proto).open(eq(new Path("/f")),
        eq(CephMount.O_WRONLY | CephMount.O_APPEND), eq(0));
    out.close();
  }

  @Test
  public void testAppendMissingThrowsFileNotFound() throws Exception {
    mockLstatMissing(proto, new Path("/missing"));

    assertThrows(FileNotFoundException.class,
        () -> fs.append(new Path("/missing"), 4096, null));
    verify(proto, never()).open(any(Path.class), anyInt(), anyInt());
  }

  @Test
  public void testAppendDirectoryThrowsFileNotFound() throws Exception {
    mockLstat(proto, new Path("/d"), dirStat());

    assertThrows(FileNotFoundException.class, () -> fs.append(new Path("/d"), 4096, null));
    verify(proto, never()).open(any(Path.class), anyInt(), anyInt());
  }
}
