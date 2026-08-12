/**
 * SP-08 探针：YARN 日志聚合的远程目录准备序列。
 *
 * 复刻 hadoop-3.3.6 LogAggregationFileController 的真实动作（已核对源码）：
 *   TLDIR_PERMISSIONS = 01777（顶层远程日志目录）
 *   APP_DIR_PERMISSIONS = 0770（应用目录）
 *   verifyAndCreateRemoteLogDir():
 *     ① getFileStatus(root).getPermission() != 01777 → 仅 **WARN**（不致命）
 *     ② 不存在则 mkdirs(root, 01777) → setPermission(root, 01777)
 *        （setPermission 抛 UnsupportedOperationException 时置 fsSupportsChmod=false）
 *     ③ setOwner(root, loginUser.getShortUserName(), primaryGroupName)
 *        —— **只捕获 UnsupportedOperationException**
 *   checkExists(fs, appDir, 0770): 权限不符即 setPermission 纠正
 *
 * 关注点不是"会不会抛异常"，而是三件事：
 *   a. mkdirs 受 umask 影响后，最终权限是否真的落到 01777（sticky 位是否保留）；
 *   b. setOwner(用户名, 组名) 在连接器里静默跳过（不抛 UnsupportedOperationException），
 *      于是 YARN 既不会降级也不会报错，目录属主停留在客户端进程的 uid/gid ——
 *      Hadoop 自己的注释就警告过这种情形会导致 "JobHistoryServer may be unable to
 *      read the directory"；
 *   c. 应用目录 0770 的权限纠正是否生效。
 *
 * ⚠ 判据设计要点（控制组实测踩过的假阳性）：setOwner 必须改成**与当前属主不同**的
 *   用户/组，否则"变更前后属主相同"既可能是静默无效、也可能只是本来就一样。
 *
 * 用法：SpikeLogAggDirs <base 路径> <目标用户名> <目标组名>
 */
import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.security.UserGroupInformation;

public final class SpikeLogAggDirs {

  private static final FsPermission TLDIR_PERMISSIONS =
      FsPermission.createImmutable((short) 01777);
  private static final FsPermission APP_DIR_PERMISSIONS =
      FsPermission.createImmutable((short) 0770);

  public static void main(String[] args) throws Exception {
    if (args.length < 3) {
      System.err.println("用法: SpikeLogAggDirs <base> <目标用户名> <目标组名>");
      System.exit(2);
    }
    String targetUser = args[1];
    String targetGroup = args[2];
    Configuration conf = new Configuration();
    Path base = new Path(args[0]);
    FileSystem fs = base.getFileSystem(conf);
    UserGroupInformation ugi = UserGroupInformation.getCurrentUser();

    try {
      Path root = new Path(base, "logs");

      System.out.println("== ② mkdirs(01777) + setPermission(01777) ==");
      fs.mkdirs(root, new FsPermission(TLDIR_PERMISSIONS));
      FsPermission afterMkdirs = fs.getFileStatus(root).getPermission();
      System.out.println("  mkdirs 后权限        = " + afterMkdirs
          + "（受 fs.permissions.umask-mode 影响属正常）");

      String chmodErr = null;
      try {
        fs.setPermission(root, new FsPermission(TLDIR_PERMISSIONS));
      } catch (UnsupportedOperationException e) {
        chmodErr = "UnsupportedOperationException（YARN 会置 fsSupportsChmod=false）";
      } catch (Exception e) {
        chmodErr = e.getClass().getName() + ": " + e.getMessage();
      }
      FileStatus rootStat = fs.getFileStatus(root);
      System.out.println("  setPermission 结果   = " + (chmodErr == null ? "成功" : chmodErr));
      System.out.println("  最终权限             = " + rootStat.getPermission()
          + "（期望 " + TLDIR_PERMISSIONS + "，sticky 位必须保留）");

      boolean permOk = TLDIR_PERMISSIONS.equals(rootStat.getPermission());
      if (permOk) {
        result("SP-08A", "REFUTED", "顶层日志目录权限可达 01777（sticky 保留），"
            + "YARN 的权限校验不会告警");
      } else {
        result("SP-08A", "CONFIRMED", "顶层日志目录最终权限为 "
            + rootStat.getPermission() + "，与 YARN 期望的 01777 不符 → "
            + "NM 启动时告警 'The cluster may have problems with multiple users'");
      }

      System.out.println();
      System.out.println("== ③ setOwner(短用户名, 主组名) ==");
      // 用与当前属主不同的用户/组（见类注释的判据要点）；
      // YARN 真实传入的是 loginUser.getShortUserName() + primaryGroupName，
      // 语义等价 —— 都是"以名字调 setOwner"。
      String user = targetUser;
      String group = targetGroup;
      System.out.println("  （YARN 实际传入 loginUser=" + ugi.getShortUserName()
          + " / primaryGroup=" + safePrimaryGroup(ugi) + "，此处改用 "
          + targetUser + ":" + targetGroup + " 以便观察是否真的生效）");
      String ownerBefore = rootStat.getOwner() + ":" + rootStat.getGroup();
      String chownErr = null;
      try {
        fs.setOwner(root, user, group);
      } catch (UnsupportedOperationException e) {
        chownErr = "UnsupportedOperationException（YARN 会记 INFO 并继续）";
      } catch (Exception e) {
        chownErr = e.getClass().getName() + ": " + e.getMessage();
      }
      FileStatus afterChown = fs.getFileStatus(root);
      String ownerAfter = afterChown.getOwner() + ":" + afterChown.getGroup();
      System.out.println("  setOwner(\"" + user + "\",\"" + group + "\") = "
          + (chownErr == null ? "无异常" : chownErr));
      System.out.println("  属主 变更前=" + ownerBefore + " 变更后=" + ownerAfter);

      if (chownErr == null && ownerBefore.equals(ownerAfter)) {
        result("SP-08B", "CONFIRMED",
            "setOwner 静默无效且不抛 UnsupportedOperationException → YARN 既不降级也不报错，"
                + "日志目录属主停留在 " + ownerAfter
                + "；Hadoop 源码注释警告的 'JHS 可能读不到日志目录' 风险成立");
      } else if (chownErr != null && chownErr.startsWith("Unsupported")) {
        result("SP-08B", "REFUTED", "抛 UnsupportedOperationException，YARN 有明确降级分支");
      } else {
        result("SP-08B", "REFUTED", "属主已变更为 " + ownerAfter);
      }

      System.out.println();
      System.out.println("== checkExists：应用目录 0770 纠正 ==");
      Path appDir = new Path(root, "app-0001");
      fs.mkdirs(appDir);
      FsPermission before = fs.getFileStatus(appDir).getPermission();
      if (!APP_DIR_PERMISSIONS.equals(before)) {
        fs.setPermission(appDir, APP_DIR_PERMISSIONS);
      }
      FsPermission after = fs.getFileStatus(appDir).getPermission();
      System.out.println("  应用目录权限 " + before + " → " + after
          + "（期望 " + APP_DIR_PERMISSIONS + "）");
      if (APP_DIR_PERMISSIONS.equals(after)) {
        result("SP-08C", "REFUTED", "应用目录权限纠正生效（0770）");
      } else {
        result("SP-08C", "CONFIRMED", "应用目录权限纠正后仍为 " + after);
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

  private static String safePrimaryGroup(UserGroupInformation ugi) {
    try {
      return ugi.getPrimaryGroupName();
    } catch (IOException e) {
      return "<未知>";
    }
  }

  private static void result(String id, String verdict, String note) {
    System.out.println("RESULT|" + id + "|" + verdict + "|" + note);
  }
}
