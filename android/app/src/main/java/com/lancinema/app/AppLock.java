package com.lancinema.app;

import android.content.Context;

final class AppLock {
  static boolean enabled(Context context) {
    return !context.getSharedPreferences("lock", 0).getString("hash", "").isEmpty();
  }

  static boolean verify(Context context, String password) {
    return PasswordHash.verify(
        password, context.getSharedPreferences("lock", 0).getString("hash", ""));
  }

  static void set(Context context, String password) throws Exception {
    context
        .getSharedPreferences("lock", 0)
        .edit()
        .putString("hash", PasswordHash.create(password))
        .putInt("failures", 0)
        .putLong("until", 0)
        .commit();
  }
}
