#!/usr/bin/env bash
#
# spike 套件公共库：环境自检、连接器构建、classpath 组装、Java 探针编译与运行、
# 结论记录。被 scripts/spike/sp*.sh 与 run-all.sh 引用，不单独执行。
#
# 设计要点：
#   1. 每个探针只回答"预测是否成立"，输出统一的 `RESULT|<ID>|<VERDICT>|<说明>` 行，
#      由本库解析归档 —— 人读的详情打在同一份 stdout 里，一并存进日志文件；
#   2. VERDICT 只有三种：CONFIRMED（预测成立）/ REFUTED（预测不成立）/
#      INCONCLUSIVE（环境或前置条件不满足，未能判定）。**证伪与证实同样有价值**，
#      禁止为了"符合预期"调整判据；
#   3. 运行时组装方式与 scripts/e2e-cli-test.sh 完全一致（mvn 解析 hadoop-common
#      传递依赖 + 连接器 jar），保证与生产 classpath 的差异仅在来源。

SPIKE_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SPIKE_DIR="$(dirname "$SPIKE_LIB_DIR")"
PROJ_ROOT="$(dirname "$(dirname "$SPIKE_DIR")")"
MVN_PROJ="$PROJ_ROOT/hadoop-cephfs"

CEPH_BUILD="${CEPH_BUILD:-/home/lsh/code/ceph/build}"
CEPH_CONF="${CEPH_CONF_FILE:-$CEPH_BUILD/ceph.conf}"
CEPH_LIB_DIR="${CEPH_LIB_DIR:-$CEPH_BUILD/lib}"
JNI_DIR="${CEPH_JNI_DIR:-$PROJ_ROOT/dist/native}"
CEPH_AUTH_ID="${CEPH_AUTH_ID:-admin}"
CEPH_AUTH_KEYRING="${CEPH_AUTH_KEYRING:-}"

OUT_DIR="${SPIKE_OUT_DIR:-$MVN_PROJ/target/spike}"
CLASSES_DIR="$OUT_DIR/classes"
RESULT_FILE="$OUT_DIR/results.tsv"
LOG_DIR="$OUT_DIR/logs"

# 探针源码与输出含中文：POSIX locale 下 javac 按 ANSI_X3.4-1968 解析源码、
# JVM 按 sun.stdout.encoding 输出，二者都会产生乱码（实测踩过两次）。
# 这里同时钉死 locale 与 JVM 编码属性；C.utf8 不可用时退回 C 也不影响判定，
# 只是日志里的中文会退化，故优先选用系统实际存在的 UTF-8 locale。
if locale -a 2>/dev/null | grep -qi "^C\.utf8$"; then
  export LC_ALL="${LC_ALL:-C.utf8}" LANG="${LANG:-C.utf8}"
elif locale -a 2>/dev/null | grep -qi "^en_US\.utf8$"; then
  export LC_ALL="${LC_ALL:-en_US.utf8}" LANG="${LANG:-en_US.utf8}"
fi
JAVA_ENC_OPTS=(-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8)

log()  { echo "[spike] $*"; }
warn() { echo "[spike] WARN: $*" >&2; }
die()  { echo "[spike] FAIL: $*" >&2; exit 1; }

# ── 环境自检 ────────────────────────────────────────────────────────────────

precheck() {
  mkdir -p "$OUT_DIR" "$LOG_DIR"
  [ -f "$CEPH_CONF" ] || die "ceph.conf 不存在: $CEPH_CONF（先跑 scripts/cluster-up.sh）"
  [ -f "$JNI_DIR/libcephfs_jni.so" ] \
    || die "缺 JNI 库: $JNI_DIR/libcephfs_jni.so（见 docs/ENV.md §2）"
  timeout 30 "$CEPH_BUILD/bin/ceph" -c "$CEPH_CONF" -s >/dev/null 2>&1 \
    || die "集群不可达（bin/ceph -s 失败），先跑 scripts/cluster-up.sh 与 smoke-test.sh"
}

# ── 构建与 classpath（幂等：已就绪则跳过） ──────────────────────────────────

CONF_DIR=""
CP=""

setup_runtime() {
  local cp_file="$OUT_DIR/classpath.txt"

  if [ ! -f "$cp_file" ] || [ "$MVN_PROJ/pom.xml" -nt "$cp_file" ]; then
    log "构建连接器 jar 并解析 Hadoop classpath..."
    ( cd "$MVN_PROJ" && mvn -q -DskipTests package ) || die "mvn package 失败"
    ( cd "$MVN_PROJ" && mvn -q dependency:build-classpath -Dmdep.outputFile="$cp_file" ) \
      || die "classpath 解析失败"
  fi

  local connector_jar
  connector_jar="$(ls "$MVN_PROJ"/target/hadoop-cephfs-*.jar 2>/dev/null | grep -v sources | head -1)"
  [ -f "$connector_jar" ] || die "连接器 jar 未产出"

  CONF_DIR="$OUT_DIR/conf"
  mkdir -p "$CONF_DIR"
  local keyring_property=""
  if [ -n "$CEPH_AUTH_KEYRING" ]; then
    keyring_property="  <property><name>ceph.auth.keyring</name><value>$CEPH_AUTH_KEYRING</value></property>"
  fi
  cat > "$CONF_DIR/core-site.xml" <<EOF
<?xml version="1.0"?>
<configuration>
  <property><name>fs.defaultFS</name><value>ceph:///</value></property>
  <property><name>fs.ceph.impl</name><value>org.apache.hadoop.fs.ceph.CephFileSystem</value></property>
  <property><name>fs.AbstractFileSystem.ceph.impl</name><value>org.apache.hadoop.fs.ceph.CephFs</value></property>
  <property><name>ceph.conf.file</name><value>$CEPH_CONF</value></property>
  <property><name>ceph.auth.id</name><value>$CEPH_AUTH_ID</value></property>
$keyring_property
</configuration>
EOF

  CP="$CONF_DIR:$CLASSES_DIR:$connector_jar:$(cat "$cp_file")"
  export LD_LIBRARY_PATH="$CEPH_LIB_DIR:${LD_LIBRARY_PATH:-}"
  # vstart 调试构建默认规避 lockdep 误报；E2 Release 验证可显式传
  # `CEPH_ARGS=`，证明无需该 workaround。
  if [ -z "${CEPH_ARGS+x}" ]; then
    export CEPH_ARGS="--lockdep=false"
  else
    export CEPH_ARGS
  fi
}

