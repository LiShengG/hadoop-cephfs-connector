#!/usr/bin/env bash
#
# SP-04：FileContext 的 rename 语义（Spark Structured Streaming checkpoint、
# Delta/Iceberg 的原子创建依赖它）。分非并发（A）与并发竞态（B）两层判定。
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
spike_init

THREADS="${SPIKE_RENAME_THREADS:-8}"
BASE="$(spike_base fcrename)"
log "测试根: $BASE（并发线程数 $THREADS）"
run_probe SpikeFileContextRename sp04-filecontext-rename "$BASE" "$THREADS"
cleanup_base "$BASE"
write_report
