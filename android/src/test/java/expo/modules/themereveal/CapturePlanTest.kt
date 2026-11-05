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
    assertTrue(CapturePlan.turnsGpuOff(GpuFailure.TIMEOUT, copyThreadBusy = false))
    assertTrue(CapturePlan.turnsGpuOff(GpuFailure.ERROR, copyThreadBusy = false))
    assertFalse(CapturePlan.turnsGpuOff(GpuFailure.SYNC, copyThreadBusy = false))
  }

  @Test
  fun aTimeoutWhileTheWarmUpHoldsTheCopyThreadKeepsTheGpuPath() {
    // The image waits behind the warm-up's pre-create on the copy thread, so the next switch tries the GPU again.
    assertFalse(CapturePlan.turnsGpuOff(GpuFailure.TIMEOUT, copyThreadBusy = true))
    assertTrue(CapturePlan.turnsGpuOff(GpuFailure.ERROR, copyThreadBusy = true))
    assertFalse(CapturePlan.turnsGpuOff(GpuFailure.SYNC, copyThreadBusy = true))
  }

  @Test
  fun aTimeoutBehindAPreCreateIsForgivenAndTheNextSwitchStillTriesTheGpu() {
    val preCreates = PreCreates()
    preCreates.started()
    val atStart = preCreates.count
    assertFalse(preCreates.skipsGpu())
    assertFalse(preCreates.turnsGpuOff(GpuFailure.TIMEOUT, true, atStart))
    preCreates.finished()
    assertFalse(preCreates.skipsGpu())
  }

  @Test
  fun aPreCreateThatNeverReturnsCostsOnlyTheFirstSwitchTheTimeout() {
    val preCreates = PreCreates()
    preCreates.started()
    assertFalse(preCreates.turnsGpuOff(GpuFailure.TIMEOUT, true, preCreates.count))
    // Pre-create still holds the copy thread, so the next switches go straight to PixelCopy without the wait.
    assertTrue(preCreates.skipsGpu())
    assertTrue(preCreates.skipsGpu())
  }

  @Test
  fun aNewPreCreateGetsItsOwnForgivenTimeout() {
    // A JS reload warms up again, and the new pre-create queues behind the old one.
    val preCreates = PreCreates()
    preCreates.started()
    assertFalse(preCreates.turnsGpuOff(GpuFailure.TIMEOUT, true, preCreates.count))
    preCreates.started()
    assertFalse(preCreates.skipsGpu())
    assertFalse(preCreates.turnsGpuOff(GpuFailure.TIMEOUT, false, preCreates.count))
    assertTrue(preCreates.skipsGpu())
  }

  @Test
  fun aPreCreateThatStartsDuringTheCaptureMakesTheCopyThreadBusy() {
    val preCreates = PreCreates()
    val atStart = preCreates.count
    preCreates.started()
    preCreates.finished()
    assertFalse(preCreates.turnsGpuOff(GpuFailure.TIMEOUT, false, atStart))
  }

  @Test
  fun withoutAPreCreateATimeoutTurnsTheGpuOffAndNothingIsSkipped() {
    val preCreates = PreCreates()
    assertTrue(preCreates.turnsGpuOff(GpuFailure.TIMEOUT, false, preCreates.count))
    assertTrue(preCreates.turnsGpuOff(GpuFailure.ERROR, false, preCreates.count))
    assertFalse(preCreates.turnsGpuOff(GpuFailure.SYNC, false, preCreates.count))
    assertFalse(preCreates.skipsGpu())
  }

  @Test
  fun jsNamesThePathAsTheCapturePathType() {
    // src/types.ts CapturePath.
    assertEquals("gpu", CapturePath.GPU.js)
    assertEquals("pixelcopy", CapturePath.PIXEL_COPY.js)
  }

  @Test
  fun fromApi29TheWarmUpPreparesTheGpuCaptureAndKeepsNoBitmap() {
    assertEquals(WarmUp.GPU, warmUp(29))
    assertEquals(WarmUp.GPU, warmUp(37))
  }

  @Test
  fun belowApi29TheWarmUpKeepsABitmapForPixelCopy() {
    assertEquals(WarmUp.KEPT_BITMAP, warmUp(26))
    assertEquals(WarmUp.KEPT_BITMAP, warmUp(28))
  }

  @Test
  fun withTheGpuPathTurnedOffTheWarmUpKeepsABitmap() {
    // A JS reload warms up again in the same process, after a GPU capture timed out or threw.
    assertEquals(WarmUp.KEPT_BITMAP, warmUp(33, gpuOff = true))
  }

  @Test
  fun anActivityWindowThatTakesPixelCopyGetsAKeptBitmapFromApi29() {
    assertEquals(WarmUp.KEPT_BITMAP, warmUp(33, defaultColorMode = false))
    assertEquals(WarmUp.KEPT_BITMAP, warmUp(33, hardwareAccelerated = false))
    assertEquals(WarmUp.KEPT_BITMAP, warmUp(37, hasLight = false))
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

  private fun warmUp(
    apiLevel: Int,
    defaultColorMode: Boolean = true,
    hardwareAccelerated: Boolean = true,
    hasLight: Boolean = true,
    gpuOff: Boolean = false,
  ) = CapturePlan.warmUp(apiLevel, defaultColorMode, hardwareAccelerated, hasLight, gpuOff)
}
