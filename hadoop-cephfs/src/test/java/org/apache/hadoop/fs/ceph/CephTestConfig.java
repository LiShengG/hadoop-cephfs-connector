package org.apache.hadoop.fs.ceph;

import org.apache.hadoop.conf.Configuration;

/** Shared authentication overrides for real-cluster integration tests. */
public final class CephTestConfig {
  private static final String AUTH_ID_ENV = "CEPH_AUTH_ID";
  private static final String AUTH_KEYRING_ENV = "CEPH_AUTH_KEYRING";

  private CephTestConfig() {
  }

  public static void applyAuth(Configuration conf) {
    String authId = value(CephConfigKeys.CEPH_AUTH_ID_KEY, AUTH_ID_ENV, "admin");
    conf.set(CephConfigKeys.CEPH_AUTH_ID_KEY, authId);

    String keyring = value(CephConfigKeys.CEPH_AUTH_KEYRING_KEY,
        AUTH_KEYRING_ENV, null);
    if (keyring != null) {
      conf.set(CephConfigKeys.CEPH_AUTH_KEYRING_KEY, keyring);
    }
  }

  private static String value(String property, String environment,
      String defaultValue) {
    String fromProperty = System.getProperty(property);
    if (fromProperty != null && !fromProperty.trim().isEmpty()) {
      return fromProperty;
    }
    String fromEnvironment = System.getenv(environment);
    if (fromEnvironment != null && !fromEnvironment.trim().isEmpty()) {
      return fromEnvironment;
    }
    return defaultValue;
  }
}
