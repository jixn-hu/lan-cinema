package com.lancinema.app;

/** Gesture coordinates come from a fixed overlay, independently of the transformed video. */
final class VideoTransform {
  float scale = 1, x, y;
  private float width, height;

  void viewport(float width, float height) {
    this.width = width;
    this.height = height;
    reset();
  }

  void reset() {
    scale = 1;
    x = y = 0;
  }

  void pinch(float factor, float fromX, float fromY, float toX, float toY) {
    float next = Math.max(1, Math.min(4, scale * factor));
    float ratio = next / scale;
    x = toX - (fromX - x) * ratio;
    y = toY - (fromY - y) * ratio;
    scale = next;
    clamp();
  }

  void pan(float dx, float dy) {
    x += dx;
    y += dy;
    clamp();
  }

  private void clamp() {
    x = Math.max(width * (1 - scale), Math.min(0, x));
    y = Math.max(height * (1 - scale), Math.min(0, y));
  }
}
