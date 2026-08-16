# Deployment

This procedure installs the connector into an existing Hadoop runtime. Build and test instructions
are in [DEVELOP.md](DEVELOP.md).

## Preconditions

- A Hadoop runtime compatible with the version declared in
  [`pom.xml`](../hadoop-cephfs/pom.xml).
- A Ceph client configuration that can reach the target filesystem.
- A least-privilege cephx identity and readable keyring.
- A `libcephfs.jar`, `libcephfs_jni.so`, and native `libcephfs` built for compatible Ceph versions.
- A maintenance window or isolated client node for replacing connector/native artifacts.

The Java and JNI files must be deployed as a matching pair. See
[ADR-0002](adr/0002-package-ceph-java-and-jni-separately.md).

## Install artifacts

Extract the archive produced by `scripts/make-dist.sh`:

```bash
ARCHIVE="$(find . -maxdepth 1 -type f -name 'hadoop-cephfs-*.tar.gz' -print -quit)"
test -n "$ARCHIVE"
tar xzf "$ARCHIVE"
DIST_DIR="${ARCHIVE%.tar.gz}"
cd "$DIST_DIR"
CONNECTOR_JAR="$(find . -maxdepth 1 -type f -name 'hadoop-cephfs-*.jar' -print -quit)"
test -n "$CONNECTOR_JAR"
```

Choose one classpath method.

### Hadoop common library directory

```bash
install -m 0644 "$CONNECTOR_JAR" \
  "$HADOOP_HOME/share/hadoop/common/lib/hadoop-cephfs-connector.jar"
install -m 0644 libcephfs.jar \
  "$HADOOP_HOME/share/hadoop/common/lib/libcephfs.jar"
```

### Explicit client classpath

```bash
CONNECTOR_JAR="$(find /opt/hadoop-cephfs -maxdepth 1 -type f -name 'hadoop-cephfs-*.jar' -print -quit)"
test -n "$CONNECTOR_JAR"
export HADOOP_CLASSPATH="$CONNECTOR_JAR:/opt/hadoop-cephfs/libcephfs.jar${HADOOP_CLASSPATH:+:$HADOOP_CLASSPATH}"
```

