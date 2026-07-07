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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * 并发冒烟（T06 工作内容 5）：4 线程共享同一个 {@link CephFileSystem}
 * （即共享一个 CephMount），各自写/读独立文件并做 md5 比对，
 * 验证共享 mount 无死锁、无数据错乱、无 fd 泄漏。
 *
 * <p>门控：{@code CEPH_CONTRACT_TEST=1}；集群约定见 docs/ENV.md。
 */
public class ITestCephConcurrentIO {

  private static final String DEFAULT_CEPH_CONF = "/home/lsh/code/ceph/build/ceph.conf";
  private static final String DEFAULT_AUTH_ID = "admin";

  private static final int THREADS = 4;
  private static final int FILE_SIZE = 8 * 1024 * 1024; // 8MB / 线程
  private static final int TIMEOUT_SECONDS = 300;       // 死锁兜底

  private FileSystem fs;
  private Path base;

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

    Configuration conf = new Configuration();
    conf.set(CephConfigKeys.CEPH_CONF_FILE_KEY, cephConfFile());
    conf.set(CephConfigKeys.CEPH_AUTH_ID_KEY, DEFAULT_AUTH_ID);
    conf.setBoolean("fs.ceph.impl.disable.cache", true);
    fs = FileSystem.get(URI.create("ceph:///"), conf);
    base = new Path("/itest-t06-concurrent-" + System.nanoTime());
    assertTrue(fs.mkdirs(base));
  }

  @After
  public void tearDown() throws Exception {
    if (fs != null) {
      try {
        fs.delete(base, true);
      } catch (IOException ignored) {
        // 兜底清理
      }
      fs.close();
      fs = null;
    }
  }

  @Test
  public void testConcurrentWriteReadIndependentFiles() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    try {
      List<Future<Void>> results = new ArrayList<>();
      for (int i = 0; i < THREADS; i++) {
        final int id = i;
        results.add(pool.submit(new Callable<Void>() {
          @Override
          public Void call() throws Exception {
            Path file = new Path(base, "worker-" + id + ".bin");
            byte[] data = new byte[FILE_SIZE];
            new Random(20260705L + id).nextBytes(data);
            byte[] expectedMd5 = MessageDigest.getInstance("MD5").digest(data);

            // 写（跨多个 4MB 缓冲，中途 hflush 一次）
            try (FSDataOutputStream out = fs.create(file, true)) {
              out.write(data, 0, FILE_SIZE / 2);
              out.hflush();
              out.write(data, FILE_SIZE / 2, FILE_SIZE - FILE_SIZE / 2);
            }

            // 顺序读 md5 比对
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[128 * 1024];
            try (FSDataInputStream in = fs.open(file)) {
              int n;
              while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
              }
            }
            assertArrayEquals("thread " + id + " md5 mismatch (sequential read)",
                expectedMd5, md.digest());

            // 随机 positioned read 比对
            try (FSDataInputStream in = fs.open(file)) {
              Random r = new Random(97L + id);
              for (int k = 0; k < 20; k++) {
                int off = r.nextInt(FILE_SIZE - 4096);
                byte[] got = new byte[4096];
                in.readFully(off, got);
                for (int j = 0; j < got.length; j++) {
                  if (got[j] != data[off + j]) {
                    throw new AssertionError("thread " + id
                        + " pread mismatch at offset " + (off + j));
                  }
                }
              }
            }
            return null;
          }
        }));
      }
      for (Future<Void> f : results) {
        f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS); // 超时视为死锁/挂起
      }
    } finally {
      pool.shutdownNow();
    }

    // 全部流关闭后无 fd 泄漏（T04 引入的包内计数器）
    assertEquals("open fd count after concurrent smoke", 0,
        ((CephFileSystem) fs).openFileDescriptorCountForTests());

    // 4 个文件都在且长度正确
    assertEquals(THREADS, fs.listStatus(base).length);
    for (int i = 0; i < THREADS; i++) {
      assertEquals(FILE_SIZE,
          fs.getFileStatus(new Path(base, "worker-" + i + ".bin")).getLen());
    }
  }
}
