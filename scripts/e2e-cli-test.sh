#!/usr/bin/env bash
#
# T06 CLI 端到端验证：hadoop fs 全链路（策略见 docs/TESTING.md）。
#
# 运行时组装说明（任务书工作内容 4，"最省事可行的方式"）：
#   `hadoop fs` 包装脚本最终 exec 的就是 org.apache.hadoop.fs.FsShell，
#   因此本脚本不依赖 Hadoop 可执行发行版（磁盘上没有，下载 ~700MB 不划算），
#   而是用 Maven 从本地仓库解析 hadoop-common:3.3.6 及其全部传递依赖组装
#   classpath，连同连接器 jar 直接 `java org.apache.hadoop.fs.FsShell ...`。
#   与发行版 `hadoop fs` 的差异仅在 classpath 来源，命令解析/FS 调用路径完全相同。
#
# 覆盖：-mkdir -p / -put(200MB) / -ls / -stat / -df / -cat / -cp / -mv /
#       -rm -r / -test，逐条断言退出码；put/get 200MB md5 往返比对。
#
# 前置：vstart 集群运行中（scripts/cluster-up.sh）；连接器可编译。
# 环境变量：CEPH_CONF_FILE / CEPH_BUILD / E2E_TMPDIR / E2E_SIZE_MB 可覆盖默认。
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ_ROOT="$(dirname "$SCRIPT_DIR")"
MVN_PROJ="$PROJ_ROOT/hadoop-cephfs"

CEPH_BUILD="${CEPH_BUILD:-/home/lsh/code/ceph/build}"
CEPH_CONF="${CEPH_CONF_FILE:-$CEPH_BUILD/ceph.conf}"
CEPH_LIB_DIR="$CEPH_BUILD/lib"
JNI_DIR="$PROJ_ROOT/dist/native"
SIZE_MB="${E2E_SIZE_MB:-200}"

FAILURES=0

log()  { echo "[e2e] $*"; }
die()  { echo "[e2e] FAIL: $*" >&2; exit 1; }

# ── 0. 前置检查 ─────────────────────────────────────────────────────────────
[ -f "$CEPH_CONF" ] || die "ceph.conf 不存在: $CEPH_CONF（先跑 scripts/cluster-up.sh）"
[ -f "$JNI_DIR/libcephfs_jni.so" ] || die "缺 JNI 库: $JNI_DIR/libcephfs_jni.so（见 docs/environments/E1.md）"
timeout 30 "$CEPH_BUILD/bin/ceph" -c "$CEPH_CONF" -s >/dev/null 2>&1 \
  || die "集群不可达（bin/ceph -s 失败），先跑 scripts/cluster-up.sh 与 smoke-test.sh"

# ── 1. 构建连接器 jar 与运行时 classpath ────────────────────────────────────
log "构建连接器 jar（mvn -DskipTests package）..."
( cd "$MVN_PROJ" && mvn -q -DskipTests package ) || die "mvn package 失败"
CONNECTOR_JAR="$(ls "$MVN_PROJ"/target/hadoop-cephfs-*.jar | head -1)"
[ -f "$CONNECTOR_JAR" ] || die "连接器 jar 未产出"

CP_FILE="$MVN_PROJ/target/e2e-classpath.txt"
log "解析 Hadoop 3.3.6 运行时 classpath（mvn dependency:build-classpath）..."
( cd "$MVN_PROJ" && mvn -q dependency:build-classpath \
      -Dmdep.outputFile="$CP_FILE" ) || die "classpath 解析失败"
# 最小 Hadoop conf 目录：FsShell 以 quietMode=false 加载配置，
# classpath 上必须真实存在 core-site.xml（缺失直接 RuntimeException）。
# ceph 连接参数一并写入，等效于部署时的 core-site.xml（T07 文档同款）。
CONF_DIR="$MVN_PROJ/target/e2e-conf"
mkdir -p "$CONF_DIR"
cat > "$CONF_DIR/core-site.xml" <<EOF
<?xml version="1.0"?>
<configuration>
  <property><name>fs.defaultFS</name><value>ceph:///</value></property>
  <property><name>fs.ceph.impl</name><value>org.apache.hadoop.fs.ceph.CephFileSystem</value></property>
  <property><name>fs.AbstractFileSystem.ceph.impl</name><value>org.apache.hadoop.fs.ceph.CephFs</value></property>
  <property><name>ceph.conf.file</name><value>$CEPH_CONF</value></property>
  <property><name>ceph.auth.id</name><value>admin</value></property>
