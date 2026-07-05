#!/usr/bin/env bash
# cluster-down.sh — 停止 vstart Ceph 集群（保留数据，可用 cluster-up.sh 再次启动）
set -euo pipefail

CEPH_BUILD="${CEPH_BUILD:-/home/lsh/code/ceph/build}"
cd "$CEPH_BUILD"

../src/stop.sh
echo "[cluster-down] 集群已停止。"
