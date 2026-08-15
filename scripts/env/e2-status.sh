#!/usr/bin/env bash
# Read-only readiness check for the three-node E2 CephFS cluster.
set -euo pipefail

CEPHADM="${CEPHADM:-/usr/local/sbin/cephadm}"
CEPH_IMAGE="${CEPH_IMAGE:-quay.io/ceph/ceph:v16.2.14}"
CEPH_FS="${CEPH_FS:-cephfs-e2}"

[ -x "$CEPHADM" ] || {
  echo "[e2-status] FAIL: cephadm not executable: $CEPHADM" >&2
  exit 1
}

"$CEPHADM" --image "$CEPH_IMAGE" shell -- env CEPH_FS="$CEPH_FS" bash -c '
set -euo pipefail
health="$(ceph health)"
mon_stat="$(ceph mon stat)"
osd_stat="$(ceph osd stat)"
meta_pool="cephfs.${CEPH_FS}.meta"
data_pool="cephfs.${CEPH_FS}.data"

ceph -s
ceph fs status "$CEPH_FS"
ceph osd tree

[ "$health" = "HEALTH_OK" ] || {
  echo "[e2-status] FAIL: $health" >&2
  exit 1
}
echo "$mon_stat" | grep -q "3 mons at" || {
  echo "[e2-status] FAIL: expected 3 MONs: $mon_stat" >&2
  exit 1
}
echo "$osd_stat" | grep -Eq "6 osds: 6 up.*6 in" || {
  echo "[e2-status] FAIL: expected 6 OSDs up/in: $osd_stat" >&2
  exit 1
}
[ "$(ceph osd pool get "$meta_pool" size | awk "{print \$2}")" = 3 ]
[ "$(ceph osd pool get "$meta_pool" min_size | awk "{print \$2}")" = 2 ]
[ "$(ceph osd pool get "$data_pool" size | awk "{print \$2}")" = 3 ]
[ "$(ceph osd pool get "$data_pool" min_size | awk "{print \$2}")" = 2 ]

echo "[e2-status] PASS: 3 MON, 6 OSD up/in, CephFS size=3/min_size=2, HEALTH_OK"
'