</configuration>
EOF

CP="$CONF_DIR:$CONNECTOR_JAR:$(cat "$CP_FILE")"

export LD_LIBRARY_PATH="$CEPH_LIB_DIR:${LD_LIBRARY_PATH:-}"
# vstart 调试构建 ceph.conf 含 lockdep=true，mount 引导期偶发 Ceph 内部
# 锁环误报 abort（详见 pom.xml failsafe 配置注释）；客户端进程覆盖关闭。
export CEPH_ARGS="--lockdep=false"

# `hadoop fs` 等效入口：java org.apache.hadoop.fs.FsShell（见文件头说明）
fsshell() {
  java -cp "$CP" \
       -Djava.library.path="$JNI_DIR:$CEPH_LIB_DIR" \
       org.apache.hadoop.fs.FsShell \
       "$@" 2>/dev/null
}

# 捕获 stdout 用（过滤 CephMount 静态加载器打到 stdout 的
# "Loading libcephfs-jni..." 噪音），退出码保持 FsShell 的
fsout() {
  local out rc
  out="$(fsshell "$@")"; rc=$?
  printf '%s\n' "$out" | grep -v '^Loading libcephfs'
  return "$rc"
}

# expect_rc <期望退出码> <描述> -- <fsshell 参数...>
expect_rc() {
  local want="$1" desc="$2"; shift 3
  fsshell "$@" >/dev/null
  local got=$?
  if [ "$got" -eq "$want" ]; then
    log "PASS: $desc (rc=$got)"
  else
    echo "[e2e] FAIL: $desc — 期望 rc=$want 实际 rc=$got（命令: fs $*）" >&2
    FAILURES=$((FAILURES + 1))
  fi
}

# ── 2. 测试数据 ─────────────────────────────────────────────────────────────
TMPDIR_LOCAL="${E2E_TMPDIR:-$(mktemp -d /tmp/ceph-e2e.XXXXXX)}"
mkdir -p "$TMPDIR_LOCAL"
BASE="/e2e-cli-$(date +%s)-$$"

cleanup() {
  fsshell -rm -r -f "$BASE" >/dev/null 2>&1
  rm -rf "$TMPDIR_LOCAL"
}
trap cleanup EXIT

log "生成 ${SIZE_MB}MB 随机文件..."
BIG_LOCAL="$TMPDIR_LOCAL/big.bin"
dd if=/dev/urandom of="$BIG_LOCAL" bs=1M count="$SIZE_MB" status=none \
  || die "本地测试文件生成失败"
BIG_MD5="$(md5sum "$BIG_LOCAL" | awk '{print $1}')"

SMALL_LOCAL="$TMPDIR_LOCAL/hello.txt"
printf 'hello cephfs via FsShell\n' > "$SMALL_LOCAL"

# ── 3. CLI 全链路 ───────────────────────────────────────────────────────────
expect_rc 0 "mkdir -p 多级目录"            -- -mkdir -p "$BASE/dir/sub"
expect_rc 0 "test -d 目录存在"             -- -test -d "$BASE/dir/sub"

expect_rc 0 "put ${SIZE_MB}MB 大文件"       -- -put "$BIG_LOCAL" "$BASE/dir/big.bin"
expect_rc 0 "put 小文本文件"               -- -put "$SMALL_LOCAL" "$BASE/dir/hello.txt"
expect_rc 1 "put 已存在目标应失败"          -- -put "$SMALL_LOCAL" "$BASE/dir/hello.txt"

# -ls：退出码 + 列表内容 + 大文件长度
LS_OUT="$(fsout -ls "$BASE/dir")" || { echo "[e2e] FAIL: ls 退出码非 0" >&2; FAILURES=$((FAILURES+1)); }
echo "$LS_OUT" | grep -q "big.bin"   || { echo "[e2e] FAIL: ls 未列出 big.bin" >&2; FAILURES=$((FAILURES+1)); }
echo "$LS_OUT" | grep -q "hello.txt" || { echo "[e2e] FAIL: ls 未列出 hello.txt" >&2; FAILURES=$((FAILURES+1)); }
echo "$LS_OUT" | grep -q "$((SIZE_MB * 1024 * 1024))" \
  || { echo "[e2e] FAIL: ls 中 big.bin 长度非 ${SIZE_MB}MB" >&2; FAILURES=$((FAILURES+1)); }
