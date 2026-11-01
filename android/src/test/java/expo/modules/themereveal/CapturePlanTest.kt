package expo.modules.themereveal

import android.graphics.HardwareRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePlanTest {
  @Test
  fun gpuCaptureNeedsApi29() {
    assertEquals(CapturePath.PIXEL_COPY, CapturePlan.path(26, defaultColorMode = true, gpuOff = false))
    assertEquals(CapturePath.PIXEL_COPY, CapturePlan.path(28, defaultColorMode = true, gpuOff = false))
    assertEquals(CapturePath.GPU, CapturePlan.path(29, defaultColorMode = true, gpuOff = false))
    assertEquals(CapturePath.GPU, CapturePlan.path(37, defaultColorMode = true, gpuOff = false))
  }

  @Test
  fun aWideColorWindowKeepsPixelCopy() {
    assertEquals(CapturePath.PIXEL_COPY, CapturePlan.path(33, defaultColorMode = false, gpuOff = false))
  }

  @Test
  fun aGpuPathTurnedOffKeepsPixelCopy() {
    assertEquals(CapturePath.PIXEL_COPY, CapturePlan.path(33, defaultColorMode = true, gpuOff = true))
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
  fun theTimeoutCoversASlowFirstCapture() {
    // First capture in a fresh process took 50 ms on a Pixel 8 Pro, a warm one 12-32 ms on both lab phones.
    assertTrue(CapturePlan.GPU_TIMEOUT_MS >= 5 * 50)
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

  @Test
  fun anImageAfterTheTimeoutIsDropped() {
    val race = CaptureRace()
    assertTrue("the timeout ends the capture", race.finish())
    assertFalse("the late image does not", race.finish())
    assertFalse(race.finish())
  }
}
