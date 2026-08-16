#!/usr/bin/env bash
# Run ECO-SPK-01 and ECO-SPK-02 through Spark on YARN with committer v1 and v2.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_FILE="$SCRIPT_DIR/java/E3SparkBatchSuite.java"
SPARK_HOME="${SPARK_HOME:?set SPARK_HOME to the Spark distribution directory}"
HADOOP_HOME="${HADOOP_HOME:?set HADOOP_HOME to the Hadoop distribution directory}"
HADOOP_CONF_DIR="${HADOOP_CONF_DIR:-$HADOOP_HOME/etc/hadoop}"
CEPH_TEST_ROOT="${CEPH_TEST_ROOT:?set CEPH_TEST_ROOT to an existing ceph:// test root}"
CONNECTOR_JAR="${CONNECTOR_JAR:?set CONNECTOR_JAR to hadoop-cephfs jar}"
CEPH_JAVA_JAR="${CEPH_JAVA_JAR:?set CEPH_JAVA_JAR to libcephfs.jar}"
RUN_ID="${ECO_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
JNI_DIR="${CEPH_JNI_DIR:-/usr/lib/jni}"
NATIVE_LIB_DIRS="${CEPH_NATIVE_LIB_DIRS:-/usr/lib:/usr/lib/x86_64-linux-gnu:/usr/lib/jni}"
BASE="${CEPH_TEST_ROOT%/}/eco-spk-p0-$RUN_ID"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/hadoop-cephfs-eco-spark.XXXXXX")"
APP_JAR="$WORK_DIR/e3-spark-batch-suite.jar"
FAILURES=0

case "$CEPH_TEST_ROOT" in
  ceph://*) ;;
  *) echo "[eco-spark] FAIL: CEPH_TEST_ROOT must use ceph://" >&2; exit 2 ;;
esac
case "$RUN_ID" in
  *[!A-Za-z0-9._-]*) echo "[eco-spark] FAIL: invalid ECO_RUN_ID: $RUN_ID" >&2; exit 2 ;;
esac

for required in "$SOURCE_FILE" "$SPARK_HOME/bin/spark-submit" "$HADOOP_HOME/bin/hadoop" \
    "$CONNECTOR_JAR" "$CEPH_JAVA_JAR" "$JNI_DIR/libcephfs_jni.so"; do
  [ -e "$required" ] || {
    echo "[eco-spark] FAIL: required path is missing: $required" >&2
    exit 2
  }
done

export SPARK_HOME HADOOP_HOME HADOOP_CONF_DIR
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-11-openjdk-amd64}"
export CEPH_JNI_PATH="$JNI_DIR/libcephfs_jni.so"
export LD_LIBRARY_PATH="$NATIVE_LIB_DIRS${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

fs() {
  "$HADOOP_HOME/bin/hadoop" fs "$@"
}

cleanup() {
  fs -rm -r -f -skipTrash "$BASE" >/dev/null 2>&1 || true
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

echo "[eco-spark] Spark version: $($SPARK_HOME/bin/spark-submit --version 2>&1 \
  | sed -n 's/.*version \([0-9][0-9.]*\).*/\1/p' | head -1)"
echo "[eco-spark] Test path: $BASE"

fs -test -d "$CEPH_TEST_ROOT" >/dev/null 2>&1 || {
  echo "[eco-spark] FAIL: CephFS test root is unavailable: $CEPH_TEST_ROOT" >&2
  exit 2
}
if fs -test -e "$BASE" >/dev/null 2>&1; then
  echo "[eco-spark] FAIL: refusing to reuse existing test path: $BASE" >&2
  exit 2
fi

mkdir -p "$WORK_DIR/classes"
javac -source 8 -target 8 \
  -cp "$SPARK_HOME/jars/*:$HADOOP_HOME/share/hadoop/common/*:$HADOOP_HOME/share/hadoop/common/lib/*" \
  -d "$WORK_DIR/classes" "$SOURCE_FILE" || exit 2
jar cf "$APP_JAR" -C "$WORK_DIR/classes" . || exit 2

for version in 1 2; do
  echo "[eco-spark] Running committer v$version"
  "$SPARK_HOME/bin/spark-submit" \
    --master yarn \
    --deploy-mode client \
    --name "cephfs-eco-spk-p0-v$version" \
    --class E3SparkBatchSuite \
    --jars "$CONNECTOR_JAR,$CEPH_JAVA_JAR" \
    --conf spark.executor.instances=3 \
    --conf spark.executor.cores=1 \
    --conf spark.executor.memory=1g \
    --conf spark.driver.memory=1g \
    --conf spark.sql.shuffle.partitions=12 \
    --conf spark.speculation=false \
    --conf spark.yarn.maxAppAttempts=1 \
    --conf "spark.hadoop.mapreduce.fileoutputcommitter.algorithm.version=$version" \
    --conf "spark.driver.extraJavaOptions=-Djava.library.path=$JNI_DIR" \
    --conf "spark.executor.extraJavaOptions=-Djava.library.path=$JNI_DIR" \
    --conf "spark.yarn.appMasterEnv.CEPH_JNI_PATH=$CEPH_JNI_PATH" \
    --conf "spark.yarn.appMasterEnv.LD_LIBRARY_PATH=$NATIVE_LIB_DIRS" \
    --conf "spark.executorEnv.CEPH_JNI_PATH=$CEPH_JNI_PATH" \
    --conf "spark.executorEnv.LD_LIBRARY_PATH=$NATIVE_LIB_DIRS" \
    "$APP_JAR" "$BASE" "$version"
  rc=$?
  if [ "$rc" -eq 0 ]; then
    echo "[eco-spark] PASS: committer v$version application completed"
  else
    echo "[eco-spark] FAIL: committer v$version application rc=$rc" >&2
    FAILURES=$((FAILURES + 1))
  fi
done

for version in 1 2; do
  for format in parquet orc; do
    output="$BASE/v$version/$format"
    if fs -test -e "$output/_SUCCESS" >/dev/null 2>&1 \
        && ! fs -test -e "$output/_temporary" >/dev/null 2>&1; then
      part_files="$(fs -ls "$output" 2>/dev/null | grep -c '/part-' || true)"
      if [ "$part_files" -gt 0 ]; then
        echo "[eco-spark] PASS: v$version $format success marker present, "\
"temporary absent, part-files=$part_files"
      else
        echo "[eco-spark] FAIL: v$version $format has no part files" >&2
        FAILURES=$((FAILURES + 1))
      fi
    else
      echo "[eco-spark] FAIL: v$version $format commit markers are invalid" >&2
      FAILURES=$((FAILURES + 1))
    fi
  done
done

if [ "$FAILURES" -eq 0 ]; then
  echo "[eco-spark] PASS: ECO-SPK-01 and ECO-SPK-02 completed"
  exit 0
fi

echo "[eco-spark] FAIL: ECO-SPK-01 and ECO-SPK-02 failures=$FAILURES" >&2
exit 1
