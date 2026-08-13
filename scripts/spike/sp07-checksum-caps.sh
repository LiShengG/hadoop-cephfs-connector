#!/usr/bin/env bash
#
# SP-07：getFileChecksum 为 null 对 DistCp 校验的影响（校验被静默跳过而非失败）；
# 附带 hasPathCapability / StreamCapabilities / truncate / concat 等能力面实测清单，
# 用实测值替换 TEST-CASES-ECO.md §2 中的纸面推断。
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
spike_init

BASE="$(spike_base caps)"
log "测试根: $BASE"
run_probe SpikeCapabilities sp07-checksum-caps "$BASE"
cleanup_base "$BASE"
write_report
