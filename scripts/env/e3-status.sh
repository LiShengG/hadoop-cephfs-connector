#!/usr/bin/env bash
# Read-only readiness check for the E3 Hadoop/YARN cluster. Run on node-44.
set -euo pipefail

HADOOP_HOME="${HADOOP_HOME:-/opt/hadoop-e3}"
HADOOP_CONF_DIR="${HADOOP_CONF_DIR:-$HADOOP_HOME/etc/hadoop}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-11-openjdk-amd64}"
SERVICE_USER="${SERVICE_USER:-hadoope3}"
NODE26_MAC="${NODE26_MAC:-00:0c:29:dc:2f:46}"
export HADOOP_HOME HADOOP_CONF_DIR JAVA_HOME

fail() {
  echo "[e3-status] FAIL: $*" >&2
  exit 1
}

[ "$(hostname -s)" = node-44 ] || fail "run this check on node-44"
[ -x "$HADOOP_HOME/bin/hdfs" ] || fail "Hadoop not found at $HADOOP_HOME"

neighbor="$(ip neigh show 10.20.40.26 dev ens192 || true)"
echo "$neighbor" | grep -qi "lladdr $NODE26_MAC" ||
  fail "10.20.40.26 does not resolve to expected MAC $NODE26_MAC: $neighbor"

for process in \
  org.apache.hadoop.hdfs.server.namenode.NameNode \
  org.apache.hadoop.hdfs.server.datanode.DataNode \
  org.apache.hadoop.yarn.server.resourcemanager.ResourceManager \
  org.apache.hadoop.yarn.server.nodemanager.NodeManager \
  org.apache.hadoop.mapreduce.v2.hs.JobHistoryServer; do
  pgrep -u "$SERVICE_USER" -f "$process" >/dev/null || fail "node-44 process is missing: $process"
done

report="$(sudo -u "$SERVICE_USER" "$HADOOP_HOME/bin/hdfs" dfsadmin -report)"
echo "$report" | grep -q "Live datanodes (3)" || fail "HDFS does not have 3 live DataNodes"

nodes="$(sudo -u "$SERVICE_USER" "$HADOOP_HOME/bin/yarn" node -list)"
[ "$(echo "$nodes" | grep -c RUNNING)" -eq 3 ] || fail "YARN does not have 3 RUNNING NodeManagers"

for port in 8020 8032 8088 9870 10020 19888; do
  ss -lnt | grep -q ":$port " || fail "node-44 port $port is not listening"
done

echo "[e3-status] PASS: HDFS 3 DataNodes, YARN 3 NodeManagers, RM/NN/JHS endpoints ready"
