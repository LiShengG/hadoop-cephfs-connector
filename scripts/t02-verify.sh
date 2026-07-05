#!/usr/bin/env bash
# T02 验收复现脚本：
#   1) 无集群模式：mvn clean test（单元测试必须绿色，ITest 不参与）
#   2) 集群模式（可选，需 vstart 运行中）：CEPH_CONTRACT_TEST=1 mvn verify
#
# 用法：
#   scripts/t02-verify.sh            # 仅无集群单元测试
#   scripts/t02-verify.sh --with-cluster   # 追加门控集成测试（先确保 scripts/cluster-up.sh 已就绪）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/hadoop-cephfs"

echo "== [1/2] 无集群单元测试: mvn clean test =="
mvn -B clean test

if [[ "${1:-}" == "--with-cluster" ]]; then
  echo "== [2/2] 门控集成测试: CEPH_CONTRACT_TEST=1 mvn verify -Dit.test=ITestCephTalker =="
  CEPH_CONTRACT_TEST=1 mvn -B verify -Dit.test=ITestCephTalker
else
  echo "== [2/2] 跳过集成测试（加 --with-cluster 启用，需 vstart 集群运行中）=="
fi

echo "[t02-verify] PASS"
