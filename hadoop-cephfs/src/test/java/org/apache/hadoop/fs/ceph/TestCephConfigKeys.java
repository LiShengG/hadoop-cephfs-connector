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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.apache.hadoop.conf.Configuration;
import org.junit.Test;

/**
 * 校验 CephConfigKeys 作为键名与默认值权威来源的回归行为，
 * 防止后续改动无意破坏配置兼容性。无需真实集群。
 */
public class TestCephConfigKeys {

  @Test
  public void testKeyNamesFrozenBySpec() {
    assertEquals("ceph", CephConfigKeys.CEPH_URI_SCHEME);
    assertEquals("fs.ceph.impl", CephConfigKeys.FS_CEPH_IMPL_KEY);
    assertEquals("fs.AbstractFileSystem.ceph.impl",
        CephConfigKeys.FS_ABSTRACTFILESYSTEM_CEPH_IMPL_KEY);
    assertEquals("ceph.conf.file", CephConfigKeys.CEPH_CONF_FILE_KEY);
    assertEquals("ceph.conf.options", CephConfigKeys.CEPH_CONF_OPTIONS_KEY);
    assertEquals("ceph.auth.id", CephConfigKeys.CEPH_AUTH_ID_KEY);
    assertEquals("ceph.auth.keyring", CephConfigKeys.CEPH_AUTH_KEYRING_KEY);
    assertEquals("ceph.root.dir", CephConfigKeys.CEPH_ROOT_DIR_KEY);
    assertEquals("ceph.object.size", CephConfigKeys.CEPH_OBJECT_SIZE_KEY);
    assertEquals("ceph.data.pools", CephConfigKeys.CEPH_DATA_POOLS_KEY);
    assertEquals("ceph.localize.reads", CephConfigKeys.CEPH_LOCALIZE_READS_KEY);
    assertEquals("ceph.client.buffer.size", CephConfigKeys.CEPH_CLIENT_BUFFER_SIZE_KEY);
  }

  @Test
  public void testDefaultValuesFrozenBySpec() {
    assertNull(CephConfigKeys.CEPH_CONF_FILE_DEFAULT);
    assertNull(CephConfigKeys.CEPH_CONF_OPTIONS_DEFAULT);
    assertNull(CephConfigKeys.CEPH_AUTH_ID_DEFAULT);
    assertNull(CephConfigKeys.CEPH_AUTH_KEYRING_DEFAULT);
    assertNull(CephConfigKeys.CEPH_DATA_POOLS_DEFAULT);
    assertEquals("/", CephConfigKeys.CEPH_ROOT_DIR_DEFAULT);
    assertEquals(67108864L, CephConfigKeys.CEPH_OBJECT_SIZE_DEFAULT);
    assertTrue(CephConfigKeys.CEPH_LOCALIZE_READS_DEFAULT);
    assertEquals(4194304, CephConfigKeys.CEPH_CLIENT_BUFFER_SIZE_DEFAULT);
  }

  /** 空 Configuration 走默认值路径的取值行为（与 CephTalker.initialize 的用法一致）。 */
  @Test
  public void testDefaultsThroughEmptyConfiguration() {
    Configuration conf = new Configuration(false);
    assertNull(conf.get(CephConfigKeys.CEPH_CONF_FILE_KEY,
        CephConfigKeys.CEPH_CONF_FILE_DEFAULT));
    assertNull(conf.get(CephConfigKeys.CEPH_AUTH_ID_KEY,
        CephConfigKeys.CEPH_AUTH_ID_DEFAULT));
    assertEquals("/", conf.get(CephConfigKeys.CEPH_ROOT_DIR_KEY,
        CephConfigKeys.CEPH_ROOT_DIR_DEFAULT));
    assertEquals(67108864L, conf.getLong(CephConfigKeys.CEPH_OBJECT_SIZE_KEY,
        CephConfigKeys.CEPH_OBJECT_SIZE_DEFAULT));
    assertEquals(4194304, conf.getInt(CephConfigKeys.CEPH_CLIENT_BUFFER_SIZE_KEY,
        CephConfigKeys.CEPH_CLIENT_BUFFER_SIZE_DEFAULT));
    assertTrue(conf.getBoolean(CephConfigKeys.CEPH_LOCALIZE_READS_KEY,
        CephConfigKeys.CEPH_LOCALIZE_READS_DEFAULT));
  }
}