compile_probes() {
  local marker="$CLASSES_DIR/.compiled"
  local newest
  newest="$(ls -t "$SPIKE_DIR"/java/*.java 2>/dev/null | head -1)"
  if [ -f "$marker" ] && [ -n "$newest" ] && [ "$marker" -nt "$newest" ]; then
    return 0
  fi
  log "编译 Java 探针..."
  mkdir -p "$CLASSES_DIR"
  local sources=()
  local source
  for source in "$SPIKE_DIR"/java/*.java; do
    # Spark 探针由真实 Spark 流程使用 Spark 自身 jars 单独编译；把它放进
    # Hadoop-only 的公共 classpath 会导致所有非 Spark spike 都无法启动。
    if [ "$(basename "$source")" = "SpikeSparkCheckpoint.java" ]; then
      continue
    fi
    sources+=("$source")
  done
  # -encoding UTF-8 必须显式指定：探针源码含中文，POSIX locale 下 javac
  # 会按 ANSI_X3.4-1968 解析并把字符串字面量编成乱码（实测踩过）
  javac -nowarn -encoding UTF-8 -cp "$CP" -d "$CLASSES_DIR" "${sources[@]}" \
    || die "探针编译失败"
  touch "$marker"
}

spike_init() {
  precheck
  setup_runtime
  compile_probes
  [ -f "$RESULT_FILE" ] || printf 'ID\tVERDICT\t说明\t时间\n' > "$RESULT_FILE"
}

# ── 运行与记录 ──────────────────────────────────────────────────────────────

# run_probe <类名> <日志名> [参数...]；stdout 同时进日志文件，RESULT 行被解析归档
run_probe() {
  local main="$1" name="$2"; shift 2
  local logf="$LOG_DIR/$name.log"
  log "运行 $main ..."
  java -cp "$CP" "${JAVA_ENC_OPTS[@]}" \
       -Djava.library.path="$JNI_DIR:$CEPH_LIB_DIR" \
       "$main" "$@" 2>&1 | grep -v '^Loading libcephfs' | tee "$logf"
  local rc=${PIPESTATUS[0]}
  if [ "$rc" -ne 0 ]; then
    warn "$main 退出码 $rc（详见 $logf）"
  fi
  # 解析探针自报的结论
  local any=0
  while IFS='|' read -r _ id verdict note; do
    record "$id" "$verdict" "$note"
    any=1
  done < <(grep '^RESULT|' "$logf")
  if [ "$any" -eq 0 ]; then
    warn "$main 未输出任何 RESULT 行；按 INCONCLUSIVE 记录"
    record "${name}" "INCONCLUSIVE" "探针未产出结论，见 $logf"
  fi
  return 0
}

# record <ID> <VERDICT> <说明>
record() {
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$(date -Is)" >> "$RESULT_FILE"
  case "$2" in
    CONFIRMED)    echo "[spike] >>> $1: 预测成立 —— $3" ;;
    REFUTED)      echo "[spike] >>> $1: 预测不成立 —— $3" ;;
    *)            echo "[spike] >>> $1: 未能判定 —— $3" ;;
  esac
}

# 生成待人工整理的结论草稿（脚本不直接改 docs/，避免覆盖人工结论）
write_report() {
  local draft="$OUT_DIR/ECO-FINDINGS-draft.md"
  {
    echo "# ECO spike 结论草稿（自动生成，需人工整理后并入 docs/ECO-FINDINGS.md）"
    echo
    echo "- 生成时间：$(date -Is)"
    echo "- 集群：$(timeout 20 "$CEPH_BUILD/bin/ceph" -c "$CEPH_CONF" -v 2>/dev/null | head -1)"
    echo "- 连接器：$(cd "$PROJ_ROOT" && git rev-parse --short HEAD 2>/dev/null)"
    echo "- 原始日志：\`$LOG_DIR\`"
    echo
    echo "| ID | 结论 | 说明 |"
    echo "|---|---|---|"
    tail -n +2 "$RESULT_FILE" | awk -F'\t' '{printf "| %s | %s | %s |\n", $1, $2, $3}'
    echo
    echo "> VERDICT 口径：CONFIRMED=预测成立（连接器行为确实会造成该后果）；"
    echo "> REFUTED=预测不成立；INCONCLUSIVE=环境不满足，未判定。"
    echo "> **证伪同样是有价值的结论，不得为符合预期而调整判据。**"
  } > "$draft"
  log "结论草稿：$draft"
}

# 在 CephFS 上分配一个唯一测试根，并注册清理
spike_base() {
  echo "ceph:///spike-$(date +%s)-$$-${1:-x}"
}

cleanup_base() {
  local base="$1"
  java -cp "$CP" "${JAVA_ENC_OPTS[@]}" -Djava.library.path="$JNI_DIR:$CEPH_LIB_DIR" \
       org.apache.hadoop.fs.FsShell -rm -r -f "$base" >/dev/null 2>&1 || true
}
