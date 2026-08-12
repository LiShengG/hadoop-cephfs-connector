/**
 * SP-01 / SP-02 / SP-03 探针：属主与组的字符串表示对 Hadoop 生态的影响。
 *
 * 被测的连接器事实（CephFileSystem#ownerName / groupName / setOwner）：
 *   private String ownerName(int uid) {
 *     if (uid == PROCESS_UID && userName != null) return userName;   // 进程 uid → 当前 UGI 名
 *     return Integer.toString(uid);                                  // 其余 → 数字串
 *   }
 *   group 恒为数字串；setOwner 仅接受数字 uid/gid，非数字仅 warn 后跳过。
 *
 * ⚠ 判据设计要点（第一版踩过的坑）：
 *   "把目录 chown 给别的 uid，属主校验就失败" —— 这在 **任何** 文件系统（含 HDFS）
 *   上都会失败，是 Hadoop 的既定行为，**不能**用来证明连接器有缺陷。
 *   连接器特有的病理是另外两条，本探针据此判定：
 *     B. 同一个目录，换一个 UGI 去读，报告的属主**跟着 UGI 变**
 *        （uid == 进程 uid 时直接返回当前 UGI 名）→ 属主是"谁问就报谁"，
 *        既是错误信息，也是权限判断的隐患；
 *     C. 目录属于**系统上真实存在的**用户（如 daemon），名字型文件系统会报出
 *        "daemon"，连接器只能报 uid 数字串 —— 注意必须选"有名字的 uid"：
 *        随便挑一个无账号的 uid（如 4242）时，连本地文件系统也只能报数字，
 *        那样的判据无法区分"FS 不会映射名字"与"这个 uid 本来就没名字"
 *        （控制组实测踩过这个假阳性）。
 *
 * 对照的 Hadoop 侧逻辑（均已核对 hadoop-3.3.6 源码）：
 *   - SP-01：JobSubmissionFiles#getStagingDir 用 FileStatus#getOwner() 做**字符串**比对；
 *   - SP-02：FileSystem#checkAccessPermissions 用 ugi.getGroups().contains(stat.getGroup())
 *            做**组名**比对；
 *   - SP-03：Hive/YARN/运维脚本普遍以用户名/组名调 setOwner。
 *
 * 用法：SpikeOwnerGroup <base 路径> <系统上真实存在的 uid> <该 uid 的用户名>
 *                        <当前用户主组 gid> <当前用户主组名>
 */
import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.UserGroupInformation;

public final class SpikeOwnerGroup {

  /** 用于 B 层对照的第二个 Hadoop 用户名（与当前 UGI 不同即可）。 */
  private static final String OTHER_USER = "spike-other-user";

  public static void main(String[] args) throws Exception {
    if (args.length < 5) {
      System.err.println("用法: SpikeOwnerGroup <base> <真实uid> <该uid的用户名> "
          + "<当前主组gid> <当前主组名>");
      System.exit(2);
    }
    final String base = args[0];
    String namedUid = args[1];
    String namedUser = args[2];
    String myGid = args[3];
    String myGroupName = args[4];

    final Configuration conf = new Configuration();
    Path basePath = new Path(base);
    FileSystem fs = basePath.getFileSystem(conf);
    UserGroupInformation ugi = UserGroupInformation.getCurrentUser();

    System.out.println("== 环境 ==");
    System.out.println("UGI 短用户名 : " + ugi.getShortUserName());
    System.out.println("UGI 组列表   : " + ugi.getGroups());
    System.out.println("构造用 uid   : " + namedUid + "（系统上的用户 " + namedUser
        + "）—— 名字型 FS 应当报出该用户名");
    System.out.println("当前主组     : " + myGroupName + "(gid " + myGid + ")");

    try {
      sp01(fs, conf, basePath, namedUid, namedUser, myGid, ugi);
      sp03(fs, basePath);
      sp02(fs, basePath, namedUid, myGid, myGroupName, ugi);
    } finally {
      try {
        fs.delete(basePath, true);
      } catch (IOException ignored) {
        // 清理失败不影响结论
      }
    }
  }

  // ── SP-01：MR staging 目录属主校验 ────────────────────────────────────────

