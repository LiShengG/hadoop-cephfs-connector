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

import java.io.EOFException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hadoop.fs.FSInputStream;
import org.apache.hadoop.fs.FileSystem;

/**
 * Seekable CephFS input stream backed by one libcephfs file descriptor.
 *
 * <p>The stream keeps a Java-side read buffer and issues positioned reads to
 * CephFS. The native fd position is therefore not part of normal stream state;
 * seek can update the Java position and invalidate the buffer lazily.
 */
class CephInputStream extends FSInputStream {

  private final CephFsProto proto;
  private final int fd;
  private final long fileLength;
  private final byte[] buffer;
  private final FileSystem.Statistics statistics;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private long pos;
  private long bufferStart;
  private int bufferLength;

  CephInputStream(CephFsProto proto, int fd, long fileLength, int bufferSize,
      FileSystem.Statistics statistics) {
    if (bufferSize <= 0) {
      throw new IllegalArgumentException("bufferSize must be positive");
    }
    this.proto = proto;
    this.fd = fd;
    this.fileLength = fileLength;
    this.buffer = new byte[bufferSize];
    this.statistics = statistics;
    this.pos = 0;
    this.bufferStart = 0;
    this.bufferLength = 0;
  }

  @Override
  public synchronized int read() throws IOException {
    byte[] one = new byte[1];
    int n = read(one, 0, 1);
    return n < 0 ? -1 : one[0] & 0xff;
  }

  @Override
  public synchronized int read(byte[] b, int off, int len) throws IOException {
    ensureOpen();
    checkReadArgs(b, off, len);
    if (len == 0) {
      return 0;
    }
    if (pos >= fileLength) {
      return -1;
    }

    int total = 0;
    while (len > 0 && pos < fileLength) {
      if (!hasBufferedByte()) {
        if (!fillBuffer()) {
          break;
        }
      }
      int bufferOffset = (int) (pos - bufferStart);
      int toCopy = Math.min(len, bufferLength - bufferOffset);
      System.arraycopy(buffer, bufferOffset, b, off, toCopy);
      pos += toCopy;
      off += toCopy;
      len -= toCopy;
      total += toCopy;
    }

    if (total > 0 && statistics != null) {
      statistics.incrementBytesRead(total);
    }
    return total == 0 ? -1 : total;
  }

  @Override
  public int read(long position, byte[] b, int off, int len) throws IOException {
    ensureOpen();
    validatePositionedReadArgs(position, b, off, len);
    if (len == 0) {
      return 0;
    }
    if (position >= fileLength) {
      return -1;
    }

    int toRead = (int) Math.min((long) len, fileLength - position);
    byte[] tmp = new byte[toRead];
    long n = proto.read(fd, tmp, toRead, position);
    if (n == 0) {
      return -1;
    }
    if (n < 0 || n > toRead) {
      throw new IOException("Invalid CephFS read result: " + n);
    }
    int read = (int) n;
    System.arraycopy(tmp, 0, b, off, read);
    if (statistics != null) {
      statistics.incrementBytesRead(read);
    }
    return read;
  }

  @Override
  public synchronized void seek(long newPos) throws IOException {
    ensureOpen();
    if (newPos < 0) {
      throw new EOFException("Cannot seek to negative offset " + newPos);
    }
    if (newPos > fileLength) {
      throw new EOFException("Cannot seek past EOF: " + newPos
          + " > " + fileLength);
    }
    if (newPos >= bufferStart && newPos <= bufferStart + bufferLength) {
      pos = newPos;
      return;
    }
    pos = newPos;
    bufferStart = newPos;
    bufferLength = 0;
  }

  @Override
  public synchronized long getPos() throws IOException {
    ensureOpen();
    return pos;
  }

  @Override
  public boolean seekToNewSource(long targetPos) throws IOException {
    ensureOpen();
    return false;
  }

  @Override
  public synchronized int available() throws IOException {
    ensureOpen();
    long remaining = Math.max(0, fileLength - pos);
    return remaining > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
  }

  @Override
  public void close() throws IOException {
    if (closed.getAndSet(true)) {
      return;
    }
    proto.close(fd);
  }

  private boolean hasBufferedByte() {
    return pos >= bufferStart && pos < bufferStart + bufferLength;
  }

  private boolean fillBuffer() throws IOException {
    if (pos >= fileLength) {
      bufferStart = pos;
      bufferLength = 0;
      return false;
    }
    long remaining = fileLength - pos;
    int toRead = (int) Math.min((long) buffer.length, remaining);
    long n = proto.read(fd, buffer, toRead, pos);
    if (n == 0) {
      bufferStart = pos;
      bufferLength = 0;
      return false;
    }
    if (n < 0 || n > toRead) {
      throw new IOException("Invalid CephFS read result: " + n);
    }
    bufferStart = pos;
    bufferLength = (int) n;
    return true;
  }

  private void ensureOpen() throws IOException {
    if (closed.get()) {
      throw new IOException("Stream is closed");
    }
  }

  private static void checkReadArgs(byte[] b, int off, int len) {
    if (b == null) {
      throw new NullPointerException("buffer");
    }
    if ((off | len) < 0 || len > b.length - off) {
      throw new IndexOutOfBoundsException();
    }
  }
}
