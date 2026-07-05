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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URI;

import com.ceph.fs.CephFileExtent;
import com.ceph.fs.CephStat;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;

/**
 * T03 mock 单测公共工具（非测试类，surefire 不执行）。
 *
 * <p>{@link CephStat} 的 is_file/is_directory/is_symlink 由 native 侧填充
 * （private 字段），mock 场景用反射构造。
 */
final class CephFsTestHelper {

  private CephFsTestHelper() {
  }

  /** 构造一个已 initialize 的 CephFileSystem（注入 mock proto，无 JNI）。 */
  static CephFileSystem newFs(CephFsProto proto) throws IOException {
    return newFs(proto, new Configuration(false));
  }

  static CephFileSystem newFs(CephFsProto proto, Configuration conf) throws IOException {
    CephFileSystem fs = new CephFileSystem(proto);
    fs.initialize(URI.create("ceph:///"), conf);
    return fs;
  }

  /** 普通文件 stat（默认 mode 0644、uid/gid 0、size 0）。 */
  static CephStat fileStat() {
    return stat(true, false, false);
  }

  /** 目录 stat（默认 mode 0755）。 */
  static CephStat dirStat() {
    CephStat s = stat(false, true, false);
    s.mode = 0755;
    return s;
  }

  static CephStat stat(boolean isFile, boolean isDir, boolean isSymlink) {
    CephStat s = new CephStat();
    setBool(s, "is_file", isFile);
    setBool(s, "is_directory", isDir);
    setBool(s, "is_symlink", isSymlink);
    if (isFile) {
      s.mode = 0644;
    }
    return s;
  }

  private static void setBool(CephStat s, String field, boolean value) {
    try {
      Field f = CephStat.class.getDeclaredField(field);
      f.setAccessible(true);
      f.setBoolean(s, value);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("cannot set CephStat." + field, e);
    }
  }

  /** mock：lstat(path) 成功并把 template 的全部字段拷入出参。 */
  static void mockLstat(CephFsProto proto, Path path, CephStat template)
      throws IOException {
    doAnswer(invocation -> {
      copyStat(template, invocation.getArgument(1));
      return null;
    }).when(proto).lstat(eq(path), any(CephStat.class));
  }

  /** mock：lstat(path) 抛 FileNotFoundException（proto 契约：不存在）。 */
  static void mockLstatMissing(CephFsProto proto, Path path) throws IOException {
    doThrow(new FileNotFoundException(path.toString()))
        .when(proto).lstat(eq(path), any(CephStat.class));
  }

  /**
   * 反射构造 {@link CephFileExtent}（T05）：其构造器为包可见（native 侧填充），
   * mock 场景与 CephStat 同法处理。
   */
  static CephFileExtent extent(long offset, long length, int[] osds) {
    try {
      Constructor<CephFileExtent> ctor = CephFileExtent.class
          .getDeclaredConstructor(long.class, long.class, int[].class);
      ctor.setAccessible(true);
      return ctor.newInstance(offset, length, osds);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("cannot construct CephFileExtent", e);
    }
  }

  static void copyStat(CephStat from, CephStat to) {
    for (Field f : CephStat.class.getDeclaredFields()) {
      try {
        f.setAccessible(true);
        f.set(to, f.get(from));
      } catch (ReflectiveOperationException e) {
        throw new AssertionError("cannot copy CephStat." + f.getName(), e);
      }
    }
  }
}
