#!/usr/bin/env bash
# Run ECO-CLI-01 through a real Hadoop distribution's `bin/hadoop fs` entry point.
set -uo pipefail

HADOOP_HOME="${HADOOP_HOME:?set HADOOP_HOME to the Hadoop distribution directory}"
HADOOP_CONF_DIR="${HADOOP_CONF_DIR:-$HADOOP_HOME/etc/hadoop}"
CEPH_TEST_ROOT="${CEPH_TEST_ROOT:?set CEPH_TEST_ROOT to an existing ceph:// test root}"
RUN_ID="${ECO_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
SIZE_MB="${ECO_SIZE_MB:-200}"
HADOOP_BIN="$HADOOP_HOME/bin/hadoop"

case "$CEPH_TEST_ROOT" in
  ceph://*) ;;
  *) echo "[eco-cli] FAIL: CEPH_TEST_ROOT must use ceph://" >&2; exit 2 ;;
esac
case "$RUN_ID" in
  *[!A-Za-z0-9._-]*) echo "[eco-cli] FAIL: invalid ECO_RUN_ID: $RUN_ID" >&2; exit 2 ;;
esac
case "$SIZE_MB" in
  ''|*[!0-9]*|0) echo "[eco-cli] FAIL: ECO_SIZE_MB must be a positive integer" >&2; exit 2 ;;
esac

[ -x "$HADOOP_BIN" ] || {
  echo "[eco-cli] FAIL: Hadoop entry point is not executable: $HADOOP_BIN" >&2
  exit 2
}

export HADOOP_HOME HADOOP_CONF_DIR
BASE="${CEPH_TEST_ROOT%/}/eco-cli-$RUN_ID"
LOCAL_TMP="$(mktemp -d "${TMPDIR:-/tmp}/hadoop-cephfs-eco-cli.XXXXXX")"
FAILURES=0
ASSERTIONS=0

log() { echo "[eco-cli] $*"; }

pass() {
  ASSERTIONS=$((ASSERTIONS + 1))
  log "PASS: $*"
}

fail() {
  ASSERTIONS=$((ASSERTIONS + 1))
  FAILURES=$((FAILURES + 1))
  echo "[eco-cli] FAIL: $*" >&2
}

fs() {
  "$HADOOP_BIN" fs "$@"
}

fsout() {
  local rc
  fs "$@" | sed '/^Loading libcephfs-jni/d'
  rc=${PIPESTATUS[0]}
  return "$rc"
}

expect_rc() {
  local expected="$1" description="$2"
  shift 2
  fs "$@" >/dev/null 2>&1
  local actual=$?
  if [ "$actual" -eq "$expected" ]; then
    pass "$description (rc=$actual)"
  else
    fail "$description: expected rc=$expected, actual rc=$actual; command=hadoop fs $*"
  fi
}

cleanup() {
  fs -rm -r -f -skipTrash "$BASE" >/dev/null 2>&1 || true
  rm -rf "$LOCAL_TMP"
}

log "Hadoop version: $($HADOOP_BIN version 2>&1 | sed -n '1p')"
log "Hadoop entry point: $HADOOP_BIN"
log "Test path: $BASE"

fs -test -d "$CEPH_TEST_ROOT" >/dev/null 2>&1 || {
  echo "[eco-cli] FAIL: CephFS test root is unavailable: $CEPH_TEST_ROOT" >&2
  rm -rf "$LOCAL_TMP"
  exit 2
}
if fs -test -e "$BASE" >/dev/null 2>&1; then
  echo "[eco-cli] FAIL: refusing to reuse existing test path: $BASE" >&2
  rm -rf "$LOCAL_TMP"
  exit 2
fi
trap cleanup EXIT

BIG_LOCAL="$LOCAL_TMP/big.bin"
BIG_GET="$LOCAL_TMP/big.get.bin"
SMALL_LOCAL="$LOCAL_TMP/hello.txt"
dd if=/dev/urandom of="$BIG_LOCAL" bs=1M count="$SIZE_MB" status=none || exit 2
printf 'hello cephfs via Hadoop FsShell\n' > "$SMALL_LOCAL"
BIG_MD5="$(md5sum "$BIG_LOCAL" | awk '{print $1}')"