[ "$FAILURES" -eq 0 ] && log "PASS: ls 列表与长度正确"

# -stat：文件名与字节数
STAT_OUT="$(fsout -stat '%n %b' "$BASE/dir/big.bin")" \
  || { echo "[e2e] FAIL: stat 退出码非 0" >&2; FAILURES=$((FAILURES+1)); }
[ "$STAT_OUT" = "big.bin $((SIZE_MB * 1024 * 1024))" ] \
  && log "PASS: stat 输出正确 ($STAT_OUT)" \
  || { echo "[e2e] FAIL: stat 输出异常: $STAT_OUT" >&2; FAILURES=$((FAILURES+1)); }

# -df：容量报告非零（getStatus 路径）
DF_OUT="$(fsout -df "$BASE")" \
  || { echo "[e2e] FAIL: df 退出码非 0" >&2; FAILURES=$((FAILURES+1)); }
echo "$DF_OUT" | awk 'NR==2 && $2+0 > 0 {ok=1} END {exit !ok}' \
  && log "PASS: df 容量非零" \
  || { echo "[e2e] FAIL: df 容量为 0 或解析失败: $DF_OUT" >&2; FAILURES=$((FAILURES+1)); }

# -cat：小文件内容一致
CAT_OUT="$(fsout -cat "$BASE/dir/hello.txt")" \
  || { echo "[e2e] FAIL: cat 退出码非 0" >&2; FAILURES=$((FAILURES+1)); }
[ "$CAT_OUT" = "hello cephfs via FsShell" ] \
  && log "PASS: cat 内容一致" \
  || { echo "[e2e] FAIL: cat 内容不一致: $CAT_OUT" >&2; FAILURES=$((FAILURES+1)); }

# put/get 往返 md5（任务书验收标准 2）
GET_LOCAL="$TMPDIR_LOCAL/big.get.bin"
expect_rc 0 "get ${SIZE_MB}MB 回本地"       -- -get "$BASE/dir/big.bin" "$GET_LOCAL"
GET_MD5="$(md5sum "$GET_LOCAL" | awk '{print $1}')"
if [ "$GET_MD5" = "$BIG_MD5" ]; then
  log "PASS: put/get md5 往返一致 ($BIG_MD5)"
else
  echo "[e2e] FAIL: md5 不一致 put=$BIG_MD5 get=$GET_MD5" >&2
  FAILURES=$((FAILURES + 1))
fi

expect_rc 0 "cp FS 内复制"                  -- -cp "$BASE/dir/hello.txt" "$BASE/dir/sub/hello-copy.txt"
expect_rc 0 "cp 后源与副本并存(源)"          -- -test -e "$BASE/dir/hello.txt"
expect_rc 0 "cp 后源与副本并存(副本)"        -- -test -e "$BASE/dir/sub/hello-copy.txt"

expect_rc 0 "mv 重命名"                     -- -mv "$BASE/dir/hello.txt" "$BASE/dir/hello-moved.txt"
expect_rc 0 "mv 后新路径存在"               -- -test -e "$BASE/dir/hello-moved.txt"
expect_rc 1 "mv 后旧路径不存在"             -- -test -e "$BASE/dir/hello.txt"

expect_rc 0 "rm -r 递归删除"                -- -rm -r "$BASE/dir"
expect_rc 1 "rm -r 后目录不存在"            -- -test -e "$BASE/dir"
expect_rc 1 "rm 不存在路径应失败"           -- -rm "$BASE/dir/never-there"

# ── 4. 汇总 ─────────────────────────────────────────────────────────────────
if [ "$FAILURES" -eq 0 ]; then
  log "PASS: CLI 端到端全部通过（FsShell 等效 hadoop fs，${SIZE_MB}MB md5 往返一致）"
  exit 0
else
  echo "[e2e] FAIL: $FAILURES 项未通过" >&2
  exit 1
fi
