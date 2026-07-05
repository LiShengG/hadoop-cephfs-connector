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
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.hadoop.fs.FileSystem;
import org.junit.Before;
import org.junit.Test;

/**
 * CephInputStream mock 单测：缓冲、seek、EOF、关闭后语义与统计。
 */
public class TestCephInputStream {

  private static final int FD = 7;

  private CephFsProto proto;
  private FileSystem.Statistics stats;

  @Before
  public void setUp() {
    proto = mock(CephFsProto.class);
    stats = new FileSystem.Statistics("ceph");
  }

  @Test
  public void testReadThroughBufferAndEof() throws Exception {
    byte[] data = bytes("abcdef");
    mockReads(data);
    CephInputStream in = new CephInputStream(proto, FD, data.length, 4, stats);

    byte[] out = new byte[data.length];
    assertEquals(data.length, in.read(out, 0, out.length));
    assertArrayEquals(data, out);
    assertEquals(data.length, in.getPos());
    assertEquals(-1, in.read());
    assertEquals(data.length, stats.getBytesRead());
    verify(proto).read(eq(FD), any(byte[].class), eq(4L), eq(0L));
    verify(proto).read(eq(FD), any(byte[].class), eq(2L), eq(4L));
  }

  @Test
  public void testSeekWithinBufferDoesNotReadAgain() throws Exception {
    byte[] data = bytes("abcdef");
    mockReads(data);
    CephInputStream in = new CephInputStream(proto, FD, data.length, 4, stats);

    byte[] first = new byte[2];
    assertEquals(2, in.read(first, 0, first.length));
    in.seek(1);
    assertEquals('b', in.read());
    assertEquals(2, in.getPos());
    assertEquals(3, stats.getBytesRead());
    verify(proto, times(1)).read(eq(FD), any(byte[].class), anyLong(), anyLong());
  }

  @Test
  public void testSeekOutsideBufferInvalidatesBuffer() throws Exception {
    byte[] data = bytes("abcdef");
    mockReads(data);
    CephInputStream in = new CephInputStream(proto, FD, data.length, 4, stats);

    assertEquals('a', in.read());
    in.seek(5);
    assertEquals('f', in.read());
    assertEquals(6, in.getPos());
    verify(proto).read(eq(FD), any(byte[].class), eq(4L), eq(0L));
    verify(proto).read(eq(FD), any(byte[].class), eq(1L), eq(5L));
  }

  @Test
  public void testPositionedReadDoesNotMoveStreamPosition() throws Exception {
    byte[] data = bytes("abcdef");
    mockReads(data);
    CephInputStream in = new CephInputStream(proto, FD, data.length, 4, stats);

    byte[] first = new byte[2];
    assertEquals(2, in.read(first, 0, first.length));
    byte[] pread = new byte[2];
    assertEquals(2, in.read(4, pread, 0, pread.length));
    assertArrayEquals(bytes("ef"), pread);
    assertEquals(2, in.getPos());
  }

  @Test
  public void testAvailableAndSeekToNewSource() throws Exception {
    byte[] data = bytes("abcdef");
    mockReads(data);
    CephInputStream in = new CephInputStream(proto, FD, data.length, 4, stats);

    assertEquals(6, in.available());
    in.seek(3);
    assertEquals(3, in.available());
    assertFalse(in.seekToNewSource(3));
  }

  @Test
  public void testSeekPastEofRejected() throws Exception {
    byte[] data = bytes("abcdef");
    mockReads(data);
    CephInputStream in = new CephInputStream(proto, FD, data.length, 4, stats);

    assertThrows(EOFException.class, () -> in.seek(-1));
    assertThrows(EOFException.class, () -> in.seek(data.length + 1L));
  }

  @Test
  public void testCloseIdempotentAndReadAfterCloseFails() throws Exception {
    byte[] data = bytes("abcdef");
    mockReads(data);
    CephInputStream in = new CephInputStream(proto, FD, data.length, 4, stats);

    in.close();
    in.close();
    verify(proto, times(1)).close(FD);
    assertThrows(IOException.class, () -> in.read());
    assertThrows(IOException.class, () -> in.seek(0));
  }

  private void mockReads(byte[] data) throws Exception {
    doAnswer(invocation -> {
      byte[] dest = invocation.getArgument(1);
      long size = invocation.getArgument(2);
      long offset = invocation.getArgument(3);
      if (offset >= data.length) {
        return 0L;
      }
      int n = (int) Math.min(size, data.length - offset);
      System.arraycopy(data, (int) offset, dest, 0, n);
      return (long) n;
    }).when(proto).read(eq(FD), any(byte[].class), anyLong(), anyLong());
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
