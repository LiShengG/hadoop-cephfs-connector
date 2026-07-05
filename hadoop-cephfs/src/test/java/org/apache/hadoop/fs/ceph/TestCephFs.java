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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.URI;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.DelegateToFileSystem;
import org.junit.Test;

/**
 * T05：CephFs 结构性单测（无集群）。
 *
 * <p>实例化 CephFs 需要真实 mount（DelegateToFileSystem 构造器内即
 * initialize），全链路验证由门控的 {@code ITestCephFileContext} 覆盖；
 * 本测试仅钉死 AbstractFileSystem 反射协议所需的静态结构。
 */
public class TestCephFs {

  @Test
  public void testExtendsDelegateToFileSystem() {
    assertTrue("CephFs 必须经 DelegateToFileSystem 委托（架构文档 §2）",
        DelegateToFileSystem.class.isAssignableFrom(CephFs.class));
  }

  @Test
  public void testHasUriConfigurationConstructor() throws Exception {
    // AbstractFileSystem.createFileSystem 通过 (URI, Configuration) 构造器反射实例化
    Constructor<CephFs> ctor =
        CephFs.class.getDeclaredConstructor(URI.class, Configuration.class);
    assertNotNull(ctor);
    assertTrue("构造器应为 public，便于直接使用",
        Modifier.isPublic(ctor.getModifiers()));
  }

  @Test
  public void testDefaultPortConstant() {
    assertEquals("Ceph monitor 默认端口", 6789, CephFs.CEPH_DEFAULT_PORT);
  }
}
