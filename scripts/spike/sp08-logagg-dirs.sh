#!/usr/bin/env bash
#
# SP-08：YARN 日志聚合远程目录准备序列（复刻 LogAggregationFileController 的动作）。
# 关注点是"权限能否落到 01777/0770"与"setOwner 静默无效导致 YARN 既不降级也不报错"。
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
spike_init

# 目标用户/组取系统上真实存在、且不同于当前用户的账号：
# 若 setOwner 真的生效，属主会可见地变化；相同则说明静默无效（判据要点见探针注释）
TARGET_USER="${SPIKE_NAMED_USER:-}"
TARGET_GROUP="${SPIKE_NAMED_GROUP:-}"
if [ -z "$TARGET_USER" ]; then
  read -r TARGET_USER TARGET_GROUP < <(getent passwd \
    | awk -F: -v me="$(id -u)" '$3 != me && $3 > 0 && $3 < 65534 {print $1, $4; exit}' \
    | while read -r u g; do echo "$u $(getent group "$g" | cut -d: -f1)"; done)
fi
[ -n "${TARGET_USER:-}" ] || die "未找到可用的目标账号，请设置 SPIKE_NAMED_USER/SPIKE_NAMED_GROUP"
TARGET_GROUP="${TARGET_GROUP:-$TARGET_USER}"

BASE="$(spike_base logagg)"
log "测试根: $BASE（setOwner 目标 $TARGET_USER:$TARGET_GROUP）"
run_probe SpikeLogAggDirs sp08-logagg-dirs "$BASE" "$TARGET_USER" "$TARGET_GROUP"
cleanup_base "$BASE"
write_report
