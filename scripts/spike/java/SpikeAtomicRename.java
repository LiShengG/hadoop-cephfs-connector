/*
 * SP-04C 实验探针：Ceph 16 缺少 rename-noreplace 时，验证 hard-link 抢占目标
 * 是否能为普通文件提供原子 no-replace 提交原语。
 */
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.ceph.fs.CephFileAlreadyExistsException;
import com.ceph.fs.CephMount;
import com.ceph.fs.CephStat;

public final class SpikeAtomicRename {
  private SpikeAtomicRename() {
  }

  public static void main(String[] args) throws Exception {
    if (args.length > 0 && "--worker".equals(args[0])) {
      worker(args);
      return;
    }
    if (args.length < 2) {
      System.err.println("用法: SpikeAtomicRename <ceph.conf> <base> [线程数]");
      System.exit(2);
    }
    String conf = args[0];
    String base = args[1];
    int threads = args.length >= 3 ? Integer.parseInt(args[2]) : 8;

    CephMount mount = new CephMount("admin");
    mount.conf_read_file(conf);
    mount.mount(null);
    try {
      mount.mkdirs(base, 0755);
      raceLinks(mount, base, threads);
      raceIndependentJvms(mount, conf, base, threads);
      crashAndRecoverInNewJvm(mount, conf, base);
      exposeCrashWindow(mount, base);
      directoryLimit(mount, base);
    } finally {
      cleanup(mount, base);
      mount.unmount();
    }
  }

  private static void raceIndependentJvms(CephMount mount, String conf,
      String base, int workers) throws Exception {
    String destination = base + "/jvm-dst";
    java.nio.file.Path coordination = Files.createTempDirectory("sp04c-jvm-");
    java.nio.file.Path start = coordination.resolve("start");
    List<Process> processes = new ArrayList<>();
    try {
      for (int i = 0; i < workers; i++) {
        String source = base + "/jvm-src-" + i;
        int fd = mount.open(source,
            CephMount.O_WRONLY | CephMount.O_CREAT | CephMount.O_EXCL, 0644);
        mount.write(fd, new byte[] {(byte) i}, 1, -1);
        mount.close(fd);

        java.nio.file.Path ready = coordination.resolve("ready-" + i);
        java.nio.file.Path result = coordination.resolve("result-" + i);
        ProcessBuilder builder = workerProcess("race", conf, source, destination,
            ready.toString(), start.toString(), result.toString());
        builder.redirectErrorStream(true);
        builder.redirectOutput(coordination.resolve("worker-" + i + ".log").toFile());
        processes.add(builder.start());
      }

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (countPrefix(coordination, "ready-") != workers
          && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      if (countPrefix(coordination, "ready-") != workers) {
        throw new IllegalStateException("等待独立 JVM mount/ready 超时");
      }
      Files.write(start, new byte[] {1});

      int success = 0;
      int exists = 0;
      int other = 0;
      for (int i = 0; i < workers; i++) {
        Process process = processes.get(i);
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          other++;
          continue;
        }
        String value = new String(Files.readAllBytes(
            coordination.resolve("result-" + i)), StandardCharsets.UTF_8);
        if ("OK".equals(value)) {
          success++;
        } else if ("EXISTS".equals(value)) {
          exists++;
        } else {
          other++;
          System.out.println("  JVM " + i + " 非预期结果: " + value);
        }
      }
      System.out.println("独立 JVM 竞争: 成功=" + success + " EEXIST=" + exists
          + " 其他=" + other);
      if (success == 1 && exists == workers - 1 && other == 0) {
        result("SP-04C-MULTI-JVM", "REFUTED",
            "独立 JVM/独立 Ceph mount 竞争仍恰好一个成功者");
      } else {
        result("SP-04C-MULTI-JVM", "CONFIRMED",
            "跨客户端 hard-link 抢占未满足 no-replace 契约");
      }
    } finally {
      for (Process process : processes) {
        if (process.isAlive()) {
          process.destroyForcibly();
        }
      }
      deleteLocalTree(coordination);
    }
  }

