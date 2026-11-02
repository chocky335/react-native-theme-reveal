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

// What a warm-up prepares for the first switch.
internal enum class WarmUp {
  // GPU capture's classes, reader and renderer. No bitmap kept, the GPU capture makes its own.
  GPU,

  // One window-size bitmap for PixelCopy, kept with one copy into it.
  KEPT_BITMAP,
}

internal class LightSource(val x: Float, val y: Float, val z: Float, val radius: Float)

internal object CapturePlan {
  // First capture in a fresh process took 50 ms on a Pixel 8 Pro, a warm one 12-32 ms on both lab phones.
  const val GPU_TIMEOUT_MS = 250L

  private const val SYNC_FAILED = HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND or
    HardwareRenderer.SYNC_CONTEXT_IS_STOPPED or HardwareRenderer.SYNC_FRAME_DROPPED

  // GPU capture renders in sRGB, so a wide color window stays on PixelCopy. So does a window without hardware
  // acceleration, since its software canvas can't draw the hardware bitmap. Same for a view change waiting for its
  // frame while force dark can apply: the capture would sync it first, without the force dark transform.
  fun path(
    apiLevel: Int,
    defaultColorMode: Boolean,
    hardwareAccelerated: Boolean,
    pendingUnderForceDark: Boolean,
    gpuOff: Boolean,
  ): CapturePath =
    if (apiLevel >= Build.VERSION_CODES.Q && defaultColorMode && hardwareAccelerated && !pendingUnderForceDark &&
      !gpuOff
    ) {
      CapturePath.GPU
    } else {
      CapturePath.PIXEL_COPY
    }

  // ViewRootImpl turns on force dark for a light theme that allows it, in night mode. From API 36 the color
  // inversion setting can darken a light theme too, and apps can't read that setting.
  fun forceDarkCanApply(apiLevel: Int, nightMode: Boolean, lightTheme: Boolean, forceDarkAllowed: Boolean): Boolean =
    apiLevel >= Build.VERSION_CODES.Q && nightMode && lightTheme &&
      (forceDarkAllowed || apiLevel >= Build.VERSION_CODES.BAKLAVA)

  // Warm-up prepares the path the first switch takes: GPU capture on API 29+, otherwise PixelCopy.
  fun warmUp(apiLevel: Int, gpuOff: Boolean): WarmUp =
    if (apiLevel >= Build.VERSION_CODES.Q && !gpuOff) WarmUp.GPU else WarmUp.KEPT_BITMAP

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

// How a GPU capture ends: image, timeout or failure, whichever comes first. The rest get dropped and a late bitmap
// is recycled. onResult runs once. release runs once, after onResult for an image and before it for a failure. Main
// thread only.
internal class CaptureEnd<B : Any>(
  private val release: () -> Unit,
  private val recycle: (B) -> Unit,
  private val cancelTimeout: () -> Unit,
  private val onResult: (B?, GpuFailure?) -> Unit,
) {
  private var finished = false

  fun image(bitmap: B?) {
    if (finished) {
      bitmap?.let(recycle)
      return
    }
    finished = true
    cancelTimeout()
    if (bitmap == null) {
      release()
      onResult(null, GpuFailure.ERROR)
      return
    }
    // Overlay goes up first, then the renderer goes away.
    try {
      onResult(bitmap, null)
    } finally {
      release()
    }
  }

  fun timeout() {
    if (finished) return
    finished = true
    release()
    onResult(null, GpuFailure.TIMEOUT)
  }

  fun fail(failure: GpuFailure) {
    if (finished) return
    finished = true
    cancelTimeout()
    release()
    onResult(null, failure)
  }
}