  private static void sp01(FileSystem fs, final Configuration conf, Path base,
      String namedUid, String namedUser, String myGid, UserGroupInformation ugi)
      throws Exception {
    System.out.println();
    System.out.println("== SP-01：MR staging 目录属主校验 ==");
    final Path staging = new Path(base, "staging");
    fs.mkdirs(staging, new FsPermission((short) 0700));

    // A. 自建目录、当前 UGI 读
    FileStatus a = fs.getFileStatus(staging);
    String errA = stagingOwnerCheck(a, ugi);
    System.out.println("A. 自建目录：owner=" + a.getOwner() + " group=" + a.getGroup()
        + " perm=" + a.getPermission() + " → " + (errA == null ? "校验通过" : "校验失败"));

    // B. 同一目录、换一个 UGI 读（决定性判据：属主是否"谁问就报谁"）
    String ownerAsOther;
    try {
      ownerAsOther = UserGroupInformation.createRemoteUser(OTHER_USER).doAs(
          new PrivilegedExceptionAction<String>() {
            @Override
            public String run() throws Exception {
              // FileSystem 缓存按 UGI 分键，这里会得到独立的 CephFileSystem 实例
              // （其 userName 为 OTHER_USER）。探针不 close，避免影响外层共享实例。
              FileSystem other = FileSystem.get(staging.toUri(), conf);
              return other.getFileStatus(staging).getOwner();
            }
          });
    } catch (Exception e) {
      ownerAsOther = "<读取失败: " + e.getClass().getSimpleName() + ": " + e.getMessage() + ">";
    }
    System.out.println("B. 换 UGI(" + OTHER_USER + ") 读同一目录：owner=" + ownerAsOther
        + "（若与 A 的 " + a.getOwner() + " 不同，即「谁问就报谁」）");

    // C. 目录属于系统上真实存在的另一个用户（模拟"alice 的目录，
    //    客户端进程以别的 OS 用户运行"）。必须用有名字的 uid，见类注释。
    boolean chownOk = true;
    String chownErr = null;
    try {
      fs.setOwner(staging, namedUid, myGid);
    } catch (IOException e) {
      chownOk = false;
      chownErr = e.toString();
    }
    String ownerC = null;
    String errC = null;
    if (chownOk) {
      FileStatus c = fs.getFileStatus(staging);
      ownerC = c.getOwner();
      errC = stagingOwnerCheck(c, ugi);
      System.out.println("C. 改属主为 uid=" + namedUid + "（用户 " + namedUser
          + "）后：owner=" + ownerC + " → " + (errC == null ? "校验通过" : "校验失败"));
      System.out.println("   期望：名字型 FS 报 \"" + namedUser + "\"（该用户提交作业即可通过）；"
          + "报数字串则说明本 FS 无法映射用户名");
      if (errC != null) {
        System.out.println("   Hadoop 将抛出：" + errC);
      }
    } else {
      System.out.println("C. setOwner(数字 uid) 失败（可能 cephx caps 不足）：" + chownErr);
    }

    // 判定
    boolean ownerFollowsUgi = !ownerAsOther.startsWith("<")
        && !ownerAsOther.equals(a.getOwner());
    if (errA != null) {
      result("SP-01", "CONFIRMED", "连自建 staging 目录都过不了属主校验（owner 报告为 "
          + a.getOwner() + "）→ MR 作业提交必然失败");
    } else if (ownerFollowsUgi) {
      result("SP-01", "CONFIRMED",
          "属主随发起方 UGI 变化：同一目录在 UGI=" + ugi.getShortUserName() + " 下报 "
              + a.getOwner() + "，在 UGI=" + OTHER_USER + " 下报 " + ownerAsOther
              + " —— 属主信息不可信；配合 C（uid " + namedUid + " → \"" + ownerC
              + "\"）可知：属于某用户但客户端进程 uid 不同的 staging 目录，"
              + "在 HDFS 上能通过校验、在本连接器上必然失败");
    } else if (chownOk && namedUid.equals(ownerC)) {
      result("SP-01", "CONFIRMED",
          "属于系统用户 " + namedUser + "(uid " + namedUid + ") 的目录被报告为数字串 \""
              + ownerC + "\" 而非用户名 → 该用户提交作业时属主校验失败；"
              + "同样的目录在名字型 FS（HDFS）上报 \"" + namedUser + "\" 可通过");
    } else if (chownOk && namedUser.equals(ownerC)) {
      result("SP-01", "REFUTED",
          "uid " + namedUid + " 被正确映射为用户名 \"" + ownerC + "\"，属主校验语义正常");
    } else if (!chownOk) {
      result("SP-01", "INCONCLUSIVE",
          "无法构造陌生 uid 属主的目录（setOwner 失败: " + chownErr + "），需在有 chown 权限的环境重跑");
    } else {
      result("SP-01", "REFUTED", "属主报告稳定且各场景校验均通过（A=" + a.getOwner()
          + " B=" + ownerAsOther + " C=" + ownerC + "）");
    }
  }

