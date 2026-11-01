package expo.modules.themereveal

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.View
import androidx.annotation.RequiresApi

// Elevation shadows: where the light is and how dark its shadows are.
internal class Lighting(val source: LightSource, val ambientAlpha: Float, val spotAlpha: Float)

// API 29+: window snapshot drawn on the GPU into a hardware bitmap. No readback to the CPU and no upload back to the
// GPU like PixelCopy needs.
@RequiresApi(Build.VERSION_CODES.Q)
internal object GpuSnapshot {
  // Light the window's renderer uses for elevation shadows (ThreadedRenderer), or null if the theme doesn't define
  // it.
  fun lightOf(activity: Activity, decor: View): Lighting? {
    val display = decor.display ?: return null
    val theme = activity.theme
    val value = TypedValue()
    fun dimension(name: String): Float? {
      // lightY, lightZ and lightRadius are private framework attributes.
      @Suppress("DiscouragedApi")
      val id = activity.resources.getIdentifier(name, "attr", "android")
      if (id == 0 || !theme.resolveAttribute(id, value, true)) return null
      return value.getDimension(activity.resources.displayMetrics)
    }
    val lightY = dimension("lightY") ?: return null
    val lightZ = dimension("lightZ") ?: return null
    val radius = dimension("lightRadius") ?: return null
    if (!theme.resolveAttribute(android.R.attr.ambientShadowAlpha, value, true)) return null
    val ambient = value.float
    if (!theme.resolveAttribute(android.R.attr.spotShadowAlpha, value, true)) return null
    val spot = value.float
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    display.getRealMetrics(metrics)
    val inWindow = IntArray(2).also { decor.getLocationInWindow(it) }
    val onScreen = WindowSnapshot.locationOf(decor)
    val source = CapturePlan.light(
      Build.VERSION.SDK_INT,
      metrics.widthPixels,
      metrics.heightPixels,
      metrics.density,
      onScreen[0] - inWindow[0],
      onScreen[1] - inWindow[1],
      lightY,
      lightZ,
      radius,
    )
    return Lighting(source, ambient, spot)
  }

  // Draws a window's views the way its renderer does: in place in the window, with Z order and shadows. Child views
  // draw the display lists they already have. UI thread only.
  fun drawWindow(canvas: Canvas, decor: View) {
    val at = IntArray(2).also { decor.getLocationInWindow(it) }
    val count = canvas.save()
    canvas.translate(at[0].toFloat(), at[1].toFloat())
    canvas.enableZ()
    decor.draw(canvas)
    canvas.disableZ()
    canvas.restoreToCount(count)
  }

  // Records draw on the UI thread, renders it into a hardware buffer and calls onResult once on handler, with the
  // bitmap or with null and the reason. readerHandler gets the image, since taking it waits for the GPU to finish.
  fun capture(
    width: Int,
    height: Int,
    light: Lighting,
    handler: Handler,
    readerHandler: Handler,
    draw: (Canvas) -> Unit,
    onResult: (Bitmap?, GpuFailure?) -> Unit,
  ) {
    val node = RenderNode("ThemeRevealSnapshot")
    var reader: ImageReader? = null
    var renderer: HardwareRenderer? = null
    val race = CaptureRace()
    val release = {
      renderer?.destroy()
      reader?.close()
      node.discardDisplayList()
    }
    val timeout = Runnable {
      if (race.finish()) {
        release()
        onResult(null, GpuFailure.TIMEOUT)
      }
    }
    val fail = { failure: GpuFailure ->
      if (race.finish()) {
        handler.removeCallbacks(timeout)
        release()
        onResult(null, failure)
      }
    }
    // Main thread. A bitmap arriving after the timeout or a failure is dropped.
    val deliver = { bitmap: Bitmap? ->
      if (!race.finish()) {
        bitmap?.recycle()
      } else {
        handler.removeCallbacks(timeout)
        if (bitmap == null) {
          release()
          onResult(null, GpuFailure.ERROR)
        } else {
          // Overlay goes up first, then the renderer goes away.
          try {
            onResult(bitmap, null)
          } finally {
            release()
          }
        }
      }
    }
    try {
      node.setPosition(0, 0, width, height)
      val canvas = node.beginRecording(width, height)
      try {
        draw(canvas)
      } finally {
        node.endRecording()
      }
      val images = ImageReader.newInstance(
        width,
        height,
        PixelFormat.RGBA_8888,
        1,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
      )
      reader = images
      images.setOnImageAvailableListener({ source ->
        val bitmap = try {
          source.acquireLatestImage()?.use { image ->
            image.hardwareBuffer?.use { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }
          }
        } catch (_: RuntimeException) {
          null
        }
        // Front of the queue, like the PixelCopy listener, so the overlay can still make a frame that's due.
        handler.postAtFrontOfQueue { deliver(bitmap) }
      }, readerHandler)
      val output = HardwareRenderer()
      renderer = output
      output.setSurface(images.surface)
      output.setContentRoot(node)
      output.setOpaque(false)
      output.setLightSourceGeometry(light.source.x, light.source.y, light.source.z, light.source.radius)
      output.setLightSourceAlpha(light.ambientAlpha, light.spotAlpha)
      if (CapturePlan.syncFailed(output.createRenderRequest().syncAndDraw())) {
        fail(GpuFailure.SYNC)
        return
      }
    } catch (_: RuntimeException) {
      fail(GpuFailure.ERROR)
      return
    }
    handler.postDelayed(timeout, CapturePlan.GPU_TIMEOUT_MS)
  }
}
