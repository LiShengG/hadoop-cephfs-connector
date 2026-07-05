import com.ceph.fs.CephMount;
import com.ceph.fs.CephStat;

import java.util.Arrays;
import java.util.Random;

/**
 * T01 smoke test：不依赖 Hadoop，直接经 libcephfs Java 绑定（JNI）验证
 * mount -> mkdirs -> open+write 1MB -> read 回读比对 -> unlink -> rmdir -> unmount 全链路。
 *
 * 用法（由 scripts/smoke-test.sh 调用）:
 *   java -Djava.library.path=<含 libcephfs_jni.so 的目录> CephSmoke <ceph.conf 路径> [auth-id]
 *
 * 退出码 0 = 全部通过；非 0 = 失败（stderr 有原因）。
 */
public class CephSmoke {
    private static final int SIZE = 1024 * 1024; // 1MB

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: CephSmoke <ceph.conf 路径> [auth-id]");
            System.exit(2);
        }
        String confPath = args[0];
        String authId = args.length > 1 ? args[1] : "admin";

        String dir = "/t01-smoke-" + System.currentTimeMillis();
        String file = dir + "/data.bin";

        CephMount mount = new CephMount(authId);
        mount.conf_read_file(confPath);
        mount.mount("/");
        System.out.println("[smoke] mount OK (auth id=" + authId + ", conf=" + confPath + ")");

        try {
            // mkdirs
            mount.mkdirs(dir, 0755);
            System.out.println("[smoke] mkdirs OK: " + dir);

            // 写 1MB 随机数据
            byte[] wbuf = new byte[SIZE];
            new Random(42).nextBytes(wbuf);
            int wfd = mount.open(file, CephMount.O_WRONLY | CephMount.O_CREAT | CephMount.O_EXCL, 0644);
            long written = mount.write(wfd, wbuf, SIZE, 0);
            mount.fsync(wfd, false);
            mount.close(wfd);
            if (written != SIZE) {
                throw new RuntimeException("写入字节数不符: 期望 " + SIZE + " 实际 " + written);
            }
            System.out.println("[smoke] write OK: " + written + " bytes -> " + file);

            // lstat 校验文件大小
            CephStat st = new CephStat();
            mount.lstat(file, st);
            if (st.size != SIZE) {
                throw new RuntimeException("lstat 大小不符: 期望 " + SIZE + " 实际 " + st.size);
            }
            System.out.println("[smoke] lstat OK: size=" + st.size);

            // 回读比对
            byte[] rbuf = new byte[SIZE];
            int rfd = mount.open(file, CephMount.O_RDONLY, 0);
            long total = 0;
            while (total < SIZE) {
                byte[] chunk = new byte[SIZE - (int) total];
                long n = mount.read(rfd, chunk, chunk.length, total);
                if (n <= 0) {
                    throw new RuntimeException("read 提前结束: offset=" + total + " 返回 " + n);
                }
                System.arraycopy(chunk, 0, rbuf, (int) total, (int) n);
                total += n;
            }
            mount.close(rfd);
            if (!Arrays.equals(wbuf, rbuf)) {
                throw new RuntimeException("回读数据与写入数据不一致!");
            }
            System.out.println("[smoke] read OK: " + total + " bytes, 内容与写入一致");

            // 删除
            mount.unlink(file);
            mount.rmdir(dir);
            System.out.println("[smoke] unlink + rmdir OK");
        } finally {
            mount.unmount();
            System.out.println("[smoke] unmount OK");
        }

        System.out.println("[smoke] PASS: Java -> JNI -> libcephfs -> CephFS 全链路验证通过");
    }
}
