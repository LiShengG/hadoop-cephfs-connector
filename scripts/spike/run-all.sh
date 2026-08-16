#!/usr/bin/env bash
#
# 一键跑完 A 层全部 spike（只需 vstart 集群），汇总结论草稿。
# B 层（需 Hadoop 发行版 / Kerberos 集群）另行执行：
#   HADOOP_HOME=... scripts/spike/sp01b-mr-submit.sh
#   scripts/spike/sp06-kerberos.sh 文末清单
#
# 退出码：恒为 0 —— **spike 的产出是结论，不是通过/失败**。
# CONFIRMED（预测成立）同样是"跑成功了"，不应被 CI 当作红灯。
set -uo pipefail
SPIKE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SPIKE_DIR/lib/common.sh"

spike_init
: > "$RESULT_FILE"
printf 'ID\tVERDICT\t说明\t时间\n' > "$RESULT_FILE"

for s in sp01-owner-group sp04-filecontext-rename sp05-visibility \
         sp06-kerberos sp07-checksum-caps sp08-logagg-dirs; do
  echo
  echo "════════════════════════════════════════════════════════════════"
  echo "  $s"
  echo "════════════════════════════════════════════════════════════════"
  bash "$SPIKE_DIR/$s.sh" || warn "$s 执行中断（结论以已记录的为准）"
done

echo
echo "════════════════════════════════════════════════════════════════"
echo "  汇总"
echo "════════════════════════════════════════════════════════════════"
column -t -s $'\t' "$RESULT_FILE" 2>/dev/null || cat "$RESULT_FILE"
write_report

echo
echo "[spike] 下一步："
echo "  1. 人工整理 $OUT_DIR/ECO-FINDINGS-draft.md → docs/reports/YYYY-MM-DD-e1-ecosystem-spikes.md（补复现命令与原始输出）"
echo "  2. CONFIRMED 项建跟踪，并更新 docs/KNOWN-LIMITATIONS.md 和当次报告"
echo "  3. B 层：HADOOP_HOME=... $SPIKE_DIR/sp01b-mr-submit.sh"
