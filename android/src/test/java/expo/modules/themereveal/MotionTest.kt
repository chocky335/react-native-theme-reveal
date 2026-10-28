package expo.modules.themereveal

import kotlin.math.hypot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {
  @Test
  fun growthStartsAtFromAndEndsAtTo() {
    assertEquals(0.17, Motion.growth(0.0, 0.17, 20.0), 1e-9)
    assertEquals(20.0, Motion.growth(1.0, 0.17, 20.0), 1e-9)
  }

  @Test
  fun growthIsMonotonic() {
    var previous = Motion.growth(0.0, 0.17, 20.0)
    for (step in 1..100) {
      val next = Motion.growth(step / 100.0, 0.17, 20.0)
      assertTrue("step $step", next > previous)
      previous = next
    }
  }

  @Test
  fun shrinkRunsFromCoverToZero() {
    assertEquals(20.0, Motion.shrink(0.0, 0.17, 20.0), 1e-9)
    assertEquals(0.0, Motion.shrink(1.0, 0.17, 20.0), 1e-9)
    var previous = Motion.shrink(0.0, 0.17, 20.0)
    for (step in 1..100) {
      val next = Motion.shrink(step / 100.0, 0.17, 20.0)
      assertTrue("step $step", next < previous)
      previous = next
    }
  }

  @Test
  fun coverScaleReachesTheFarthestCorner() {
    val expected = (hypot(1080.0, 2400.0) / 46.7).toFloat()
    assertEquals(expected, Motion.coverScale(0f, 0f, 1080, 2400, 46.7f), 1e-3f)
  }

  @Test
  fun coverScaleFromTheCenterUsesHalfTheDiagonal() {
    val expected = (hypot(540.0, 1200.0) / 10.0).toFloat()
    assertEquals(expected, Motion.coverScale(540f, 1200f, 1080, 2400, 10f), 1e-3f)
  }

  @Test
  fun easeInOutHitsTheEndsAndTheMiddle() {
    assertEquals(0f, Motion.easeInOut(0f), 1e-6f)
    assertEquals(1f, Motion.easeInOut(1f), 1e-6f)
    assertEquals(0.5f, Motion.easeInOut(0.5f), 1e-4f)
  }

  @Test
  fun easeInOutIsMonotonicAndSlowAtTheEnds() {
    var previous = 0f
    for (step in 1..100) {
      val next = Motion.easeInOut(step / 100f)
      assertTrue("step $step", next >= previous)
      previous = next
    }
    assertTrue(Motion.easeInOut(0.1f) < 0.1f)
    assertTrue(Motion.easeInOut(0.9f) > 0.9f)
  }

  @Test
  fun canAnimateNeedsApi26AndAResumedActivity() {
    assertFalse(Motion.canAnimate(25, true))
    assertFalse(Motion.canAnimate(26, false))
    assertTrue(Motion.canAnimate(26, true))
  }

  @Test
  fun sameSizeComparesBothSides() {
    assertTrue(Motion.sameSize(1080, 2400, 1080, 2400))
    assertFalse(Motion.sameSize(1080, 2400, 2400, 1080))
    assertFalse(Motion.sameSize(1080, 2400, 1080, 2300))
  }

  @Test
  fun dialogFrameKeepsAFullScreenDialogAtTheOrigin() {
    assertEquals(Frame(0, 0, 1080, 2400), Motion.dialogFrame(0, 0, 1080, 2400, 1080, 2400))
  }

  @Test
  fun dialogFrameOffsetsACenteredDialog() {
    assertEquals(Frame(90, 600, 990, 1800), Motion.dialogFrame(90, 600, 900, 1200, 1080, 2400))
  }

  @Test
  fun dialogFrameClipsADialogToTheScreen() {
    assertEquals(Frame(0, 2300, 1070, 2400), Motion.dialogFrame(-10, 2300, 1080, 200, 1080, 2400))
    assertTrue(Motion.dialogFrame(0, 2400, 1080, 200, 1080, 2400).isEmpty)
  }

  @Test
  fun dimAlphaScalesTheDimAmountToAByte() {
    assertEquals(153, Motion.dimAlpha(0.6f))
    assertEquals(0, Motion.dimAlpha(0f))
    assertEquals(255, Motion.dimAlpha(1f))
    assertEquals(255, Motion.dimAlpha(2f))
    assertEquals(0, Motion.dimAlpha(Float.NaN))
  }

  @Test
  fun toOverlayLeavesAPointUnchangedForARootAtTheOverlayOrigin() {
    assertArrayEquals(floatArrayOf(120f, 340f), Motion.toOverlay(120f, 340f, 0, 0, 0, 0), 1e-6f)
    assertArrayEquals(floatArrayOf(120f, 340f), Motion.toOverlay(120f, 340f, 0, 100, 0, 100), 1e-6f)
  }

  @Test
  fun toOverlayShiftsAPointByTheRootOffset() {
    assertArrayEquals(floatArrayOf(210f, 940f), Motion.toOverlay(120f, 340f, 90, 600, 0, 0), 1e-6f)
  }

  @Test
  fun durationMsKeepsAPositiveDurationUpToTheCap() {
    assertEquals(450L, Motion.durationMs(450.0))
    assertEquals(450L, Motion.durationMs(450.9))
    assertEquals(1L, Motion.durationMs(1.0))
    assertEquals(10_000L, Motion.durationMs(10_000.0))
  }

  @Test
  fun durationMsRejectsANonFiniteZeroNegativeOrOverlongDuration() {
    for (ms in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, 0.5, -1.0, 10_000.5, 450_000.0)) {
      assertNull("$ms", Motion.durationMs(ms))
    }
  }
}
