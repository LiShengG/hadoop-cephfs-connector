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

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.StreamCapabilities;
import org.apache.hadoop.fs.Syncable;

/**
 * Buffered CephFS output stream backed by one libcephfs file descriptor.
 */
class CephOutputStream extends OutputStream implements Syncable, StreamCapabilities {

  private final CephFsProto proto;
  private final int fd;
  private final byte[] buffer;
  private final FileSystem.Statistics statistics;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private int bufferLength;

  CephOutputStream(CephFsProto proto, int fd, int bufferSize,
      FileSystem.Statistics statistics) {
    if (bufferSize <= 0) {
      throw new IllegalArgumentException("bufferSize must be positive");
    }
    this.proto = proto;
    this.fd = fd;
    this.buffer = new byte[bufferSize];
    this.statistics = statistics;
  }

  @Override
  public synchronized void write(int b) throws IOException {
    ensureOpen();
    if (bufferLength == buffer.length) {
      flushBuffer();
    }
    buffer[bufferLength++] = (byte) b;
    incrementBytesWritten(1);
  }

  @Override
  public synchronized void write(byte[] b, int off, int len) throws IOException {
    ensureOpen();
    checkWriteArgs(b, off, len);
    if (len == 0) {
      return;
    }

    int originalLen = len;
    while (len > 0) {
      if (bufferLength == 0 && len >= buffer.length) {
        writeFully(b, off, len);
        incrementBytesWritten(originalLen);
        return;
      }

      int space = buffer.length - bufferLength;
      int toCopy = Math.min(space, len);
      System.arraycopy(b, off, buffer, bufferLength, toCopy);
      bufferLength += toCopy;
      off += toCopy;
      len -= toCopy;

      if (bufferLength == buffer.length) {
        flushBuffer();
      }
    }
    incrementBytesWritten(originalLen);
  }

  @Override
  public synchronized void flush() throws IOException {
    if (closed.get()) {
      return;
    }
    flushBuffer();
  }

  @Override
  public synchronized void hflush() throws IOException {
    hsync();
  }

  @Override
  public synchronized void hsync() throws IOException {
    ensureOpen();
    flushBuffer();
    proto.flush(fd);
  }

  @Override
  public boolean hasCapability(String capability) {
    if (capability == null) {
      return false;
    }
    String c = capability.toLowerCase(Locale.ENGLISH);
    return StreamCapabilities.HFLUSH.equals(c)
        || StreamCapabilities.HSYNC.equals(c);
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed.getAndSet(true)) {
      return;
    }

    IOException failure = null;
    try {
      flushBuffer();
      proto.flush(fd);
    } catch (IOException e) {
      failure = e;
    } finally {
      try {
        proto.close(fd);
      } catch (IOException e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  private void flushBuffer() throws IOException {
    if (bufferLength == 0) {
      return;
    }
    writeFully(buffer, 0, bufferLength);
    bufferLength = 0;
  }

  private void writeFully(byte[] b, int off, int len) throws IOException {
    int cursor = off;
    int remaining = len;
    while (remaining > 0) {
      byte[] chunk = (cursor == 0 && remaining == b.length)
          ? b : Arrays.copyOfRange(b, cursor, cursor + remaining);
      long n = proto.write(fd, chunk, remaining, -1);
      if (n <= 0 || n > remaining) {
        throw new IOException("Invalid CephFS write result: " + n);
      }
      cursor += (int) n;
      remaining -= (int) n;
    }
  }

  private void ensureOpen() throws IOException {
    if (closed.get()) {
      throw new IOException("Stream is closed");
    }
  }

  private void incrementBytesWritten(long n) {
    if (statistics != null) {
      statistics.incrementBytesWritten(n);
    }
  }

  private static void checkWriteArgs(byte[] b, int off, int len) {
    if (b == null) {
      throw new NullPointerException("buffer");
    }
    if ((off | len) < 0 || len > b.length - off) {
      throw new IndexOutOfBoundsException();
    }
  }
}
