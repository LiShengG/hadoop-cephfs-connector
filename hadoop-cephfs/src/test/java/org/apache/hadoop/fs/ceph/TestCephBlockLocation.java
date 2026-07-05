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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.net.InetAddress;

import com.ceph.fs.CephMount;

import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.junit.Before;
import org.junit.Test;

/**
 * T05 mock 单测：extent → BlockLocation 的偏移/长度切分与降级路径。
 *
 * <p>布局模型：objectSize = {@value #OBJ}，单 OSD（osd.0 → 10.0.0.1）。
 * {@code getFileExtent(fd, off)} 按真实 libcephfs 语义 mock：
 * 返回的 length 是从 off 到所在 object 末尾的剩余字节数。
 */
public class TestCephBlockLocation {

  private static final int FD = 7;
  private static final long OBJ = 8; // mock 布局的 object size
  private static final Path FILE = new Path("/f");

  private CephFsProto proto;
  private CephFileSystem fs;

  @Before
  public void setUp() throws Exception {
    proto = mock(CephFsProto.class);
    fs = CephFsTestHelper.newFs(proto);
    doReturn(FD).when(proto).open(eq(FILE), eq(CephMount.O_RDONLY), eq(0));
  }

  /** getFileExtent 按 OBJ 边界返回剩余长度，OSD 恒为 osd.0。 */
  private void mockExtents() throws IOException {
    doAnswer(inv -> {
      long off = inv.getArgument(1);
      return CephFsTestHelper.extent(off, OBJ - (off % OBJ), new int[] { 0 });
    }).when(proto).getFileExtent(eq(FD), anyLong());
    doReturn(InetAddress.getByName("10.0.0.1")).when(proto).getOsdAddress(0);
  }

  private static FileStatus fileStatus(long len) {
    return new FileStatus(len, false, 1, OBJ, 0, FILE);
  }

  private static void assertBlock(BlockLocation b, long offset, long length,
      String... hosts) throws IOException {
    assertEquals("offset", offset, b.getOffset());
    assertEquals("length", length, b.getLength());
    assertArrayEquals("hosts", hosts, b.getHosts());
    assertArrayEquals("names", hosts, b.getNames());
    assertEquals("topologyPaths 留空（无机架感知）", 0, b.getTopologyPaths().length);
  }

  // ── 基类契约分支 ─────────────────────────────────────────────────────

  @Test
  public void testNullFileStatusReturnsNull() throws Exception {
    assertNull(fs.getFileBlockLocations((FileStatus) null, 0, 10));
    verify(proto, never()).open(any(Path.class), anyInt(), anyInt());
  }

  @Test
  public void testNegativeStartOrLenThrows() throws Exception {
    for (long[] args : new long[][] { { -1, 10 }, { 0, -1 } }) {
      try {
        fs.getFileBlockLocations(fileStatus(20), args[0], args[1]);
        fail("expected IllegalArgumentException for start=" + args[0]
            + " len=" + args[1]);
      } catch (IllegalArgumentException expected) {
        // 与基类 FileSystem 契约一致
      }
    }
    verify(proto, never()).open(any(Path.class), anyInt(), anyInt());
  }

  @Test
  public void testStartAtOrBeyondEofReturnsEmpty() throws Exception {
    assertEquals(0, fs.getFileBlockLocations(fileStatus(20), 20, 5).length);
    assertEquals(0, fs.getFileBlockLocations(fileStatus(20), 100, 5).length);
    verify(proto, never()).open(any(Path.class), anyInt(), anyInt());
  }

  @Test
  public void testEmptyFileReturnsEmpty() throws Exception {
    assertEquals(0, fs.getFileBlockLocations(fileStatus(0), 0, 10).length);
    verify(proto, never()).open(any(Path.class), anyInt(), anyInt());
  }

  @Test
  public void testDirectoryReturnsEmpty() throws Exception {
    FileStatus dir = new FileStatus(0, true, 0, 0, 0, FILE);
    assertEquals(0, fs.getFileBlockLocations(dir, 0, 10).length);
    verify(proto, never()).open(any(Path.class), anyInt(), anyInt());
  }

  @Test
  public void testZeroLenReturnsEmpty() throws Exception {
    mockExtents();
    assertEquals(0, fs.getFileBlockLocations(fileStatus(20), 5, 0).length);
  }

  // ── 切分正确性 ───────────────────────────────────────────────────────

  @Test
  public void testWholeFileSplitsOnObjectBoundary() throws Exception {
    mockExtents();
    // 文件 20 字节 = 2 个整 object + 尾块 4 字节（跨对象边界）
    BlockLocation[] blocks = fs.getFileBlockLocations(fileStatus(20), 0, 20);
    assertEquals(3, blocks.length);
    assertBlock(blocks[0], 0, 8, "10.0.0.1");
    assertBlock(blocks[1], 8, 8, "10.0.0.1");
    assertBlock(blocks[2], 16, 4, "10.0.0.1");
    verify(proto).close(FD); // 临时 fd 必须关闭
  }