  /**
   * 复刻 hadoop-3.3.6 JobSubmissionFiles#getStagingDir 的属主校验分支（已核对源码）：
   *   if (!(fileOwner.equals(currentUser.getShortUserName())
   *         || fileOwner.equalsIgnoreCase(currentUser.getUserName())
   *         || fileOwner.equals(realUser.getShortUserName())
   *         || fileOwner.equalsIgnoreCase(realUser.getUserName()))) throw new IOException(...)
   * 非 proxy 场景下 currentUser == realUser，故只比对两项。
   *
   * @return null 表示通过；否则返回 Hadoop 会抛出的错误消息原文
   */
  private static String stagingOwnerCheck(FileStatus st, UserGroupInformation user) {
    String fileOwner = st.getOwner();
    boolean ok = fileOwner.equals(user.getShortUserName())
        || fileOwner.equalsIgnoreCase(user.getUserName());
    if (ok) {
      return null;
    }
    return "The ownership on the staging directory " + st.getPath()
        + " is not as expected. It is owned by " + fileOwner
        + ". The directory must be owned by the submitter "
        + user.getShortUserName() + " or " + user.getUserName();
  }

  // ── SP-03：以用户名/组名调 setOwner ───────────────────────────────────────

  private static void sp03(FileSystem fs, Path base) throws IOException {
    System.out.println();
    System.out.println("== SP-03：setOwner(用户名, 组名) 的实际效果 ==");
    Path p = new Path(base, "chown-target");
    fs.mkdirs(p);
    FileStatus before = fs.getFileStatus(p);

    String user = "hive";
    String group = "hadoop";
    String thrown = null;
    try {
      fs.setOwner(p, user, group);
    } catch (Exception e) {
      thrown = e.getClass().getName() + ": " + e.getMessage();
    }
    FileStatus after = fs.getFileStatus(p);
    System.out.println("setOwner(\"" + user + "\",\"" + group + "\") 异常: "
        + (thrown == null ? "无" : thrown));
    System.out.println("变更前 owner=" + before.getOwner() + " group=" + before.getGroup());
    System.out.println("变更后 owner=" + after.getOwner() + " group=" + after.getGroup());

    boolean unchanged = before.getOwner().equals(after.getOwner())
        && before.getGroup().equals(after.getGroup());
    if (thrown == null && unchanged) {
      result("SP-03", "CONFIRMED",
          "非数字用户名/组名的 setOwner 静默无效（无异常、属主不变）——"
              + "Hive 建库、YARN 目录准备、运维脚本会误以为成功");
    } else if (thrown != null) {
      result("SP-03", "REFUTED", "setOwner 抛出异常而非静默跳过: " + thrown);
    } else {
      result("SP-03", "REFUTED", "属主确实被修改为 " + after.getOwner() + ":" + after.getGroup());
    }
  }

  // ── SP-02：基类 access() 的组鉴权 ─────────────────────────────────────────

  private static void sp02(FileSystem fs, Path base, String alienUid, String myGid,
      String myGroupName, UserGroupInformation ugi) throws IOException {
    System.out.println();
    System.out.println("== SP-02：FileSystem#access 的组鉴权 ==");
    Path p = new Path(base, "group-only");
    fs.mkdirs(p);

    // 构造"仅组可访问"：属主为陌生 uid，组为当前用户实际所属的组，权限 0070。
    // 组若报告为组名 → 当前用户有权；报告为数字 → 永远匹配不上。
    boolean prepared = true;
    try {
      fs.setPermission(p, new FsPermission((short) 0070));
      fs.setOwner(p, alienUid, myGid);
    } catch (IOException e) {
      prepared = false;
      System.out.println("构造失败：" + e);
    }
    if (!prepared) {
      result("SP-02", "INCONCLUSIVE", "无法构造仅组可访问的目录（setOwner/setPermission 失败）");
      return;
    }

    FileStatus st = fs.getFileStatus(p);
    List<String> ugiGroups = ugi.getGroups();
    boolean groupMatches = ugiGroups.contains(st.getGroup());
    System.out.println("目录 owner=" + st.getOwner() + " group=" + st.getGroup()
        + " perm=" + st.getPermission());
    System.out.println("UGI 组列表 " + ugiGroups + " 是否包含该 group? " + groupMatches);
    System.out.println("当前用户按 POSIX 属于组 " + myGroupName + "(gid " + myGid
        + ")，本应有权访问");

    String denied = null;
    try {
      fs.access(p, FsAction.READ);
      System.out.println("fs.access(READ) 通过");
    } catch (AccessControlException e) {
      denied = e.getMessage();
      System.out.println("fs.access(READ) 被拒：" + denied);
    }

    if (denied != null && !groupMatches) {
      result("SP-02", "CONFIRMED",
          "用户按 POSIX 属于该组却被 access() 拒绝 —— 基类按组名比对，"
              + "连接器报告数字 gid（" + st.getGroup() + "），组权限永远匹配不上");
    } else if (denied == null) {
      result("SP-02", "REFUTED", "access() 通过（组报告为 " + st.getGroup() + "），组鉴权未受影响");
    } else {
      result("SP-02", "INCONCLUSIVE",
          "access() 被拒但组名恰好匹配，需换构造条件复核: " + denied);
    }
  }

  private static void result(String id, String verdict, String note) {
    System.out.println("RESULT|" + id + "|" + verdict + "|" + note);
  }
}
