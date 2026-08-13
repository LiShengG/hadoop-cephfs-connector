#!/usr/bin/env bash
#
# SP-01 / SP-02 / SP-03：属主与组的字符串表示对生态的影响。
#   SP-01 MR staging 目录属主校验（JobSubmissionFiles）
#   SP-02 基类 access() 的组鉴权
#   SP-03 setOwner(用户名, 组名) 静默无效
# 仅需 vstart 集群（A 层）；完整的 MR 提交验证见 sp01b-mr-submit.sh（B 层）。
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
spike_init

MY_UID="$(id -u)"; MY_GID="$(id -g)"; MY_GROUP="$(id -gn)"

# 构造用的 uid 必须是**系统上真实存在的账号**：名字型 FS 会报出用户名，
# 连接器只能报数字 —— 这才构成有效判据。随便挑无账号的 uid（如 4242）时
# 连本地 FS 也只能报数字，判据失效（控制组实测踩过这个假阳性）。
NAMED_UID="${SPIKE_NAMED_UID:-}"
NAMED_USER="${SPIKE_NAMED_USER:-}"
if [ -z "$NAMED_UID" ]; then
  read -r NAMED_USER NAMED_UID < <(getent passwd \
    | awk -F: -v me="$MY_UID" '$3 != me && $3 > 0 && $3 < 65534 {print $1, $3; exit}')
fi
[ -n "${NAMED_UID:-}" ] && [ -n "${NAMED_USER:-}" ] \
  || die "未能在本机找到可用于构造的系统账号，请显式设置 SPIKE_NAMED_UID/SPIKE_NAMED_USER"

BASE="$(spike_base ownergroup)"
log "测试根: $BASE"
log "当前 uid=$MY_UID gid=$MY_GID 主组=$MY_GROUP；构造用账号=$NAMED_USER(uid $NAMED_UID)"
run_probe SpikeOwnerGroup sp01-owner-group "$BASE" "$NAMED_UID" "$NAMED_USER" "$MY_GID" "$MY_GROUP"
cleanup_base "$BASE"
write_report
