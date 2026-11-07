package expo.modules.themereveal

import android.graphics.HardwareRenderer
import android.os.Build
import kotlin.math.min

// js: name JS sees for it (src/types.ts CapturePath).
internal enum class CapturePath(val js: String) {
  GPU("gpu"),
  PIXEL_COPY("pixelcopy"),
}

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

  // Warm-up prepares the path the activity window's first switch takes: GPU capture on API 29+, otherwise
  // PixelCopy. hasLight: the theme has the light values for shadows (GpuSnapshot.lightOf).
  fun warmUp(
    apiLevel: Int,
    defaultColorMode: Boolean,
    hardwareAccelerated: Boolean,
    hasLight: Boolean,
    gpuOff: Boolean,
  ): WarmUp =
    if (hasLight && path(apiLevel, defaultColorMode, hardwareAccelerated, false, gpuOff) == CapturePath.GPU) {
      WarmUp.GPU
    } else {
      WarmUp.KEPT_BITMAP
    }

  fun syncFailed(result: Int): Boolean = result and SYNC_FAILED != 0

  // A timeout or error can repeat on every switch, so the process stops trying the GPU. A timeout while the warm-up's
  // pre-create holds the copy thread (copyThreadBusy) says nothing about the GPU, so the next switch tries it.
  fun turnsGpuOff(failure: GpuFailure, copyThreadBusy: Boolean): Boolean = when (failure) {
    GpuFailure.SYNC -> false
    GpuFailure.TIMEOUT -> !copyThreadBusy
    GpuFailure.ERROR -> true
  }

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

// GPU jobs on the copy thread: the warm-up's pre-creates, plus the RenderThread reveal's prepared targets and
// pre-imports. All of them count as pre-creates here. A GPU capture's image arrives on that thread, so it can wait
// behind a pre-create past the timeout. The first such timeout of each pre-create is forgiven and the GPU path stays
// on. If that pre-create still holds the copy thread at a later switch, that switch skips the GPU capture. So a
// pre-create that never returns costs only one switch the timeout. Main thread only.
internal class PreCreates {
  // How many have started.
  var count = 0
    private set
  private var running = 0
  private var forgivenAt = -1

  // One is queued or running.
  val busy: Boolean
    get() = running > 0

  fun started() {
    count++
    running++
  }

  fun finished() {
    running--
  }

  // At a switch's start, the copy thread is still running a pre-create that already cost a switch the timeout.
  fun skipsGpu(): Boolean = running > 0 && forgivenAt == count

  // A failed GPU capture. busyAtStart and countAtStart are read when the capture started.
  fun turnsGpuOff(failure: GpuFailure, busyAtStart: Boolean, countAtStart: Int): Boolean {
    val copyThreadBusy = busyAtStart || running > 0 || count != countAtStart
    if (failure == GpuFailure.TIMEOUT && copyThreadBusy) forgivenAt = count
    return CapturePlan.turnsGpuOff(failure, copyThreadBusy)
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