Use one method consistently on every NodeManager, history server, gateway, and client that can load
the connector. Mixed jars during a rolling update are not verified; see
[LIM-007](KNOWN-LIMITATIONS.md#lim-007-unsupported-filesystem-capabilities).

## Configure native libraries

The JVM must find the JNI library, and the operating-system loader must find the libraries on which
it depends.

```bash
export JAVA_LIBRARY_PATH=/opt/hadoop-cephfs
export LD_LIBRARY_PATH="/opt/hadoop-cephfs:/usr/lib/ceph${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
```

Pass the Java path to a direct JVM invocation with:

```bash
java -Djava.library.path="$JAVA_LIBRARY_PATH" ...
```

For Hadoop daemons, set both variables through the distribution's daemon environment mechanism and
restart the affected process. `CEPH_JNI_PATH` may contain the full JNI file path where supported by
the Ceph binding. Do not append multiple file paths to that single-file setting.

## Configure Hadoop

Start from [`conf/core-site.xml.example`](../conf/core-site.xml.example). The configuration keys and
defaults are owned by
[`CephConfigKeys.java`](../hadoop-cephfs/src/main/java/org/apache/hadoop/fs/ceph/CephConfigKeys.java).
A minimal structure is:

```xml
<property>
  <name>fs.ceph.impl</name>
  <value>org.apache.hadoop.fs.ceph.CephFileSystem</value>
</property>
<property>
  <name>fs.AbstractFileSystem.ceph.impl</name>
  <value>org.apache.hadoop.fs.ceph.CephFs</value>
</property>
<property>
  <name>fs.defaultFS</name>
  <value>ceph:///</value>
</property>
<property>
  <name>ceph.conf.file</name>
  <value>/etc/ceph/ceph.conf</value>
</property>
<property>
  <name>ceph.auth.id</name>
  <value>hadoop</value>
</property>
<property>
  <name>ceph.auth.keyring</name>
  <value>/etc/ceph/ceph.client.hadoop.keyring</value>
</property>
```

When HDFS remains `fs.defaultFS`, use an explicit Ceph authority for `FileContext` paths as described
in [LIM-008](KNOWN-LIMITATIONS.md#lim-008-mixed-default-filesystem-requires-explicit-ceph-authority).

## Ceph authorization and data protection

- Use a dedicated client identity. Do not deploy `client.admin` as an application credential.
- Restrict monitor, MDS, OSD, filesystem, pool, and path access to the workload's required scope.
- Store the keyring outside the application archive and make it readable only by the service group.
- Do not print keyring contents or pass the secret itself on a command line.
- Hadoop UGI is not propagated as a Ceph identity. Design tenant isolation with separate cephx
  identities and restricted roots; see
  [LIM-003](KNOWN-LIMITATIONS.md#lim-003-authentication-and-delegation-token-boundary).
- Never run environment provisioning or disk-zap commands from this deployment procedure. E2/E3
  device rules are defined in their environment documents.

## Validate

Confirm class loading and read access:

```bash
hadoop fs -ls ceph:///
```

Use a test-owned path for a write/read/delete check:

```bash
printf '%s\n' 'cephfs validation' > /tmp/cephfs-validation.txt
hadoop fs -mkdir -p ceph:///validation
hadoop fs -put -f /tmp/cephfs-validation.txt ceph:///validation/input.txt
hadoop fs -cat ceph:///validation/input.txt
hadoop fs -rm -r ceph:///validation
```

For a release decision, record the exact commands and output against OPS-01 or OPS-10 in a dated
report. A successful `-ls` alone is not production-readiness evidence.

## Rollback

1. Stop new jobs that use `ceph://` and wait for active client JVMs to exit.
2. Restore the previous connector jar and its matching Ceph Java/JNI pair on every affected node.
3. Restore the previous Hadoop configuration and daemon environment.
4. Restart the affected Hadoop services.
5. Run the read-only `-ls` check, then a test-owned write/read/delete validation.

Do not delete or rewrite CephFS data during connector rollback. If mixed connector versions have
already been loaded, restart the client or daemon rather than relying on classloader replacement.

## Troubleshooting

| Symptom | Check | Action |
|---|---|---|
| `UnsupportedFileSystemException` | `fs.ceph.impl` and `fs.AbstractFileSystem.ceph.impl` | Merge both implementation properties into the active configuration |
| JNI library not found | `java.library.path` or `CEPH_JNI_PATH` | Point to the directory or exact JNI file used by that launcher |
| Native dependency not found | `ldd libcephfs_jni.so` and loader path | Add the matching native library directory and restart the JVM |
| Mount timeout or authentication failure | Ceph config path, network, client ID, keyring permission, caps | Use the target environment's read-only status commands and least-privilege identity |
| Wrong filesystem/authority error | `fs.defaultFS` and explicit URI | Follow [LIM-008](KNOWN-LIMITATIONS.md#lim-008-mixed-default-filesystem-requires-explicit-ceph-authority) |
| Owner/group or permission precheck differs | Numeric ownership and UGI assumptions | Follow [LIM-001](KNOWN-LIMITATIONS.md#lim-001-owner-and-group-mapping) |
| Later jobs pause in native calls | MDS sessions and client process lifecycle | Follow [LIM-006](KNOWN-LIMITATIONS.md#lim-006-native-session-lifecycle); never evict a live client blindly |
| DistCp update skips changed content | Checksum behavior | Follow [LIM-004](KNOWN-LIMITATIONS.md#lim-004-file-checksum-is-unavailable) |

All current restrictions are canonical in [KNOWN-LIMITATIONS.md](KNOWN-LIMITATIONS.md).
