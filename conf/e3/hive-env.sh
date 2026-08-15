export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
export HADOOP_HOME=/opt/hadoop-e3
export HADOOP_CONF_DIR=/opt/hadoop-e3/etc/hadoop
export HIVE_CONF_DIR=/opt/hive-e3/conf
export CEPH_JNI_PATH=/usr/lib/jni/libcephfs_jni.so
export LD_LIBRARY_PATH=/usr/lib:/usr/lib/x86_64-linux-gnu:/usr/lib/jni
export HADOOP_CLIENT_OPTS="${HADOOP_CLIENT_OPTS} -Djava.library.path=/usr/lib:/usr/lib/x86_64-linux-gnu:/usr/lib/jni"
