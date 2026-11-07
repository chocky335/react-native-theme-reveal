package expo.modules.themereveal

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RevealPlanTest {
  private fun path(
    type: String = "reveal",
    customShape: Boolean = false,
    apiLevel: Int = 33,
    snapshot: CapturePath = CapturePath.GPU,
    animatorsEnabled: Boolean = true,
  ) = RevealPlan.path(type, customShape, apiLevel, snapshot, animatorsEnabled)

  @Test
  fun theDefaultCircleFromAGpuSnapshotRunsOnTheRenderThread() {
    assertEquals(RevealPath.RENDER_THREAD_REVEAL, path("reveal"))
    assertEquals(RevealPath.RENDER_THREAD_INVERSE, path("inverseReveal"))
  }

  @Test
  fun theRenderThreadPathNeedsApi29() {
    assertEquals(RevealPath.UI_THREAD, path("reveal", apiLevel = 26))
    assertEquals(RevealPath.UI_THREAD, path("inverseReveal", apiLevel = 28))
    assertEquals(RevealPath.RENDER_THREAD_REVEAL, path("reveal", apiLevel = 29))
    assertEquals(RevealPath.RENDER_THREAD_INVERSE, path("inverseReveal", apiLevel = 29))
  }

  @Test
  fun aCustomShapeKeepsTheUiThread() {
    assertEquals(RevealPath.UI_THREAD, path("reveal", customShape = true))
    assertEquals(RevealPath.UI_THREAD, path("inverseReveal", customShape = true))
  }

  @Test
  fun aPixelCopySnapshotKeepsTheUiThread() {
    assertEquals(RevealPath.UI_THREAD, path("reveal", snapshot = CapturePath.PIXEL_COPY))
    assertEquals(RevealPath.UI_THREAD, path("inverseReveal", snapshot = CapturePath.PIXEL_COPY))
  }

  @Test
  fun disabledAnimatorsKeepTheUiThread() {
    assertEquals(RevealPath.UI_THREAD, path("reveal", animatorsEnabled = false))
    assertEquals(RevealPath.UI_THREAD, path("inverseReveal", animatorsEnabled = false))
  }

  @Test
  fun wipeAndCrossfadeKeepTheUiThread() {
    assertEquals(RevealPath.UI_THREAD, path("wipe"))
    assertEquals(RevealPath.UI_THREAD, path("crossfade"))
    assertEquals(RevealPath.UI_THREAD, path("unknown"))
  }

  @Test
  fun circleRadiiRunFromHalfTheStartHeightToTheFarthestCorner() {
    // ShapePath.circle() has radius 1 and height 2: the UI-thread reveal starts at startHeightPx / 2.
    val radii = RevealPlan.circleRadii(100f, 200f, 1080, 2400, 66f)
    assertEquals(33f, radii[0], 1e-4f)
    assertEquals(hypot(980.0, 2200.0).toFloat(), radii[1], 1e-2f)
  }

  @Test
  fun radiiAreUsableOnlyWhenTheCircleGrows() {
    assertTrue(RevealPlan.radiiUsable(33f, 2631f))
    assertFalse(RevealPlan.radiiUsable(33f, 33f))
    assertFalse(RevealPlan.radiiUsable(33f, 10f))
    assertFalse(RevealPlan.radiiUsable(0f, 2631f))
    assertFalse(RevealPlan.radiiUsable(Float.NaN, 2631f))
    assertFalse(RevealPlan.radiiUsable(33f, Float.POSITIVE_INFINITY))
  }

  @Test
  fun aHeldGpuSnapshotIsPreImportedOnApi29AndUp() {
    assertTrue(RevealPlan.preImports(29, CapturePath.GPU))
    assertTrue(RevealPlan.preImports(36, CapturePath.GPU))
    assertFalse(RevealPlan.preImports(28, CapturePath.GPU))
    assertFalse(RevealPlan.preImports(33, CapturePath.PIXEL_COPY))
  }
}
