/**
 * SP-05 探针：写入中文件的数据可见性（HBase WAL 回放、流式 tail 读依赖它）。
 *
 * 被测的连接器事实：CephInputStream 在 open 时刻由 lstat 拿到 fileLength 并**快照**，
 * read() 越过快照长度即返回 -1（见 CephInputStream 构造器与 read/fillBuffer）。
 *
 * 分两层判定，二者的生产含义完全不同：
 *   A. **已打开**的 reader 能否看到其后 hflush 的数据 —— 影响"边写边追读"（tail）；
 *   B. **新打开**的 reader 能否看到已 hflush 的数据 —— 若这一层也失败，
 *      则 HBase WAL 回放、Spark 读运行中日志等场景全部不可用，性质严重得多。
 *
 * 用法：SpikeVisibility <base 路径> [每段字节数]
 */
import java.io.EOFException;
import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

public final class SpikeVisibility {

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("用法: SpikeVisibility <base> [每段字节数]");
      System.exit(2);
    }
    String base = args[0];
    int chunk = args.length > 1 ? Integer.parseInt(args[1]) : 1024 * 1024;

    Configuration conf = new Configuration();
    Path basePath = new Path(base);
    FileSystem fs = basePath.getFileSystem(conf);
    fs.mkdirs(basePath);
    Path file = new Path(basePath, "wal");

    byte[] first = filled(chunk, (byte) 'A');
    byte[] second = filled(chunk, (byte) 'B');

    FSDataOutputStream out = null;
    FSDataInputStream early = null;
    try {
      out = fs.create(file, true);
      out.write(first);
      out.hflush();
      System.out.println("写入第 1 段 " + chunk + " 字节并 hflush");

      // 在追加之前打开 reader（模拟"边写边读"的消费者）
      early = fs.open(file);
      long lenAtOpen = fs.getFileStatus(file).getLen();
      int readFirst = readFully(early, first.length);
      System.out.println("已打开的 reader 首次读到 " + readFirst + " 字节"
          + "（open 时刻 getFileStatus 长度=" + lenAtOpen + "）");

      out.write(second);
      out.hflush();
      long lenAfter = fs.getFileStatus(file).getLen();
      System.out.println("写入第 2 段并 hflush，元数据长度=" + lenAfter);

      // A：已打开的 reader 继续读到 EOF（读到 EOF 而非按预期长度截断，
      // 否则首次短读会让第二次测量偏移，判定失真）
      int extra = readToEnd(early);
      long earlyTotal = (long) readFirst + extra;
      System.out.println("已打开的 reader 追加读到 " + extra + " 字节，累计 "
          + earlyTotal + "（期望累计 " + (2L * chunk) + "）");

      // B：新打开的 reader
      int fresh;
      try (FSDataInputStream in2 = fs.open(file)) {
        fresh = readToEnd(in2);
      }
      System.out.println("新打开的 reader 读到 " + fresh + " 字节（期望 "
          + (2L * chunk) + "）");

      long want = 2L * chunk;
      if (fresh < want) {
        result("SP-05", "CONFIRMED",
            "新打开的 reader 也只读到 " + fresh + "/" + want
                + " 字节 —— 已 hflush 的数据对新 reader 都不完整，"
                + "HBase WAL 回放类场景不可用（严重）");
      } else if (earlyTotal < want) {
        result("SP-05", "CONFIRMED",
            "新 reader 可读全（" + fresh + "），但已打开的 reader 累计只读到 "
                + earlyTotal + "/" + want + " 字节 —— open 时刻长度快照坐实，"
                + "边写边追读（tail）场景不可用");
      } else {
        result("SP-05", "REFUTED",
            "已打开与新打开的 reader 均能看到 hflush 后的数据");
      }
    } finally {
      closeQuietly(early);
      closeQuietly(out);
      try {
        fs.delete(basePath, true);
      } catch (IOException ignored) {
        // 清理失败不影响结论
      }
      fs.close();
    }
  }

  /** 一直读到 EOF，返回读到的字节数。 */
  private static int readToEnd(FSDataInputStream in) throws IOException {
    byte[] buf = new byte[64 * 1024];
    int total = 0;
    while (true) {
      int n;
      try {
        n = in.read(buf, 0, buf.length);
      } catch (EOFException e) {
        break;
      }
      if (n < 0) {
        break;
      }
      total += n;
    }
    return total;
  }

  /** 尽量读满 want 字节，返回实际读到的字节数（EOF 即停）。 */
  private static int readFully(FSDataInputStream in, int want) throws IOException {
    byte[] buf = new byte[Math.min(want, 64 * 1024)];
    int total = 0;
    while (total < want) {
      int n;
      try {
        n = in.read(buf, 0, Math.min(buf.length, want - total));
      } catch (EOFException e) {
        break;
      }
      if (n < 0) {
        break;
      }
      total += n;
    }
    return total;
  }

  private static byte[] filled(int n, byte b) {
    byte[] a = new byte[n];
    java.util.Arrays.fill(a, b);
    return a;
  }

  private static void closeQuietly(java.io.Closeable c) {
    if (c != null) {
      try {
        c.close();
      } catch (IOException ignored) {
        // 忽略
      }
    }
  }

  private static void result(String id, String verdict, String note) {
    System.out.println("RESULT|" + id + "|" + verdict + "|" + note);
  }
}
