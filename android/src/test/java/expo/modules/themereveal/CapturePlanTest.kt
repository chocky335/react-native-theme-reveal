package expo.modules.themereveal

import android.graphics.HardwareRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePlanTest {
  @Test
  fun gpuCaptureNeedsApi29() {
    assertEquals(CapturePath.PIXEL_COPY, path(26))
    assertEquals(CapturePath.PIXEL_COPY, path(28))
    assertEquals(CapturePath.GPU, path(29))
    assertEquals(CapturePath.GPU, path(37))
  }

  @Test
  fun aWideColorWindowKeepsPixelCopy() {
    assertEquals(CapturePath.PIXEL_COPY, path(33, defaultColorMode = false))
  }

  @Test
  fun aGpuPathTurnedOffKeepsPixelCopy() {
    assertEquals(CapturePath.PIXEL_COPY, path(33, gpuOff = true))
  }

  @Test
  fun aWindowWithoutHardwareAccelerationKeepsPixelCopy() {
    // Overlay can't draw a hardware bitmap on a software canvas.
    assertEquals(CapturePath.PIXEL_COPY, path(33, hardwareAccelerated = false))
  }

  @Test
  fun pendingWorkUnderForceDarkKeepsPixelCopy() {
    // Capture would sync that work first, without the force dark transform.
    assertEquals(CapturePath.PIXEL_COPY, path(33, pendingUnderForceDark = true))
  }

  @Test
  fun forceDarkCanApplyToALightThemeInNightMode() {
    assertTrue(CapturePlan.forceDarkCanApply(29, nightMode = true, lightTheme = true, forceDarkAllowed = true))
    assertFalse(CapturePlan.forceDarkCanApply(33, nightMode = false, lightTheme = true, forceDarkAllowed = true))
    assertFalse(CapturePlan.forceDarkCanApply(33, nightMode = true, lightTheme = false, forceDarkAllowed = true))
    assertFalse(CapturePlan.forceDarkCanApply(33, nightMode = true, lightTheme = true, forceDarkAllowed = false))
    assertFalse(CapturePlan.forceDarkCanApply(28, nightMode = true, lightTheme = true, forceDarkAllowed = true))
  }

  @Test
  fun fromApi36ForceDarkCanApplyWithoutTheThemeAllowingIt() {
    // API 36's color inversion setting darkens light apps too, and apps can't read it.
    assertTrue(CapturePlan.forceDarkCanApply(36, nightMode = true, lightTheme = true, forceDarkAllowed = false))
    assertFalse(CapturePlan.forceDarkCanApply(36, nightMode = false, lightTheme = true, forceDarkAllowed = false))
  }

  @Test
  fun syncFailsOnALostSurfaceAStoppedContextOrADroppedFrame() {
    assertFalse(CapturePlan.syncFailed(HardwareRenderer.SYNC_OK))
    assertFalse(CapturePlan.syncFailed(HardwareRenderer.SYNC_REDRAW_REQUESTED))
    assertTrue(CapturePlan.syncFailed(HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND))
    assertTrue(CapturePlan.syncFailed(HardwareRenderer.SYNC_CONTEXT_IS_STOPPED))
    assertTrue(CapturePlan.syncFailed(HardwareRenderer.SYNC_FRAME_DROPPED))
    assertTrue(
      CapturePlan.syncFailed(HardwareRenderer.SYNC_REDRAW_REQUESTED or HardwareRenderer.SYNC_FRAME_DROPPED),
    )
  }

  @Test
  fun aTimeoutOrAnErrorTurnsTheGpuPathOffButASyncFailureDoesNot() {
    assertTrue(CapturePlan.turnsGpuOff(GpuFailure.TIMEOUT))
    assertTrue(CapturePlan.turnsGpuOff(GpuFailure.ERROR))
    assertFalse(CapturePlan.turnsGpuOff(GpuFailure.SYNC))
  }

  @Test
  fun fromApi29TheWarmUpPreparesTheGpuCaptureAndKeepsNoBitmap() {
    assertEquals(WarmUp.GPU, CapturePlan.warmUp(29, gpuOff = false))
    assertEquals(WarmUp.GPU, CapturePlan.warmUp(37, gpuOff = false))
  }

  @Test
  fun belowApi29TheWarmUpKeepsABitmapForPixelCopy() {
    assertEquals(WarmUp.KEPT_BITMAP, CapturePlan.warmUp(26, gpuOff = false))
    assertEquals(WarmUp.KEPT_BITMAP, CapturePlan.warmUp(28, gpuOff = false))
  }

  @Test
  fun withTheGpuPathTurnedOffTheWarmUpKeepsABitmap() {
    // A JS reload warms up again in the same process, after a GPU capture timed out or threw.
    assertEquals(WarmUp.KEPT_BITMAP, CapturePlan.warmUp(33, gpuOff = true))
  }

  @Test
  fun theLightSitsAboveTheDisplayCenterInWindowSpace() {
    // Pixel 8 Pro: 1008 x 2244, density 2.25, theme lightY 0, lightZ 500dp, lightRadius 800dp.
    val light = CapturePlan.light(33, 1008, 2244, 2.25f, 0, 0, 0f, 1125f, 1800f)
    assertEquals(504f, light.x, 1e-3f)
    assertEquals(0f, light.y, 1e-3f)
    assertEquals(1125f, light.z, 1e-3f)
    assertEquals(1800f, light.radius, 1e-3f)
    val moved = CapturePlan.light(33, 1008, 2244, 2.25f, 40, 120, 10f, 1125f, 1800f)
    assertEquals(464f, moved.x, 1e-3f)
    assertEquals(-110f, moved.y, 1e-3f)
  }

  @Test
  fun fromApi34TheLightHeightScalesWithTheSmallestDisplaySide() {
    // ThreadedRenderer.setLightCenter: z * (min(w, h) / (450 * density) + 2) / 3. Measured on the Pixel (API 37).
    assertEquals(1123.3334f, CapturePlan.light(34, 1008, 2244, 2.25f, 0, 0, 0f, 1125f, 1800f).z, 1e-3f)
    assertEquals(1123.3334f, CapturePlan.light(37, 2244, 1008, 2.25f, 0, 0, 0f, 1125f, 1800f).z, 1e-3f)
  }

  private fun path(
    apiLevel: Int,
    defaultColorMode: Boolean = true,
    hardwareAccelerated: Boolean = true,
    pendingUnderForceDark: Boolean = false,
    gpuOff: Boolean = false,
  ) = CapturePlan.path(apiLevel, defaultColorMode, hardwareAccelerated, pendingUnderForceDark, gpuOff)
}
