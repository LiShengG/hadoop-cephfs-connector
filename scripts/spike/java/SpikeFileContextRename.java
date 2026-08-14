/**
 * SP-04 探针：FileContext 的 rename 语义（Spark Structured Streaming checkpoint、
 * Delta/Iceberg 的"原子创建"依赖它）。
 *
 * 被测的连接器事实：CephFs 仅覆写 getUriDefaultPort，其余全部委托 ——
 *   DelegateToFileSystem#renameInternal → fsImpl.rename(src, dst, Options.Rename.NONE)
 *   → FileSystem#rename(Path,Path,Rename...) 的**基类默认实现**（hadoop-3.3.6 已核对）：
 *       getFileLinkStatus(dst) 非空 且 未指定 OVERWRITE → 抛 FileAlreadyExistsException；
 *       否则调用 rename(src,dst)，返回 false 时抛 IOException("rename from X to Y failed.")。
 *
 * 因此有两个层次要分别判定：
 *   A. 非并发：目标已存在时是否为 FileAlreadyExistsException（Spark 据此判断"别人赢了"）；
 *   B. 并发竞态：检查与 rename 之间非原子，落败方拿到的**异常类型**是否仍然正确
 *      —— 若退化为普通 IOException，Spark 的 catch FileAlreadyExistsException 分支
 *      不会命中，会把"正常的并发落败"当成硬失败。
 *
 * 用法：SpikeFileContextRename <base 路径> [并发线程数]
 */
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Options;
import org.apache.hadoop.fs.Path;

public final class SpikeFileContextRename {

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("用法: SpikeFileContextRename <base> [并发线程数]");
      System.exit(2);
    }
    String base = args[0];
    int threads = args.length > 1 ? Integer.parseInt(args[1]) : 8;

    Configuration conf = new Configuration();
    Path basePath = new Path(base);
    FileSystem fs = basePath.getFileSystem(conf);
    FileContext fc = FileContext.getFileContext(basePath.toUri(), conf);
    fs.mkdirs(basePath);

    try {
      sp04a(fs, fc, basePath);
      if (sp04a2(fs, fc, basePath)) {
        sp04b(fs, fc, basePath, threads);
      } else {
        result("SP-04B", "INCONCLUSIVE",
            "普通 rename 到不存在的目标已经失败，无法据此判定并发竞态语义");
      }
    } finally {
      try {
        fs.delete(basePath, true);
      } catch (IOException ignored) {
        // 清理失败不影响结论
      }
      fs.close();
    }
  }

  // ── A2：普通 rename 到不存在的目标（并发测试的有效性基线）──────────────

  private static boolean sp04a2(FileSystem fs, FileContext fc, Path base) throws Exception {
    System.out.println();
    System.out.println("== SP-04A2：FileContext.rename 到不存在的目标 ==");
    Path src = new Path(base, "a2-src");
    Path dst = new Path(base, "a2-dst");
    touch(fs, src);

    String failure = null;
    try {
      fc.rename(src, dst);
    } catch (Exception e) {
      failure = e.getClass().getName() + ": " + e.getMessage();
    }

    boolean moved = failure == null && !fs.exists(src) && fs.exists(dst);
    if (moved) {
      result("SP-04A2", "REFUTED",
          "普通 FileContext.rename 成功，可继续判定并发竞态语义");
      return true;
    }

    String detail = failure != null ? failure : "调用返回成功但源/目标状态不符合 rename 语义";
    result("SP-04A2", "CONFIRMED",
        "普通 FileContext.rename 到不存在目标失败：" + detail);
    return false;
  }

  // ── A：非并发的"目标已存在" ───────────────────────────────────────────────

  private static void sp04a(FileSystem fs, FileContext fc, Path base) throws Exception {
    System.out.println("== SP-04A：FileContext.rename 目标已存在（无 OVERWRITE）==");
    Path src = new Path(base, "a-src");
    Path dst = new Path(base, "a-dst");
    touch(fs, src);
    touch(fs, dst);

    String type = "（未抛异常）";
    boolean renamed = false;
    try {
      fc.rename(src, dst);
      renamed = true;
    } catch (Exception e) {
      type = e.getClass().getName() + ": " + e.getMessage();
    }
    System.out.println("结果: " + type);

    if (renamed) {
      result("SP-04A", "CONFIRMED",
          "目标已存在时 FileContext.rename 竟然成功（静默覆盖）——"
              + "依赖'原子创建'的 Spark checkpoint / Delta 会丢数据");
    } else if (type.startsWith(FileAlreadyExistsException.class.getName())) {
      result("SP-04A", "REFUTED",
          "语义正确：抛 FileAlreadyExistsException，Spark 的原子创建假设成立");
    } else {
      result("SP-04A", "CONFIRMED",
          "异常类型不是 FileAlreadyExistsException 而是 " + type
              + " —— 调用方按类型分支的逻辑不会命中");
    }
  }

  // ── B：并发竞态下落败方的异常类型 ─────────────────────────────────────────

  private static void sp04b(final FileSystem fs, final FileContext fc, Path base,
      int threads) throws Exception {
    System.out.println();
    System.out.println("== SP-04B：" + threads + " 线程并发 rename 到同一目标 ==");
    final Path dst = new Path(base, "b-dst");
    final CyclicBarrier barrier = new CyclicBarrier(threads);
    List<Callable<String>> tasks = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      final Path src = new Path(base, "b-src-" + i);
      touch(fs, src);
      tasks.add(new Callable<String>() {
        @Override
        public String call() {
          try {
            barrier.await(30, TimeUnit.SECONDS);
            fc.rename(src, dst);
            return "OK";
          } catch (Exception e) {
            return e.getClass().getName() + ": " + e.getMessage();
          }
        }
      });
    }

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    int ok = 0;
    int alreadyExists = 0;
    int other = 0;
    StringBuilder others = new StringBuilder();
    try {
      for (Future<String> f : pool.invokeAll(tasks, 120, TimeUnit.SECONDS)) {
        String r = f.get();
        System.out.println("  线程结果: " + r);
        if ("OK".equals(r)) {
          ok++;
        } else if (r.startsWith(FileAlreadyExistsException.class.getName() + ":")) {
          alreadyExists++;
        } else {
          other++;
          if (others.indexOf(r) < 0) {
            others.append(r).append(' ');
          }
        }
      }
    } finally {
      pool.shutdownNow();
    }
    System.out.println("成功=" + ok + " FileAlreadyExists=" + alreadyExists
        + " 其他异常=" + other + " " + others);

    if (ok != 1) {
      result("SP-04B", "CONFIRMED",
          "并发 rename 成功数为 " + ok + "（期望恰好 1）——原子性不成立");
    } else if (other > 0) {
      result("SP-04B", "CONFIRMED",
          "落败方拿到的是 " + others.toString().trim()
              + " 而非 FileAlreadyExistsException —— 调用方会把正常的并发落败当硬失败");
    } else {
      result("SP-04B", "REFUTED",
          "恰好 1 个成功，其余均为 FileAlreadyExistsException，竞态语义正确");
    }
  }

  private static void touch(FileSystem fs, Path p) throws IOException {
    fs.create(p, true).close();
  }

  private static void result(String id, String verdict, String note) {
    System.out.println("RESULT|" + id + "|" + verdict + "|" + note);
  }
}
