package com.lancinema.app;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class PasswordHash {
  static String create(String password) throws Exception {
    byte[] salt = new byte[16];
    new SecureRandom().nextBytes(salt);
    return Base64.getEncoder().encodeToString(salt)
        + ":"
        + Base64.getEncoder().encodeToString(derive(password, salt));
  }

  static boolean verify(String password, String stored) {
    try {
      String[] parts = stored.split(":", -1);
      return parts.length == 2
          && MessageDigest.isEqual(
              Base64.getDecoder().decode(parts[1]),
              derive(password, Base64.getDecoder().decode(parts[0])));
    } catch (Exception e) {
      return false;
    }
  }

  private static byte[] derive(String password, byte[] salt) throws Exception {
    PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120000, 256);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } finally {
      spec.clearPassword();
    }
  }
}
