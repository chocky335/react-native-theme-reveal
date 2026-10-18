package expo.modules.themereveal

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Test

class ShapePathTest {
  @Test
  fun keepsAPositiveRadiusWhenTheAnchorIsInside() {
    assertEquals(12f, effectiveInscribedRadius(0f, 0f, 40f, 30f, 20f, 15f, 12f), 1e-6f)
  }

  @Test
  fun fallsBackToHalfTheDiagonalForAZeroRadius() {
    val expected = (hypot(40.0, 30.0) / 2).toFloat()
    assertEquals(expected, effectiveInscribedRadius(0f, 0f, 40f, 30f, 20f, 15f, 0f), 1e-6f)
  }

  @Test
  fun fallsBackToHalfTheDiagonalForANegativeRadius() {
    val expected = (hypot(40.0, 30.0) / 2).toFloat()
    assertEquals(expected, effectiveInscribedRadius(0f, 0f, 40f, 30f, 20f, 15f, -3f), 1e-6f)
  }

  @Test
  fun fallsBackToHalfTheDiagonalWhenTheAnchorIsOutside() {
    val expected = (hypot(40.0, 30.0) / 2).toFloat()
    assertEquals(expected, effectiveInscribedRadius(0f, 0f, 40f, 30f, 50f, 15f, 12f), 1e-6f)
    assertEquals(expected, effectiveInscribedRadius(0f, 0f, 40f, 30f, 20f, -1f, 12f), 1e-6f)
  }

  @Test
  fun aUnitCircleKeepsItsRadius() {
    assertEquals(1f, effectiveInscribedRadius(-1f, -1f, 1f, 1f, 0f, 0f, 1f), 1e-6f)
  }
}
