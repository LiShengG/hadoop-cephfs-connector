#!/usr/bin/env bash
#
# SP-06：委托 Token 与安全集群适用性。
# A 层（本脚本，非安全集群即可）：确证连接器不提供任何委托 Token。
# B 层（需 Kerberized E3 集群，见文末清单）：确证其对作业提交与 keyring 分发的实际后果。
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
spike_init

BASE="$(spike_base token)"
run_probe SpikeDelegationToken sp06-kerberos "$BASE"
cleanup_base "$BASE"
write_report

cat <<'EOF'

[spike] ── SP-06 B 层清单（需 Kerberized 集群，人工执行并把输出补进 ECO-FINDINGS）──
  1. kinit 后向 YARN 提交一个读写 ceph:// 的作业，记录是否因缺少 Token 失败；
  2. 若成功：确认容器实际用的是哪份 cephx 凭据（节点本地 keyring 路径与权限）；
  3. 验证"另一个 Kerberos 用户在同节点起容器"能否用同一 keyring 取得同等 CephFS 权限
     —— 这是 SP-06 真正要回答的安全边界问题；
  4. 记录 keyring 的分发方式、文件权限、可读用户集合，写入 DEPLOY.md《安全模型与适用边界》。
EOF
