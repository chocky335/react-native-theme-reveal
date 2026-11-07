package expo.modules.themereveal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RevealInterpolatorTest {
  // A 24 dp start circle at density 2.75 and the cover radius from a corner of a 1080x2400 window.
  private val r0 = 33f
  private val r1 = 2631.7f

  @Test
  fun growthStartsAtZeroAndEndsAtOne() {
    val growth = GrowthInterpolator(r0, r1)
    assertEquals(0f, growth.getInterpolation(0f), 1e-6f)
    assertEquals(1f, growth.getInterpolation(1f), 1e-6f)
  }

  @Test
  fun growthIsMonotonic() {
    val growth = GrowthInterpolator(r0, r1)
    var previous = growth.getInterpolation(0f)
    for (step in 1..100) {
      val next = growth.getInterpolation(step / 100f)
      assertTrue("step $step", next > previous)
      previous = next
    }
  }

  @Test
  fun growthGivesTheUiThreadRevealRadius() {
    // createCircularReveal draws r0 + (r1 - r0) * fraction.
    val growth = GrowthInterpolator(r0, r1)
    for (step in 0..100) {
      val t = step / 100f
      val radius = r0 + (r1 - r0) * growth.getInterpolation(t)
      assertEquals("step $step", Motion.growth(t.toDouble(), r0.toDouble(), r1.toDouble()), radius.toDouble(), 0.01)
    }
  }

  @Test
  fun shrinkStartsAtZeroAndEndsAtOne() {
    val shrink = ShrinkInterpolator(r0, r1)
    assertEquals(0f, shrink.getInterpolation(0f), 1e-6f)
    assertEquals(1f, shrink.getInterpolation(1f), 1e-6f)
  }

  @Test
  fun shrinkIsMonotonic() {
    val shrink = ShrinkInterpolator(r0, r1)
    var previous = shrink.getInterpolation(0f)
    for (step in 1..100) {
      val next = shrink.getInterpolation(step / 100f)
      assertTrue("step $step", next > previous)
      previous = next
    }
  }

  @Test
  fun shrinkGivesTheUiThreadInverseRevealRadius() {
    // The inverse reveal runs from r1 to 0, so createCircularReveal draws r1 * (1 - fraction).
    val shrink = ShrinkInterpolator(r0, r1)
    for (step in 0..100) {
      val t = step / 100f
      val radius = r1 * (1 - shrink.getInterpolation(t))
      assertEquals("step $step", Motion.shrink(t.toDouble(), r0.toDouble(), r1.toDouble()), radius.toDouble(), 0.01)
    }
  }

  @Test
  fun fractionsStayBetweenZeroAndOne() {
    val growth = GrowthInterpolator(r0, r1)
    val shrink = ShrinkInterpolator(r0, r1)
    for (input in listOf(-0.5f, 0f, 1e-7f, 0.999999f, 1f, 1.5f)) {
      assertTrue("growth $input", growth.getInterpolation(input) in 0f..1f)
      assertTrue("shrink $input", shrink.getInterpolation(input) in 0f..1f)
    }
  }
}
