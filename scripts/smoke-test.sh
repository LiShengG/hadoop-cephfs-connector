#!/usr/bin/env bash
# smoke-test.sh — 一键编译并运行 T01 smoke test（CephSmoke.java）
#
# 前置: vstart 集群已运行（scripts/cluster-up.sh），dist/native/ 下有
#       libcephfs.jar 与 libcephfs_jni.so。
#
# 用法: scripts/smoke-test.sh
set -euo pipefail

PROJ_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CEPH_BUILD="${CEPH_BUILD:-/home/lsh/code/ceph/build}"

NATIVE_DIR="$PROJ_ROOT/dist/native"
JAR="$NATIVE_DIR/libcephfs.jar"
JNI_SO="$NATIVE_DIR/libcephfs_jni.so"
CEPH_CONF="$CEPH_BUILD/ceph.conf"
AUTH_ID="admin"
SMOKE_DIR="$PROJ_ROOT/scripts/smoke"

for f in "$JAR" "$JNI_SO" "$CEPH_CONF"; do
  if [[ ! -e "$f" ]]; then
    echo "[smoke-test] 缺少前置文件: $f" >&2
    exit 1
  fi
done

echo "[smoke-test] 编译 CephSmoke.java ..."
javac -cp "$JAR" -d "$SMOKE_DIR" "$SMOKE_DIR/CephSmoke.java"

echo "[smoke-test] 运行 smoke test ..."
# libcephfs_jni.so 依赖 libcephfs.so/libceph-common.so，须让动态链接器能找到
export LD_LIBRARY_PATH="$CEPH_BUILD/lib:${LD_LIBRARY_PATH:-}"
java -cp "$JAR:$SMOKE_DIR" \
     -Djava.library.path="$NATIVE_DIR:$CEPH_BUILD/lib" \
     CephSmoke "$CEPH_CONF" "$AUTH_ID"

echo "[smoke-test] 退出码 0，smoke test 通过。"
