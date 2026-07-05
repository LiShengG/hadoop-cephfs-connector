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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.StreamCapabilities;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

/**
 * CephOutputStream mock 单测：写缓冲、flush/sync、capability、close 幂等。
 */
public class TestCephOutputStream {

  private static final int FD = 9;

  private CephFsProto proto;
  private FileSystem.Statistics stats;
  private List<byte[]> writePayloads;
  private List<Long> writeSizes;

  @Before
  public void setUp() throws Exception {
    proto = mock(CephFsProto.class);
    stats = new FileSystem.Statistics("ceph");
    writePayloads = new ArrayList<>();
    writeSizes = new ArrayList<>();
    doAnswer(invocation -> {
      byte[] payload = invocation.getArgument(1);
      long size = invocation.getArgument(2);
      writePayloads.add(Arrays.copyOf(payload, (int) size));
      writeSizes.add(size);
      return size;
    })
        .when(proto).write(eq(FD), any(byte[].class), anyLong(), eq(-1L));
  }

  @Test
  public void testBufferedWriteAcrossBoundary() throws Exception {
    CephOutputStream out = new CephOutputStream(proto, FD, 4, stats);

    out.write(bytes("abc"));
    verify(proto, never()).write(eq(FD), any(byte[].class), anyLong(), eq(-1L));
    assertEquals(3, stats.getBytesWritten());

    out.write(bytes("defg"));
    assertEquals(7, stats.getBytesWritten());
    out.flush();

    verify(proto, times(2)).write(eq(FD), any(byte[].class), anyLong(), eq(-1L));
    assertEquals(Arrays.asList(4L, 3L), writeSizes);
    assertArrayEquals(bytes("abcd"), writePayloads.get(0));
    assertArrayEquals(bytes("efg"), writePayloads.get(1));
  }

  @Test
  public void testHflushFlushesBufferAndFsyncs() throws Exception {
    CephOutputStream out = new CephOutputStream(proto, FD, 4, stats);
    out.write(bytes("abc"));

    out.hflush();

    InOrder order = inOrder(proto);
    order.verify(proto).write(eq(FD), any(byte[].class), eq(3L), eq(-1L));
    order.verify(proto).flush(FD);
  }

  @Test
  public void testHsyncFlushesBufferAndFsyncs() throws Exception {
    CephOutputStream out = new CephOutputStream(proto, FD, 4, stats);
    out.write(bytes("abc"));

    out.hsync();

    InOrder order = inOrder(proto);
    order.verify(proto).write(eq(FD), any(byte[].class), eq(3L), eq(-1L));
    order.verify(proto).flush(FD);
  }

  @Test
  public void testCloseFlushesFsyncsClosesAndIsIdempotent() throws Exception {
    CephOutputStream out = new CephOutputStream(proto, FD, 4, stats);
    out.write(bytes("abc"));

    out.close();
    out.close();

    InOrder order = inOrder(proto);
    order.verify(proto).write(eq(FD), any(byte[].class), eq(3L), eq(-1L));
    order.verify(proto).flush(FD);
    order.verify(proto).close(FD);
    verify(proto, times(1)).close(FD);
  }

  @Test
  public void testCloseStillClosesFdAfterFlushFailure() throws Exception {
    CephOutputStream out = new CephOutputStream(proto, FD, 4, stats);
    out.write(bytes("abc"));
    doAnswer(invocation -> {
      throw new IOException("write failed");
    }).when(proto).write(eq(FD), any(byte[].class), anyLong(), eq(-1L));

    IOException e = assertThrows(IOException.class, out::close);
    assertEquals("write failed", e.getMessage());
    verify(proto).close(FD);
  }

  @Test
  public void testWriteAfterCloseFailsButFlushIsNoop() throws Exception {
    CephOutputStream out = new CephOutputStream(proto, FD, 4, stats);
    out.close();
    out.flush();
    assertThrows(IOException.class, () -> out.write('x'));
    assertThrows(IOException.class, out::hflush);
  }

  @Test
  public void testCapabilities() {
    CephOutputStream out = new CephOutputStream(proto, FD, 4, stats);
    assertTrue(out.hasCapability(StreamCapabilities.HFLUSH));
    assertTrue(out.hasCapability(StreamCapabilities.HSYNC));
    assertTrue(out.hasCapability("HFLUSH"));
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
