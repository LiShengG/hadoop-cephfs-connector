/**
 * SP-06 探针（可在非安全集群上先跑一半）：委托 Token 与安全集群适用性。
 *
 * 被测的连接器事实：未覆写 getDelegationToken / addDelegationTokens /
 * getCanonicalServiceName，全部沿用基类默认 —— 即**不提供任何委托 Token**。
 *
 * 生产含义（这才是本探针的重点，不在于 API 返回值本身）：
 *   Kerberos 集群里 YARN 容器以作业用户身份在各节点运行，它们无法通过 Token 继承
 *   客户端的 FS 凭据，只能各自读本地 cephx keyring —— 于是 keyring 必须分发到所有
 *   计算节点且对作业用户可读，**任何能起容器的用户都因此获得该 cephx 身份的全部权限**。
 *   这不是 bug，是架构 §1.2 "不做 Kerberos/Token 集成"的直接后果，
 *   但必须在 DEPLOY.md 写成明确的适用边界，而不是留给用户自己踩。
 *
 * 非安全集群上本探针能确证"无 Token"这一半；Kerberized 集群上的完整验证见
 * scripts/spike/sp06-kerberos.sh 的 B 层清单。
 *
 * 注：本探针是**事实确认型**而非**对比判别型** —— LocalFileSystem 同样没有委托 Token，
 * 因此拿 file:// 做控制组不具判别意义（HDFS 才会返回 token）。它要确立的事实是
 * "连接器不提供 Token"，真正的结论在于由此推出的 keyring 分发与权限边界。
 *
 * 用法：SpikeDelegationToken <base 路径>
 */
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;

public final class SpikeDelegationToken {

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("用法: SpikeDelegationToken <base>");
      System.exit(2);
    }
    Configuration conf = new Configuration();
    Path base = new Path(args[0]);
    FileSystem fs = base.getFileSystem(conf);

    try {
      String scheme = fs.getUri().getScheme();
      UserGroupInformation ugi = UserGroupInformation.getCurrentUser();
      System.out.println("== 环境 ==");
      System.out.println("  FileSystem 实现 = " + fs.getClass().getName());
      System.out.println("  FileSystem URI = " + fs.getUri());
      System.out.println("  hadoop.security.authentication = "
          + conf.get("hadoop.security.authentication", "simple"));
      System.out.println("  UGI 认证方式 = " + ugi.getAuthenticationMethod());
      System.out.println("  isSecurityEnabled = " + UserGroupInformation.isSecurityEnabled());

      System.out.println();
      System.out.println("== 委托 Token ==");
      String service = fs.getCanonicalServiceName();
      System.out.println("  getCanonicalServiceName = " + service);

      Credentials creds = new Credentials();
      Token<?>[] tokens = fs.addDelegationTokens("yarn", creds);
      int n = (tokens == null) ? 0 : tokens.length;
      System.out.println("  addDelegationTokens(\"yarn\") 返回 " + n + " 个 token");
      System.out.println("  Credentials 内 token 数 = " + creds.numberOfTokens());

      if (!"ceph".equalsIgnoreCase(scheme)) {
        result("SP-06-CONTROL", "OBSERVED",
            scheme + " 在当前认证模式下返回 " + n
                + " 个 token；该控制组不用于判定 CephFS 连接器结论");
      } else if (n == 0 && creds.numberOfTokens() == 0) {
        result("SP-06A", "CONFIRMED",
            "连接器不提供委托 Token（canonicalServiceName=" + service
                + "）→ Kerberos 集群中容器无法继承客户端凭据，"
                + "只能在每个节点分发 cephx keyring；凡能起容器者即获得该 cephx 身份的全部权限，"
                + "须在 DEPLOY.md 写成明确的安全适用边界");
      } else {
        result("SP-06A", "REFUTED", "返回了 " + n + " 个委托 token，需进一步核对其可用性");
      }
    } finally {
      fs.close();
    }
  }

  private static void result(String id, String verdict, String note) {
    System.out.println("RESULT|" + id + "|" + verdict + "|" + note);
  }
}
