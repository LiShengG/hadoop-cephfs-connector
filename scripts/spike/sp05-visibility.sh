#!/usr/bin/env bash
#
# SP-05：写入中文件的数据可见性（HBase WAL 回放、边写边追读）。
# A 层判定已打开的 reader，B 层判定新打开的 reader —— 后者失败性质严重得多。
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
spike_init

CHUNK="${SPIKE_CHUNK_BYTES:-1048576}"
BASE="$(spike_base visibility)"
log "测试根: $BASE（每段 $CHUNK 字节）"
run_probe SpikeVisibility sp05-visibility "$BASE" "$CHUNK"
cleanup_base "$BASE"
write_report
