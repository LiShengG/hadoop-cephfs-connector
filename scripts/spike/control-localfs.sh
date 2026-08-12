#!/usr/bin/env bash
#
# 判据自检（控制组）：把**判别型**探针跑在 LocalFileSystem 上。
#
# 为什么需要它：spike 的判据很容易写成"在任何文件系统上都成立"的假阳性。
# 本项目实测踩过两次：
#   ① SP-01 初版用"chown 给某个 uid 后属主校验失败"做判据 —— 可任何 FS（含 HDFS）
#      对真正属于别人的目录都会失败，且无账号的 uid 在本地 FS 上同样报数字；
#      修正为"用系统上真实存在的账号"，本地 FS 报出用户名 → REFUTED，判据才有区分力。
#   ② SP-08 初版把 setOwner 目标设成当前用户 —— "变更前后相同"既可能是静默无效，
#      也可能本来就一样；改成 chown 到另一个真实账号才判得准。
#
# 因此：**判别型探针在本地 FS 上必须全部 REFUTED**，否则判据有问题，先修判据再谈结论。
#
# 非判别型探针（不参与本自检）：
#   SP-05 —— 本地 FS（ChecksumFileSystem）本就无法让 reader 看到未成块的尾部数据，
#            CONFIRMED 属正常，不代表判据坏；
#   SP-06/SP-07 —— 本地 FS 同样没有委托 Token、同样返回 null checksum，
#            属"事实确认型"，控制组无判别意义（HDFS 才是有效对照）。
#
# 本脚本**不需要 Ceph 集群**（但需要能解析项目依赖以组装 classpath）。
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

setup_runtime          # 只装配 classpath，不做集群自检
compile_probes

TMP="$(mktemp -d /tmp/spike-control.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT

MY_UID="$(id -u)"; MY_GID="$(id -g)"; MY_GROUP="$(id -gn)"
read -r NAMED_USER NAMED_UID < <(getent passwd \
  | awk -F: -v me="$MY_UID" '$3 != me && $3 > 0 && $3 < 65534 {print $1, $3; exit}')
[ -n "${NAMED_UID:-}" ] || die "未找到可用于构造的系统账号"
NAMED_GROUP="$(id -gn "$NAMED_USER" 2>/dev/null || echo "$NAMED_USER")"

log "控制组根目录: $TMP（构造账号 $NAMED_USER:$NAMED_GROUP uid=$NAMED_UID）"
RESULT_FILE="$TMP/results.tsv"
LOG_DIR="$TMP/logs"; mkdir -p "$LOG_DIR"
printf 'ID\tVERDICT\t说明\t时间\n' > "$RESULT_FILE"

run_probe SpikeOwnerGroup        control-ownergroup "file://$TMP/og" \
          "$NAMED_UID" "$NAMED_USER" "$MY_GID" "$MY_GROUP"
run_probe SpikeFileContextRename control-fcrename   "file://$TMP/fc" 4
run_probe SpikeLogAggDirs        control-logagg     "file://$TMP/log" \
          "$NAMED_USER" "$NAMED_GROUP"

echo
echo "── 判据自检结果（期望全部 REFUTED）──"
BAD=0
while IFS=$'\t' read -r id verdict note _; do
  [ "$id" = "ID" ] && continue
  if [ "$verdict" = "REFUTED" ]; then
    echo "  OK   $id  $verdict"
  else
    echo "  BAD  $id  $verdict —— $note"
    BAD=$((BAD + 1))
  fi
done < "$RESULT_FILE"

if [ "$BAD" -gt 0 ]; then
  echo
  echo "[spike] 判据自检失败：$BAD 条判别型判据在本地 FS 上也成立（假阳性），"
  echo "        先修判据，再用它去判连接器。"
  exit 1
fi
echo
echo "[spike] 判据自检通过：判别型判据在正常 FS 上均为 REFUTED，具备区分力。"
