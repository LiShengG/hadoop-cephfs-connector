#!/usr/bin/env bash
#
# T07 一键打包：从干净 mvn clean 状态产出部署包
#   dist/hadoop-cephfs-<version>.tar.gz
#     hadoop-cephfs-<version>/
#       hadoop-cephfs-<version>.jar   连接器（不含 Hadoop、不含 libcephfs）
#       libcephfs.jar                 Ceph 官方 Java 绑定（与 jni so 同源构建）
#       libcephfs_jni.so              JNI 原生库（重建方法见 docs/ENV.md §2）
#       conf/core-site.xml.example    全部配置键的注释样例
#       README.md                     快速安装说明
#       docs/DEPLOY.md                完整部署文档
#
# libcephfs.jar 不 shade 进连接器 jar、并列发布，理由：
#   1. libcephfs.jar (LGPL-2.1) 与连接器 (Apache-2.0) 许可不同，合入会污染许可；
#   2. jar 与 libcephfs_jni.so 必须同源同版本（JNI 符号一一对应），独立成件
#      才能随 Ceph 客户端升级整体替换；
#   3. 节点上若已部署 Ceph 自带的 libcephfs.jar，shade 副本会造成同名类冲突。
#
# 用法：scripts/make-dist.sh            # mvn clean package（跑全部无集群单测）
#       SKIP_TESTS=1 scripts/make-dist.sh  # 跳过单测（仅打包验证时用）
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ_ROOT="$(dirname "$SCRIPT_DIR")"
MVN_PROJ="$PROJ_ROOT/hadoop-cephfs"
NATIVE_DIR="$PROJ_ROOT/dist/native"

log() { echo "[make-dist] $*"; }
die() { echo "[make-dist] FAIL: $*" >&2; exit 1; }

# ── 0. 前置检查 ─────────────────────────────────────────────────────────────
# 项目 <version> 是 pom.xml 中第一个 <version> 元素
VERSION="$(grep -m1 -oP '(?<=<version>)[^<]+' "$MVN_PROJ/pom.xml")"
[ -n "$VERSION" ] || die "无法从 pom.xml 解析版本号"
log "版本: $VERSION"

[ -f "$NATIVE_DIR/libcephfs.jar" ]    || die "缺 $NATIVE_DIR/libcephfs.jar（重建见 docs/ENV.md §2）"
[ -f "$NATIVE_DIR/libcephfs_jni.so" ] || die "缺 $NATIVE_DIR/libcephfs_jni.so（重建见 docs/ENV.md §2）"
[ -f "$PROJ_ROOT/conf/core-site.xml.example" ] || die "缺 conf/core-site.xml.example"
[ -f "$PROJ_ROOT/docs/DEPLOY.md" ]    || die "缺 docs/DEPLOY.md"

# ── 1. 从干净状态构建 ───────────────────────────────────────────────────────
if [ "${SKIP_TESTS:-0}" = "1" ]; then
  log "mvn clean package -DskipTests ..."
  ( cd "$MVN_PROJ" && mvn -q clean package -DskipTests ) || die "mvn package 失败"
else
  log "mvn clean package（含全部无集群单测）..."
  ( cd "$MVN_PROJ" && mvn -q clean package ) || die "mvn clean package 失败（单测未通过或构建错误）"
fi

JAR="$MVN_PROJ/target/hadoop-cephfs-$VERSION.jar"
[ -f "$JAR" ] || die "预期产物不存在: $JAR"

# ── 2. 组装发布目录 ─────────────────────────────────────────────────────────
STAGE_NAME="hadoop-cephfs-$VERSION"
STAGE="$PROJ_ROOT/dist/$STAGE_NAME"
rm -rf "$STAGE"
mkdir -p "$STAGE/conf" "$STAGE/docs"

cp "$JAR"                                   "$STAGE/"
cp "$NATIVE_DIR/libcephfs.jar"              "$STAGE/"
cp "$NATIVE_DIR/libcephfs_jni.so"           "$STAGE/"
cp "$PROJ_ROOT/conf/core-site.xml.example"  "$STAGE/conf/"
cp "$PROJ_ROOT/docs/DEPLOY.md"              "$STAGE/docs/"

cat > "$STAGE/README.md" <<EOF
# Hadoop CephFS 连接器 $VERSION 部署包

为 Hadoop 3.3.6 提供 CephFS (Ceph 16.2.x Pacific) 后端：\`ceph://\` URI 直接读写
CephFS，支持设为 fs.defaultFS。完整安装/配置/排错文档见 **docs/DEPLOY.md**。

## 包内容

| 文件 | 说明 |
|---|---|
| hadoop-cephfs-$VERSION.jar | 连接器（org.apache.hadoop.fs.ceph.CephFileSystem / CephFs） |
| libcephfs.jar | Ceph 官方 Java 绑定（com.ceph.fs.CephMount），LGPL-2.1 |
| libcephfs_jni.so | Java 绑定的 JNI 原生库，须与 libcephfs.jar 同版本使用 |
| conf/core-site.xml.example | 全部配置键的注释样例（vstart 与生产两组示例值） |
| docs/DEPLOY.md | 完整部署文档（安装、配置、cephx 权限、验证、故障排查） |

连接器 jar 不内嵌 Hadoop（provided 依赖），也不内嵌 libcephfs.jar——
后者与 libcephfs_jni.so 必须同源同版本、许可为 LGPL-2.1，且节点可能已随
Ceph 部署过同类 jar，故并列发布以便独立升级、避免类冲突（详见 DEPLOY.md §2.1）。

## 快速安装（5 步，详见 docs/DEPLOY.md）

1. 前提：Java 8+、Hadoop 3.3.6、Ceph 客户端库 libcephfs.so.2（16.2.x）；
2. 两个 jar 放入 \`\$HADOOP_HOME/share/hadoop/common/lib/\`（或加入 HADOOP_CLASSPATH）；
3. libcephfs_jni.so 所在目录配置进 java.library.path，其依赖库目录配置进
   LD_LIBRARY_PATH（三种方式见 DEPLOY.md §2.3，二者缺一不可）；
4. 按 conf/core-site.xml.example 把 5 个必配项合并进 core-site.xml
   （fs.AbstractFileSystem.ceph.impl 必须显式配置，无自动发现）；
5. 验证：\`hadoop fs -ls ceph:///\`。
EOF

# ── 3. 打 tar.gz ────────────────────────────────────────────────────────────
TARBALL="$PROJ_ROOT/dist/$STAGE_NAME.tar.gz"
rm -f "$TARBALL"
tar czf "$TARBALL" -C "$PROJ_ROOT/dist" "$STAGE_NAME"

log "产物: $TARBALL"
log "内容:"
tar tzf "$TARBALL" | sed 's/^/[make-dist]   /'
log "sha256: $(sha256sum "$TARBALL" | awk '{print $1}')"
log "PASS: 打包完成"
