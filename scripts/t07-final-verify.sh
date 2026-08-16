#!/usr/bin/env bash
#
# T07 终验（归档见 docs/archive/2026-07.md）：开干净 shell（env -i，不继承会话环境），
# 仅按 docs/DEPLOY.md 的步骤操作，从部署包 tar.gz 出发完成
# ls / put / cat / rm 的 ceph:/// 全流程（FsShell 方式，与 T06 CLI 方案一致）。
#
# 前置：dist/hadoop-cephfs-1.0.0.tar.gz 已由 scripts/make-dist.sh 产出；
#       vstart 集群运行中（scripts/cluster-up.sh）。
#
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ_ROOT="$(dirname "$SCRIPT_DIR")"

TARBALL="$PROJ_ROOT/dist/hadoop-cephfs-1.0.0.tar.gz"
[ -f "$TARBALL" ] || { echo "缺 $TARBALL（先跑 scripts/make-dist.sh）" >&2; exit 1; }

# env -i：清空全部环境（不继承会话的 LD_LIBRARY_PATH/CEPH_ARGS 等）；
# 干净 shell 内仅按 DEPLOY.md 操作。HOME 为 shell 基本前提（mvn 定位本地仓库）。
exec env -i HOME="$HOME" PROJ_ROOT="$PROJ_ROOT" TARBALL="$TARBALL" \
  bash --noprofile --norc <<'CLEANSHELL'
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin  # 系统默认 PATH
echo "== 干净 shell 环境（env 全量）=="; env | sort; echo

WORK=$(mktemp -d /tmp/t07-deploy.XXXXXX)
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

echo '== DEPLOY.md §2.1 解包 =='
tar xzf "$TARBALL"
ls -l hadoop-cephfs-1.0.0/

echo '== DEPLOY.md §3 core-site.xml（5 必配项，vstart 值；§6 调试构建加 lockdep=false）=='
mkdir conf
cat > conf/core-site.xml <<'EOF'
<?xml version="1.0"?>
<configuration>
  <property><name>fs.ceph.impl</name><value>org.apache.hadoop.fs.ceph.CephFileSystem</value></property>
  <property><name>fs.AbstractFileSystem.ceph.impl</name><value>org.apache.hadoop.fs.ceph.CephFs</value></property>
  <property><name>fs.defaultFS</name><value>ceph:///</value></property>
  <property><name>ceph.conf.file</name><value>/home/lsh/code/ceph/build/ceph.conf</value></property>
  <property><name>ceph.auth.id</name><value>admin</value></property>
  <property><name>ceph.conf.options</name><value>lockdep=false</value></property>
</configuration>
EOF
cat conf/core-site.xml

echo '== DEPLOY.md §5.2-1 组装 Hadoop classpath =='
( cd "$PROJ_ROOT/hadoop-cephfs" && mvn -q dependency:build-classpath -Dmdep.outputFile="$WORK/cp.txt" )
echo "classpath 条目数: $(tr ':' '\n' < cp.txt | wc -l)"

echo '== DEPLOY.md §2.3-A + §5.2-3 native 路径与 hfs 入口 =='
export LD_LIBRARY_PATH=/home/lsh/code/ceph/build/lib
D="$WORK/hadoop-cephfs-1.0.0"
hfs() { java -cp "$WORK/conf:$D/hadoop-cephfs-1.0.0.jar:$D/libcephfs.jar:$(cat "$WORK/cp.txt")" \
             -Djava.library.path="$D" org.apache.hadoop.fs.FsShell "$@" 2> >(grep -v log4j >&2); }

echo 'hello cephfs' > "$WORK/hello.txt"

run() { echo "\$ hadoop fs $*"; hfs "$@"; echo "rc=$?"; echo; }

run -mkdir -p ceph:///verify/dir
run -ls ceph:///verify
run -put "$WORK/hello.txt" ceph:///verify/dir/hello.txt
run -ls ceph:///verify/dir
run -cat ceph:///verify/dir/hello.txt
run -rm -r ceph:///verify
echo '$ hadoop fs -test -e ceph:///verify（应 rc=1）'
hfs -test -e ceph:///verify && { echo 'FAIL: verify 仍存在'; exit 1; } || echo "rc=$?"

echo
echo '[t07-final-verify] PASS: 干净 shell 按 DEPLOY.md 完成 ls/put/cat/rm 全流程'
CLEANSHELL
