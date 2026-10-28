package com.lancinema.app;

import static org.junit.Assert.*;

import org.junit.Test;

public class PasswordHashTest {
  @Test
  public void verifiesSaltedPasswords() throws Exception {
    String hash = PasswordHash.create("视频-password42");
    assertTrue(PasswordHash.verify("视频-password42", hash));
    assertFalse(PasswordHash.verify("wrong", hash));
    assertNotEquals(hash, PasswordHash.create("视频-password42"));
    assertFalse(hash.contains("password42"));
  }

  @Test
  public void malformedHashesFailClosed() {
    assertFalse(PasswordHash.verify("test", ""));
    assertFalse(PasswordHash.verify("test", "bad:invalid"));
  }
}