  private static void crashAndRecoverInNewJvm(CephMount mount, String conf,
      String base) throws Exception {
    String source = base + "/crash-src";
    String destination = base + "/crash-dst";
    int fd = mount.open(source,
        CephMount.O_WRONLY | CephMount.O_CREAT | CephMount.O_EXCL, 0644);
    byte[] payload = "crash-safe-payload".getBytes(StandardCharsets.UTF_8);
    mount.write(fd, payload, payload.length, -1);
    mount.close(fd);

    java.nio.file.Path coordination = Files.createTempDirectory("sp04c-crash-");
    try {
      java.nio.file.Path crashResult = coordination.resolve("crash-result");
      ProcessBuilder crash = workerProcess("crash", conf, source, destination,
          "-", "-", crashResult.toString());
      crash.redirectErrorStream(true);
      crash.redirectOutput(coordination.resolve("crash.log").toFile());
      Process crashed = crash.start();
      int crashExit = crashed.waitFor();

      java.nio.file.Path recoveryResult = coordination.resolve("recovery-result");
      ProcessBuilder recovery = workerProcess("recover", conf, source, destination,
          "-", "-", recoveryResult.toString());
      recovery.redirectErrorStream(true);
      recovery.redirectOutput(coordination.resolve("recover.log").toFile());
      Process recovered = recovery.start();
      int recoveryExit = recovered.waitFor();
      String value = Files.exists(recoveryResult)
          ? new String(Files.readAllBytes(recoveryResult), StandardCharsets.UTF_8)
          : "NO_RESULT";

      System.out.println("崩溃恢复: crashExit=" + crashExit + " recoveryExit="
          + recoveryExit + " result=" + value);
      if (crashExit == 77 && recoveryExit == 0 && "BOTH_RECOVERED".equals(value)) {
        result("SP-04C-CRASH", "REFUTED",
            "link 后强制退出会双名残留；新 JVM 可识别并仅清理源，目标保持可用");
      } else {
        result("SP-04C-CRASH", "CONFIRMED", "崩溃后的提交状态无法安全恢复");
      }
    } finally {
      deleteLocalTree(coordination);
    }
  }

  private static ProcessBuilder workerProcess(String mode, String conf,
      String source, String destination, String ready, String start, String result) {
    String java = System.getProperty("java.home") + File.separator + "bin"
        + File.separator + "java";
    return new ProcessBuilder(Arrays.asList(java,
        "-Djava.library.path=" + System.getProperty("java.library.path"),
        "-cp", System.getProperty("java.class.path"),
        SpikeAtomicRename.class.getName(), "--worker", mode, conf, source,
        destination, ready, start, result));
  }

