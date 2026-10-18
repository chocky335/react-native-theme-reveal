package expo.modules.themereveal

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
