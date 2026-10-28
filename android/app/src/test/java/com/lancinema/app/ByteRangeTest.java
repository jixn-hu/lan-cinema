package com.lancinema.app;

import static org.junit.Assert.*;

import org.junit.Test;

public class ByteRangeTest {
  @Test
  public void readsBeyondThreeGigabytes() {
    long size = 3L * 1024 * 1024 * 1024 + 19;
    ByteRange r = ByteRange.parse("bytes=3221225472-", size);
    assertEquals(3221225472L, r.start);
    assertEquals(size - 1, r.end);
    assertEquals(19, r.length());
  }

  @Test
  public void suffixAndClamping() {
    ByteRange r = ByteRange.parse("bytes=-4", 10);
    assertEquals(6, r.start);
    assertEquals(4, r.length());
    assertEquals(9, ByteRange.parse("bytes=2-999", 10).end);
    assertEquals(0, ByteRange.parse("bytes=-999", 10).start);
  }

  @Test
  public void rejectsInvalidRanges() {
    for (String h :
        new String[] {
          "bytes=10-",
          "bytes=-0",
          "bytes=5-1",
          "bytes=-1-2",
          "bytes=0-1,3-4",
          "bytes=9223372036854775808-",
          "items=0-1",
          "bytes=x-y"
        })
      try {
        ByteRange.parse(h, 10);
        fail(h);
      } catch (IllegalArgumentException expected) {
      }
  }

  @Test(expected = IllegalArgumentException.class)
  public void emptyFileHasNoRanges() {
    ByteRange.parse("bytes=0-", 0);
  }
}
