#!/usr/bin/env bash
# cluster-up.sh — 启动（或重建）vstart Ceph 集群并等待 CephFS 就绪
#
# 用法:
#   scripts/cluster-up.sh          # 集群已运行则直接复用；未运行则以既有数据启动
#   scripts/cluster-up.sh -n       # 强制销毁重建全新集群（清空数据）
#
# 就绪判定: `ceph -s` 可达且 health 为 HEALTH_OK/HEALTH_WARN，MDS active，
#           `ceph fs ls` 至少存在一个文件系统。
# 注: vstart 单 OSD 环境下 "pool(s) have no replicas configured" 的
#     HEALTH_WARN 属正常状态。
set -euo pipefail

CEPH_BUILD="${CEPH_BUILD:-/home/lsh/code/ceph/build}"
NEW_FLAG=""
if [[ "${1:-}" == "-n" || "${1:-}" == "--new" ]]; then
  NEW_FLAG="-n"
fi

cd "$CEPH_BUILD"

cluster_ready() {
  local status
  status="$(timeout 10 bin/ceph -s 2>/dev/null)" || return 1
  echo "$status" | grep -qE "health: HEALTH_(OK|WARN)" || return 1
  echo "$status" | grep -qE "mds: .*up" || return 1
  timeout 10 bin/ceph fs ls 2>/dev/null | grep -q "^name:" || return 1
  return 0
}

if [[ -z "$NEW_FLAG" ]] && cluster_ready; then
  echo "[cluster-up] 集群已在运行且就绪，直接复用。"
else
  if [[ -n "$NEW_FLAG" ]]; then
    echo "[cluster-up] 销毁旧集群..."
    ../src/stop.sh >/dev/null 2>&1 || true
  fi
  echo "[cluster-up] 启动 vstart 集群..."
  ../src/vstart.sh -d $NEW_FLAG -x --without-dashboard
fi

echo "[cluster-up] 等待集群健康与 CephFS 就绪（最长 120s）..."
for _ in $(seq 1 60); do
  if cluster_ready; then
    echo "[cluster-up] 就绪:"
    timeout 10 bin/ceph -s 2>/dev/null | grep -E "health:|mon:|mds:|osd:"
    timeout 10 bin/ceph fs ls 2>/dev/null
    exit 0
  fi
  sleep 2
done

echo "[cluster-up] 超时：集群未就绪，请检查 $CEPH_BUILD/out/ 下各守护进程日志" >&2
exit 1
