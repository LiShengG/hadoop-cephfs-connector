/**
 * SP-07 探针 + 能力面实测清单。
 *
 * SP-07（DistCp 校验）被测事实：连接器未覆写 getFileChecksum → 基类返回 null。
 * 对照 hadoop-3.3.6 DistCpUtils#checksumsAreEqual（已核对源码）：
 *   sourceChecksum == null || targetChecksum == null → 返回 ChecksumComparison.INCOMPATIBLE
 * 而调用方 DistCpUtils#compareFileLengthsAndChecksums 里：
 *   boolean checksumResult = !checksumComparison.equals(ChecksumComparison.FALSE);
 * 即 INCOMPATIBLE **被当作校验通过** —— 校验不是"失败"，而是**静默跳过**。
 * 这正是 TEST-PLAN 判定口径里最忌讳的"看起来成功了但没验"。
 *
 * 其余部分把 TEST-CASES-ECO.md §2 的"API 支撑面盘点"从纸面推断变成实测清单：
 * hasPathCapability 全常量、流的 StreamCapabilities、truncate/concat/setReplication/
 * getTrashRoot/getServerDefaults 的真实表现。
 *
 * 用法：SpikeCapabilities <base 路径>
 */
import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonPathCapabilities;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileChecksum;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.StreamCapabilities;

public final class SpikeCapabilities {

  private static final String[] CAPABILITIES = {
      CommonPathCapabilities.FS_ACLS,
      CommonPathCapabilities.FS_APPEND,
      CommonPathCapabilities.FS_CHECKSUMS,
      CommonPathCapabilities.FS_CONCAT,
      CommonPathCapabilities.FS_PATHHANDLES,
      CommonPathCapabilities.FS_PERMISSIONS,
      CommonPathCapabilities.FS_READ_ONLY_CONNECTOR,
      CommonPathCapabilities.FS_SNAPSHOTS,
      CommonPathCapabilities.FS_STORAGEPOLICY,
      CommonPathCapabilities.FS_SYMLINKS,
      CommonPathCapabilities.FS_TRUNCATE,
      CommonPathCapabilities.FS_XATTRS,
      CommonPathCapabilities.FS_MULTIPART_UPLOADER,
  };

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("用法: SpikeCapabilities <base>");
      System.exit(2);
    }
    Configuration conf = new Configuration();
    Path base = new Path(args[0]);
    FileSystem fs = base.getFileSystem(conf);
    fs.mkdirs(base);
    Path file = new Path(base, "probe.dat");

    try {
      byte[] data = new byte[64 * 1024];
      java.util.Arrays.fill(data, (byte) 'x');

      System.out.println("== 流能力（StreamCapabilities）==");
      try (FSDataOutputStream out = fs.create(file, true)) {
        out.write(data);
        for (String c : new String[] {StreamCapabilities.HFLUSH, StreamCapabilities.HSYNC,
            StreamCapabilities.DROPBEHIND, StreamCapabilities.IOSTATISTICS}) {
          System.out.println("  out." + c + " = " + out.hasCapability(c));
        }
      }

      System.out.println();
      System.out.println("== 路径能力（hasPathCapability）==");
      for (String c : CAPABILITIES) {
        boolean v;
        try {
          v = fs.hasPathCapability(file, c);
        } catch (Exception e) {
          System.out.println("  " + c + " → 异常 " + e.getClass().getSimpleName());
          continue;
        }
        System.out.println("  " + c + " = " + v);
      }

      System.out.println();
      System.out.println("== 单项 API 实测 ==");
      System.out.println("  setReplication(3) = " + fs.setReplication(file, (short) 3));
      System.out.println("  getDefaultReplication = " + fs.getDefaultReplication(file));
      System.out.println("  getDefaultBlockSize   = " + fs.getDefaultBlockSize(file));
      System.out.println("  getTrashRoot          = " + fs.getTrashRoot(file));
      System.out.println("  truncate  → " + probe(() -> String.valueOf(fs.truncate(file, 16))));
      System.out.println("  concat    → " + probe(() -> {
        Path other = new Path(base, "concat-src.dat");
        fs.create(other, true).close();
        fs.concat(file, new Path[] {other});
        return "成功";
      }));

      System.out.println();
      System.out.println("== SP-07：getFileChecksum 与 DistCp 校验判定 ==");
      FileChecksum sum = fs.getFileChecksum(file);
      System.out.println("  getFileChecksum = " + sum);
      String verdict = distCpVerdict(sum, sum);
      System.out.println("  复刻 DistCpUtils#checksumsAreEqual 判定 = " + verdict);

      if (sum == null) {
        result("SP-07", "CONFIRMED",
            "getFileChecksum 返回 null → DistCp 判定为 INCOMPATIBLE，"
                + "而 INCOMPATIBLE 被当作校验通过（静默跳过校验，非失败）；"
                + "-update/校验实际不具备内容比对能力");
      } else {
        result("SP-07", "REFUTED", "getFileChecksum 返回 " + sum + "，可参与 DistCp 校验");
      }
    } finally {
      try {
        fs.delete(base, true);
      } catch (IOException ignored) {
        // 清理失败不影响结论
      }
      fs.close();
    }
  }

  /** 复刻 DistCpUtils#checksumsAreEqual 的三态判定（hadoop-3.3.6 已核对）。 */
  private static String distCpVerdict(FileChecksum src, FileChecksum dst) {
    if (src == null || dst == null) {
      return "INCOMPATIBLE（调用方按 !FALSE 处理 → 视为通过，即跳过校验）";
    }
    return src.equals(dst) ? "TRUE" : "FALSE";
  }

  private interface Probe {
    String run() throws Exception;
  }

  private static String probe(Probe p) {
    try {
      return p.run();
    } catch (Exception e) {
      return e.getClass().getName() + ": " + e.getMessage();
    }
  }

  private static void result(String id, String verdict, String note) {
    System.out.println("RESULT|" + id + "|" + verdict + "|" + note);
  }
}
