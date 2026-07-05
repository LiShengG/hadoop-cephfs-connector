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

import org.apache.hadoop.fs.Path;
import org.junit.Test;

/**
 * CephTalker.pathString（Hadoop Path → CephFS 绝对路径的唯一转换入口）
 * 的边界用例。无需真实集群。
 */
public class TestPathTranslation {

  @Test
  public void testRootPath() {
    assertEquals("/", CephTalker.pathString(new Path("/")));
  }

  @Test
  public void testNullPathIsRoot() {
    assertEquals("/", CephTalker.pathString(null));
  }

  @Test
  public void testSimpleAbsolutePath() {
    assertEquals("/a/b/c.txt", CephTalker.pathString(new Path("/a/b/c.txt")));
  }

  @Test
  public void testSchemeAndAuthorityStripped() {
    assertEquals("/x/y", CephTalker.pathString(new Path("ceph://mon0:6789/x/y")));
  }

  @Test
  public void testSchemeWithoutAuthorityStripped() {
    assertEquals("/x", CephTalker.pathString(new Path("ceph:///x")));
  }

  @Test
  public void testSchemeAuthorityRootIsRoot() {
    assertEquals("/", CephTalker.pathString(new Path("ceph://mon0:6789/")));
  }

  @Test
  public void testRelativePathResolvedAgainstRoot() {
    // 连接器不使用 ceph_chdir，libcephfs 侧 cwd 恒为根；
    // workingDir 解析由上层 CephFileSystem 完成，落到 Talker 的相对路径按根解析。
    assertEquals("/foo/bar", CephTalker.pathString(new Path("foo/bar")));
  }

  @Test
  public void testPathWithSpaces() {
    assertEquals("/a dir/file 1.bin",
        CephTalker.pathString(new Path("/a dir/file 1.bin")));
  }

  @Test
  public void testQualifiedPathWithSpaces() {
    assertEquals("/p q/r s",
        CephTalker.pathString(new Path("ceph://mon0:6789/p q/r s")));
  }

  @Test
  public void testTrailingSlashNormalized() {
    // Path 构造时即归一化尾部斜杠
    assertEquals("/a/b", CephTalker.pathString(new Path("/a/b/")));
  }

  @Test
  public void testDuplicateSlashesNormalized() {
    assertEquals("/a/b", CephTalker.pathString(new Path("/a//b")));
  }

  @Test
  public void testParentChildConstructor() {
    assertEquals("/dir/child",
        CephTalker.pathString(new Path(new Path("ceph://mon0:6789/dir"), "child")));
  }
}
