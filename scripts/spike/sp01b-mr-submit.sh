#!/usr/bin/env bash
#
# SP-01 B 层：用**真实 Hadoop 发行版**向 YARN 提交 MR 作业，确证 A 层
# （scripts/spike/sp01-owner-group.sh）复刻的 staging 目录属主校验在真实提交链路上的后果。
#
# A 层复刻的是 JobSubmissionFiles#getStagingDir 的判定逻辑；B 层跑真作业，
# 目的是抓到真实的报错原文（写进 DEPLOY.md 排查表）以及 proxy user 场景的表现。
#
# 前置：
#   - HADOOP_HOME 指向 Hadoop 3.3.6 发行版，YARN 与 MR 可用；
#   - 连接器三件套已按 DEPLOY.md §2 安装到发行版 classpath 与 native 路径；
#   - core-site.xml 已配好 ceph:// 五个必配项。
# 用法：
#   HADOOP_HOME=/opt/hadoop-3.3.6 scripts/spike/sp01b-mr-submit.sh [两种形态都跑]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

[ -n "${HADOOP_HOME:-}" ] || die "需设置 HADOOP_HOME 指向 Hadoop 3.3.6 发行版（B 层用例）"
[ -x "$HADOOP_HOME/bin/hadoop" ] || die "$HADOOP_HOME/bin/hadoop 不可执行"
mkdir -p "$OUT_DIR" "$LOG_DIR"
[ -f "$RESULT_FILE" ] || printf 'ID\tVERDICT\t说明\t时间\n' > "$RESULT_FILE"

EXAMPLES_JAR="$(ls "$HADOOP_HOME"/share/hadoop/mapreduce/hadoop-mapreduce-examples-*.jar 2>/dev/null | head -1)"
[ -f "$EXAMPLES_JAR" ] || die "未找到 hadoop-mapreduce-examples jar"

STAGING="${SPIKE_STAGING_DIR:-ceph:///user/$(id -un)/.staging}"
LOGF="$LOG_DIR/sp01b-mr-submit.log"

log "确保 staging 目录存在: $STAGING"
"$HADOOP_HOME/bin/hadoop" fs -mkdir -p "$STAGING" >/dev/null 2>&1
"$HADOOP_HOME/bin/hadoop" fs -chmod 700 "$STAGING" >/dev/null 2>&1

log "staging 目录当前状态："
"$HADOOP_HOME/bin/hadoop" fs -ls -d "$STAGING" 2>&1 | tee -a "$LOGF"

# 形态一：fs.defaultFS=ceph://（staging 落在 CephFS 上，直接命中属主校验）
log "形态一：fs.defaultFS=ceph:// 提交 pi 作业..."
"$HADOOP_HOME/bin/hadoop" jar "$EXAMPLES_JAR" pi \
    -Dfs.defaultFS=ceph:/// \
    -Dyarn.app.mapreduce.am.staging-dir="$STAGING" \
    2 10 > "$LOGF.form1" 2>&1
RC1=$?
log "形态一退出码=$RC1（日志 $LOGF.form1）"
grep -i "ownership on the staging directory" "$LOGF.form1" | head -3

# 形态二：HDFS 为默认 FS，仅输入输出用 ceph://（生产更常见的形态）
if "$HADOOP_HOME/bin/hadoop" fs -ls hdfs:/// >/dev/null 2>&1; then
  log "形态二：HDFS 为默认 FS，输出目录在 ceph:// ..."
  OUT="ceph:///spike-mr-$(date +%s)"
  "$HADOOP_HOME/bin/hadoop" jar "$EXAMPLES_JAR" teragen 10000 "$OUT" \
      > "$LOGF.form2" 2>&1
  RC2=$?
  log "形态二退出码=$RC2（日志 $LOGF.form2）"
  "$HADOOP_HOME/bin/hadoop" fs -rm -r -f "$OUT" >/dev/null 2>&1
else
  RC2="skip"
  log "形态二跳过（无可用 HDFS）"
fi

if [ "$RC1" -ne 0 ] && grep -qi "ownership on the staging directory" "$LOGF.form1"; then
  record "SP-01B" "CONFIRMED" \
    "fs.defaultFS=ceph:// 的 MR 提交因 staging 属主校验失败（形态二 rc=$RC2）；报错原文见 $LOGF.form1"
elif [ "$RC1" -ne 0 ]; then
  record "SP-01B" "INCONCLUSIVE" \
    "提交失败但非属主校验原因（rc=$RC1），需人工判读 $LOGF.form1"
else
  record "SP-01B" "REFUTED" \
    "fs.defaultFS=ceph:// 的 MR 作业提交并完成（形态二 rc=$RC2）"
fi

write_report