  private static void worker(String[] args) throws Exception {
    String mode = args[1];
    String conf = args[2];
    String source = args[3];
    String destination = args[4];
    java.nio.file.Path ready = Paths.get(args[5]);
    java.nio.file.Path start = Paths.get(args[6]);
    java.nio.file.Path resultFile = Paths.get(args[7]);

    CephMount mount = new CephMount("admin");
    mount.conf_read_file(conf);
    mount.mount(null);
    if ("crash".equals(mode)) {
      mount.link(source, destination);
      Files.write(resultFile, "LINKED".getBytes(StandardCharsets.UTF_8));
      Runtime.getRuntime().halt(77);
    }
    try {
      if ("recover".equals(mode)) {
        boolean sourceExists = exists(mount, source);
        boolean destinationExists = exists(mount, destination);
        if (sourceExists && destinationExists) {
          mount.unlink(source);
          Files.write(resultFile, "BOTH_RECOVERED".getBytes(StandardCharsets.UTF_8));
        } else {
          Files.write(resultFile,
              ("source=" + sourceExists + ",destination=" + destinationExists)
                  .getBytes(StandardCharsets.UTF_8));
        }
        return;
      }

      Files.write(ready, new byte[] {1});
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (!Files.exists(start) && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      String value;
      try {
        mount.link(source, destination);
        mount.unlink(source);
        value = "OK";
      } catch (Exception other) {
        value = other instanceof CephFileAlreadyExistsException
            ? "EXISTS"
            : other.getClass().getName() + ":" + other.getMessage();
      }
      Files.write(resultFile, value.getBytes(StandardCharsets.UTF_8));
    } finally {
      mount.unmount();
    }
  }

  private static boolean exists(CephMount mount, String path) throws Exception {
    try {
      mount.lstat(path, new CephStat());
      return true;
    } catch (FileNotFoundException missing) {
      return false;
    }
  }

  private static int countPrefix(java.nio.file.Path directory, String prefix)
      throws Exception {
    try (java.util.stream.Stream<java.nio.file.Path> paths = Files.list(directory)) {
      return (int) paths.filter(path -> path.getFileName().toString().startsWith(prefix))
          .count();
    }
  }

  private static void deleteLocalTree(java.nio.file.Path root) {
    try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(root)) {
      paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (Exception ignored) {
          // best-effort local coordination cleanup
        }
      });
    } catch (Exception ignored) {
      // best-effort local coordination cleanup
    }
  }

  private static void raceLinks(final CephMount mount, String base, int threads)
      throws Exception {
    final String destination = base + "/link-dst";
    final CyclicBarrier barrier = new CyclicBarrier(threads);
    List<Callable<String>> tasks = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      final String source = base + "/link-src-" + i;
      int fd = mount.open(source,
          CephMount.O_WRONLY | CephMount.O_CREAT | CephMount.O_EXCL, 0644);
      mount.write(fd, new byte[] {(byte) i}, 1, -1);
      mount.close(fd);
      tasks.add(new Callable<String>() {
        @Override
        public String call() {
          try {
            barrier.await(30, TimeUnit.SECONDS);
            mount.link(source, destination);
            mount.unlink(source);
            return "OK";
          } catch (Exception e) {
            return e.getClass().getName() + ": " + e.getMessage();
          }
        }
      });
    }

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    int successes = 0;
    int exists = 0;
    int other = 0;
    try {
      for (Future<String> future : pool.invokeAll(tasks, 120, TimeUnit.SECONDS)) {
        String value = future.get();
        if ("OK".equals(value)) {
          successes++;
        } else if (value.startsWith(CephFileAlreadyExistsException.class.getName())) {
          exists++;
        } else {
          other++;
          System.out.println("  非预期线程结果: " + value);
        }
      }
    } finally {
      pool.shutdownNow();
    }

    System.out.println("hard-link 竞争: 成功=" + successes + " EEXIST=" + exists
        + " 其他=" + other);
    if (successes == 1 && exists == threads - 1 && other == 0) {
      result("SP-04C-FILE", "REFUTED",
          "hard-link 抢占目标恰好一个成功者，可作为普通文件 no-replace 原语");
    } else {
      result("SP-04C-FILE", "CONFIRMED",
          "hard-link 抢占未满足恰好一个成功者的原子契约");
    }
  }

  private static void exposeCrashWindow(CephMount mount, String base) throws Exception {
    String source = base + "/window-src";
    String destination = base + "/window-dst";
    int fd = mount.open(source,
        CephMount.O_WRONLY | CephMount.O_CREAT | CephMount.O_EXCL, 0644);
    mount.close(fd);

    mount.link(source, destination);
    boolean sourceVisible = false;
    boolean destinationVisible = false;
    for (String entry : mount.listdir(base)) {
      sourceVisible |= "window-src".equals(entry);
      destinationVisible |= "window-dst".equals(entry);
    }
    System.out.println("link 后、unlink 前: source=" + sourceVisible
        + " destination=" + destinationVisible);
    if (sourceVisible && destinationVisible) {
      result("SP-04C-WINDOW", "CONFIRMED",
          "进程若在 link 后崩溃，源和目标会同时残留，但目标内容已完整提交");
    } else {
      result("SP-04C-WINDOW", "REFUTED", "未观察到预期的双名可见窗口");
    }
    mount.unlink(source);
  }

  private static void directoryLimit(CephMount mount, String base) throws Exception {
    String source = base + "/dir-src";
    String destination = base + "/dir-dst";
    mount.mkdir(source, 0755);
    try {
      mount.link(source, destination);
      result("SP-04C-DIR", "CONFIRMED", "CephFS 意外允许目录硬链接");
    } catch (Exception expected) {
      System.out.println("目录 hard-link 结果: " + expected.getClass().getName()
          + ": " + expected.getMessage());
      result("SP-04C-DIR", "REFUTED",
          "目录不能使用 hard-link + unlink 方案，仍缺原子 no-replace rename");
    }
  }

  private static void cleanup(CephMount mount, String base) {
    try {
      String[] entries = mount.listdir(base);
      if (entries != null) {
        for (String entry : entries) {
          String path = base + "/" + entry;
          try {
            mount.unlink(path);
          } catch (Exception notFile) {
            try {
              mount.rmdir(path);
            } catch (Exception ignored) {
              // best-effort experiment cleanup
            }
          }
        }
      }
      mount.rmdir(base);
    } catch (Exception ignored) {
      // best-effort experiment cleanup
    }
  }

  private static void result(String id, String verdict, String note) {
    System.out.println("RESULT|" + id + "|" + verdict + "|" + note);
  }
}
