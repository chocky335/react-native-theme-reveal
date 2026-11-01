package expo.modules.themereveal

import android.graphics.HardwareRenderer
import android.os.Build
import kotlin.math.min

internal enum class CapturePath { GPU, PIXEL_COPY }

// Why a GPU capture gave no bitmap.
internal enum class GpuFailure {
  // Renderer lost its surface, was stopped or dropped the frame. Next switch tries the GPU again.
  SYNC,

  // No image arrived within GPU_TIMEOUT_MS.
  TIMEOUT,

  // Capture threw.
  ERROR,
}

internal class LightSource(val x: Float, val y: Float, val z: Float, val radius: Float)

internal object CapturePlan {
  // First capture in a fresh process took 50 ms on a Pixel 8 Pro, a warm one 12-32 ms on both lab phones.
  const val GPU_TIMEOUT_MS = 250L

  private const val SYNC_FAILED = HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND or
    HardwareRenderer.SYNC_CONTEXT_IS_STOPPED or HardwareRenderer.SYNC_FRAME_DROPPED

  // GPU capture renders in sRGB, so a wide color window stays on PixelCopy.
  fun path(apiLevel: Int, defaultColorMode: Boolean, gpuOff: Boolean): CapturePath =
    if (apiLevel >= Build.VERSION_CODES.Q && defaultColorMode && !gpuOff) CapturePath.GPU else CapturePath.PIXEL_COPY

  fun syncFailed(result: Int): Boolean = result and SYNC_FAILED != 0

  // A timeout or error can repeat on every switch, so the process stops trying the GPU.
  fun turnsGpuOff(failure: GpuFailure): Boolean = failure != GpuFailure.SYNC

  // Where ThreadedRenderer.setLightCenter puts a window's light: above the display's horizontal center, lightY from
  // its top, shifted into window space. From API 34 the height scales with the display's smallest side.
  fun light(
    apiLevel: Int,
    displayWidth: Int,
    displayHeight: Int,
    density: Float,
    windowLeft: Int,
    windowTop: Int,
    lightY: Float,
    lightZ: Float,
    lightRadius: Float,
  ): LightSource {
    val z = if (apiLevel >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      val ratio = min(displayWidth, displayHeight) / (450f * density)
      lightZ * (ratio + 2) / 3f
    } else {
      lightZ
    }
    return LightSource(displayWidth / 2f - windowLeft, lightY - windowTop, z, lightRadius)
  }
}

// Image, timeout and failure race to end a GPU capture. Only the first one counts. Main thread only.
internal class CaptureRace {
  private var finished = false

  fun finish(): Boolean {
    if (finished) return false
    finished = true
    return true
  }
}