  @Test
  public void testUnalignedStart() throws Exception {
    mockExtents();
    // start=5 非对齐：首块只到 object 边界 (5,3)，其后 (8,3) 由 end=11 钳制
    BlockLocation[] blocks = fs.getFileBlockLocations(fileStatus(20), 5, 6);
    assertEquals(2, blocks.length);
    assertBlock(blocks[0], 5, 3, "10.0.0.1");
    assertBlock(blocks[1], 8, 3, "10.0.0.1");
    verify(proto).close(FD);
  }

  @Test
  public void testLenBeyondEofClampedToFileLength() throws Exception {
    mockExtents();
    BlockLocation[] blocks = fs.getFileBlockLocations(fileStatus(20), 16, 100);
    assertEquals(1, blocks.length);
    assertBlock(blocks[0], 16, 4, "10.0.0.1");
    verify(proto).close(FD);
  }

  @Test
  public void testLongMaxLenDoesNotOverflow() throws Exception {
    mockExtents();
    BlockLocation[] blocks =
        fs.getFileBlockLocations(fileStatus(20), 0, Long.MAX_VALUE);
    assertEquals(3, blocks.length);
    assertEquals(20, blocks[0].getLength() + blocks[1].getLength()
        + blocks[2].getLength());
  }

  @Test
  public void testSameOsdResolvedOnce() throws Exception {
    mockExtents();
    fs.getFileBlockLocations(fileStatus(20), 0, 20);
    // 3 个 extent 均为 osd.0，调用内缓存 → 只解析一次
    verify(proto, times(1)).getOsdAddress(0);
  }

  // ── 降级路径 ─────────────────────────────────────────────────────────

  @Test
  public void testOsdResolveFailureDegradesToLocalhost() throws Exception {
    mockExtents();
    doThrow(new IOException("osd map query failed")).when(proto).getOsdAddress(0);
    BlockLocation[] blocks = fs.getFileBlockLocations(fileStatus(20), 0, 20);
    // 不抛异常；切分仍按 extent 长度，hosts 降级 localhost
    assertEquals(3, blocks.length);
    assertBlock(blocks[0], 0, 8, "localhost");
    assertBlock(blocks[1], 8, 8, "localhost");
    assertBlock(blocks[2], 16, 4, "localhost");
    verify(proto).close(FD);
  }

  @Test
  public void testEmptyOsdListDegradesToLocalhost() throws Exception {
    doAnswer(inv -> {
      long off = inv.getArgument(1);
      return CephFsTestHelper.extent(off, OBJ - (off % OBJ), new int[0]);
    }).when(proto).getFileExtent(eq(FD), anyLong());
    BlockLocation[] blocks = fs.getFileBlockLocations(fileStatus(10), 0, 10);
    assertEquals(2, blocks.length);
    assertBlock(blocks[0], 0, 8, "localhost");
    assertBlock(blocks[1], 8, 2, "localhost");
  }

  @Test
  public void testGetFileExtentFailureDegradesAndStepsByBlockSize() throws Exception {
    doThrow(new IOException("extent query failed"))
        .when(proto).getFileExtent(eq(FD), anyLong());
    // 无 extent 信息：按 FileStatus.blockSize(=OBJ) 步进，全部降级 localhost
    BlockLocation[] blocks = fs.getFileBlockLocations(fileStatus(20), 0, 20);
    assertEquals(3, blocks.length);
    assertBlock(blocks[0], 0, 8, "localhost");
    assertBlock(blocks[1], 8, 8, "localhost");
    assertBlock(blocks[2], 16, 4, "localhost");
    verify(proto).close(FD);
  }

  @Test
  public void testMixedResolveFailureOnlyDegradesFailedExtent() throws Exception {
    doAnswer(inv -> {
      long off = inv.getArgument(1);
      // 第二个 object 落在 osd.1，其余 osd.0
      int osd = (off >= OBJ && off < 2 * OBJ) ? 1 : 0;
      return CephFsTestHelper.extent(off, OBJ - (off % OBJ), new int[] { osd });
    }).when(proto).getFileExtent(eq(FD), anyLong());
    doReturn(InetAddress.getByName("10.0.0.1")).when(proto).getOsdAddress(0);
    doThrow(new IOException("osd.1 lookup failed")).when(proto).getOsdAddress(1);

    BlockLocation[] blocks = fs.getFileBlockLocations(fileStatus(20), 0, 20);
    assertEquals(3, blocks.length);
    assertBlock(blocks[0], 0, 8, "10.0.0.1");
    assertBlock(blocks[1], 8, 8, "localhost"); // 仅失败的 extent 降级
    assertBlock(blocks[2], 16, 4, "10.0.0.1");
  }

  @Test
  public void testFdClosedWhenUnexpectedErrorPropagates() throws Exception {
    doThrow(new RuntimeException("boom")).when(proto).getFileExtent(eq(FD), anyLong());
    try {
      fs.getFileBlockLocations(fileStatus(20), 0, 20);
      fail("expected RuntimeException");
    } catch (RuntimeException expected) {
      assertTrue(expected.getMessage().contains("boom"));
    }
    verify(proto).close(FD); // finally 兜底关闭
  }
}
