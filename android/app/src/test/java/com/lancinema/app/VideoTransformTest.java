package com.lancinema.app;

import static org.junit.Assert.*;

import org.junit.Test;

public class VideoTransformTest {
  @Test
  public void keepsTouchedContentUnderFingersAtEveryLocation() {
    for (float fx : new float[] {20, 240, 900}) {
      for (float fy : new float[] {20, 480, 1700}) {
        VideoTransform t = new VideoTransform();
        t.viewport(1000, 1800);
        t.pinch(2, fx, fy, fx, fy);
        assertEquals(fx, fx * t.scale + t.x, .001f);
        assertEquals(fy, fy * t.scale + t.y, .001f);
      }
    }
  }

  @Test
  public void followsMovingPinchMidpointWithoutJumping() {
    VideoTransform t = new VideoTransform();
    t.viewport(1200, 800);
    t.pinch(2, 500, 300, 500, 300);
    float contentX = (500 - t.x) / t.scale, contentY = (300 - t.y) / t.scale;
    t.pinch(1.2f, 500, 300, 580, 330);
    assertEquals(580, contentX * t.scale + t.x, .001f);
    assertEquals(330, contentY * t.scale + t.y, .001f);
  }

  @Test
  public void clampsPanAndZoomAndReturnsExactlyToIdentity() {
    VideoTransform t = new VideoTransform();
    t.viewport(1000, 600);
    t.pinch(10, 250, 450, 250, 450);
    assertEquals(4, t.scale, 0);
    t.pan(-100000, 100000);
    assertEquals(-3000, t.x, 0);
    assertEquals(0, t.y, 0);
    t.pinch(.001f, 200, 200, 200, 200);
    assertEquals(1, t.scale, 0);
    assertEquals(0, t.x, 0);
    assertEquals(0, t.y, 0);
  }

  @Test
  public void rotationResetsTransformForNewViewport() {
    VideoTransform t = new VideoTransform();
    t.viewport(800, 1200);
    t.pinch(3, 600, 900, 600, 900);
    t.viewport(1200, 800);
    assertEquals(1, t.scale, 0);
    assertEquals(0, t.x, 0);
    assertEquals(0, t.y, 0);
  }
}
