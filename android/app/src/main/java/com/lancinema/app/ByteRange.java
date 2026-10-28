package com.lancinema.app;

/** One RFC 9110 byte range, with 64-bit positions for multi-gigabyte files. */
public final class ByteRange {
  public final long start, end;

  private ByteRange(long start, long end) {
    this.start = start;
    this.end = end;
  }

  public long length() {
    return end - start + 1;
  }

  public static ByteRange parse(String header, long size) {
    if (size <= 0 || header == null || !header.startsWith("bytes=") || header.contains(","))
      throw new IllegalArgumentException("Invalid range");
    String[] parts = header.substring(6).split("-", -1);
    if (parts.length != 2) throw new IllegalArgumentException("Invalid range");
    try {
      long start, end;
      if (parts[0].isEmpty()) {
        long suffix = Long.parseLong(parts[1]);
        if (suffix <= 0) throw new IllegalArgumentException("Invalid suffix");
        start = Math.max(0, size - suffix);
        end = size - 1;
      } else {
        start = Long.parseLong(parts[0]);
        end = parts[1].isEmpty() ? size - 1 : Math.min(size - 1, Long.parseLong(parts[1]));
      }
      if (start < 0 || start >= size || end < start)
        throw new IllegalArgumentException("Unsatisfiable range");
      return new ByteRange(start, end);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid number", e);
    }
  }
}
