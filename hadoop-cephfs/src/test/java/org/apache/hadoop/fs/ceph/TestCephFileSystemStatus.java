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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ceph.fs.CephStat;
import com.ceph.fs.CephStatVFS;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.FsServerDefaults;
import org.apache.hadoop.fs.FsStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.DataChecksum;
import org.junit.Before;
import org.junit.Test;

/**
 * T05 mock 单测：收尾小方法 —— getStatus(statfs→FsStatus)、getServerDefaults、
 * getDefaultBlockSize(Path)、getContentSummary（基类默认实现可用性确认）。
 */
public class TestCephFileSystemStatus {

  private CephFsProto proto;
  private CephFileSystem fs;
  private Configuration conf;

  @Before
  public void setUp() throws Exception {
    proto = mock(CephFsProto.class);
    conf = new Configuration(false);
    conf.setLong(CephConfigKeys.CEPH_OBJECT_SIZE_KEY, 8L * 1024 * 1024);
    conf.setInt(CephConfigKeys.CEPH_REPLICATION_KEY, 2);
    conf.setInt(CephConfigKeys.CEPH_CLIENT_BUFFER_SIZE_KEY, 1 * 1024 * 1024);
    fs = CephFsTestHelper.newFs(proto, conf);
  }

  private static CephStatVFS statVfs(long blocks, long bavail, long frsize) {
    CephStatVFS s = new CephStatVFS();
    s.blocks = blocks;
    s.bavail = bavail;
    s.frsize = frsize;
    s.bsize = frsize;
    return s;
  }

  @Test
  public void testGetStatusMapsStatfs() throws Exception {
    doReturn(statVfs(1000, 400, 4096)).when(proto).statfs(new Path("/"));
    FsStatus status = fs.getStatus();
    assertEquals("capacity", 1000L * 4096, status.getCapacity());
    assertEquals("remaining", 400L * 4096, status.getRemaining());
    assertEquals("used", 600L * 4096, status.getUsed());
    // p=null → 用根路径 statfs（CephFS 单一分区）
    verify(proto).statfs(new Path("/"));
  }

  @Test
  public void testGetStatusWithPath() throws Exception {
    Path dir = new Path("/data");
    doReturn(statVfs(10, 5, 1024)).when(proto).statfs(dir);
    FsStatus status = fs.getStatus(dir);
    assertEquals(10 * 1024L, status.getCapacity());
    assertEquals(5 * 1024L, status.getRemaining());
    assertEquals(5 * 1024L, status.getUsed());
  }

  @Test
  public void testGetStatusZeroFrsizeFallsBackToBsize() throws Exception {
    CephStatVFS s = statVfs(100, 50, 0);
    s.bsize = 512;
    doReturn(s).when(proto).statfs(new Path("/"));
    assertEquals(100 * 512L, fs.getStatus().getCapacity());
  }

  @Test
  public void testGetDefaultBlockSizeWithPath() {
    assertEquals(8L * 1024 * 1024, fs.getDefaultBlockSize(new Path("/any")));
    assertEquals(fs.getDefaultBlockSize(), fs.getDefaultBlockSize(new Path("/any")));
  }

  @SuppressWarnings("deprecation")
  @Test
  public void testGetServerDefaults() throws Exception {
    for (FsServerDefaults d : new FsServerDefaults[] {
        fs.getServerDefaults(), fs.getServerDefaults(new Path("/x")) }) {
      assertEquals("blockSize=ceph.object.size", 8L * 1024 * 1024, d.getBlockSize());
      assertEquals("replication=ceph.replication", 2, d.getReplication());
      assertEquals("fileBufferSize=ceph.client.buffer.size",
          1024 * 1024, d.getFileBufferSize());
      assertEquals("CephFS 无客户端校验和", DataChecksum.Type.NULL, d.getChecksumType());
      assertFalse(d.getEncryptDataTransfer());
    }
  }

  @Test
  public void testGetContentSummaryUsesBaseImplementation() throws Exception {
    // 目录 /d 下两个文件（10 + 5 字节）：基类默认实现应汇总正确
    Path d = new Path("/d");
    CephFsTestHelper.mockLstat(proto, d, CephFsTestHelper.dirStat());
    doReturn(new String[] { "a", "b" }).when(proto).listdir(d);

    CephStat fileA = CephFsTestHelper.fileStat();
    fileA.size = 10;
    CephFsTestHelper.mockLstat(proto, new Path("/d/a"), fileA);
    CephStat fileB = CephFsTestHelper.fileStat();
    fileB.size = 5;
    CephFsTestHelper.mockLstat(proto, new Path("/d/b"), fileB);

    ContentSummary summary = fs.getContentSummary(d);
    assertEquals("length", 15, summary.getLength());
    assertEquals("fileCount", 2, summary.getFileCount());
    assertEquals("directoryCount", 1, summary.getDirectoryCount());
  }
}