expect_rc 0 "mkdir -p creates nested directories" -mkdir -p "$BASE/dir/sub"
expect_rc 0 "test -d sees the nested directory" -test -d "$BASE/dir/sub"
expect_rc 0 "put uploads the ${SIZE_MB}MB file" -put "$BIG_LOCAL" "$BASE/dir/big.bin"
expect_rc 0 "put uploads the text file" -put "$SMALL_LOCAL" "$BASE/dir/hello.txt"
expect_rc 1 "put rejects an existing destination" -put "$SMALL_LOCAL" "$BASE/dir/hello.txt"

LS_OUT="$(fsout -ls "$BASE/dir" 2>/dev/null)"
LS_RC=$?
[ "$LS_RC" -eq 0 ] && pass "ls exits successfully" || fail "ls exits with rc=$LS_RC"
echo "$LS_OUT" | grep -q 'big.bin' && pass "ls includes big.bin" || fail "ls omits big.bin"
echo "$LS_OUT" | grep -q 'hello.txt' && pass "ls includes hello.txt" || fail "ls omits hello.txt"
echo "$LS_OUT" | grep -q "$((SIZE_MB * 1024 * 1024))" \
  && pass "ls reports the large-file length" || fail "ls reports an incorrect large-file length"

STAT_OUT="$(fsout -stat '%n %b' "$BASE/dir/big.bin" 2>/dev/null)"
STAT_RC=$?
if [ "$STAT_RC" -eq 0 ] && [ "$STAT_OUT" = "big.bin $((SIZE_MB * 1024 * 1024))" ]; then
  pass "stat reports the expected name and length"
else
  fail "stat output mismatch: rc=$STAT_RC output=$STAT_OUT"
fi

DF_OUT="$(fsout -df "$BASE" 2>/dev/null)"
DF_RC=$?
if [ "$DF_RC" -eq 0 ] && echo "$DF_OUT" | awk 'NR == 2 && $2 + 0 > 0 { ok=1 } END { exit !ok }'; then
  pass "df reports non-zero capacity"
else
  fail "df output is unavailable or invalid: rc=$DF_RC output=$DF_OUT"
fi

CAT_OUT="$(fsout -cat "$BASE/dir/hello.txt" 2>/dev/null)"
CAT_RC=$?
if [ "$CAT_RC" -eq 0 ] && [ "$CAT_OUT" = "hello cephfs via Hadoop FsShell" ]; then
  pass "cat returns the expected content"
else
  fail "cat content mismatch: rc=$CAT_RC output=$CAT_OUT"
fi

expect_rc 0 "get downloads the ${SIZE_MB}MB file" -get "$BASE/dir/big.bin" "$BIG_GET"
GET_MD5="$(md5sum "$BIG_GET" 2>/dev/null | awk '{print $1}')"
[ "$GET_MD5" = "$BIG_MD5" ] \
  && pass "put/get MD5 matches ($BIG_MD5)" || fail "put/get MD5 mismatch: put=$BIG_MD5 get=$GET_MD5"

expect_rc 0 "cp copies a file within CephFS" -cp "$BASE/dir/hello.txt" "$BASE/dir/sub/hello-copy.txt"
expect_rc 0 "cp retains the source" -test -e "$BASE/dir/hello.txt"
expect_rc 0 "cp creates the destination" -test -e "$BASE/dir/sub/hello-copy.txt"
expect_rc 0 "mv renames a file" -mv "$BASE/dir/hello.txt" "$BASE/dir/hello-moved.txt"
expect_rc 0 "mv creates the new path" -test -e "$BASE/dir/hello-moved.txt"
expect_rc 1 "mv removes the old path" -test -e "$BASE/dir/hello.txt"
expect_rc 0 "rm -r removes the test directory" -rm -r -skipTrash "$BASE/dir"
expect_rc 1 "test reports the removed directory as absent" -test -e "$BASE/dir"
expect_rc 1 "rm reports a missing path" -rm -skipTrash "$BASE/never-there"

if [ "$FAILURES" -eq 0 ]; then
  log "PASS: ECO-CLI-01 completed; assertions=$ASSERTIONS failures=0"
  exit 0
fi

echo "[eco-cli] FAIL: ECO-CLI-01 completed; assertions=$ASSERTIONS failures=$FAILURES" >&2
exit 1
