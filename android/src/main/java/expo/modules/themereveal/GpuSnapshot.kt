package expo.modules.themereveal

import android.app.Activity
import android.content.res.Configuration
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
  private const val USAGE = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT

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

  // Warm-up, off the main thread: a 1x1 capture with no window in it. Loads the reader's and renderer's classes and
  // native code and draws one frame, so the first switch doesn't have to. Draws no view, so it can run off main.
  fun preCreate() {
    val node = RenderNode("ThemeRevealWarmUp")
    val images = ImageReader.newInstance(1, 1, PixelFormat.RGBA_8888, 1, USAGE)
    var renderer: HardwareRenderer? = null
    try {
      node.setPosition(0, 0, 1, 1)
      node.beginRecording(1, 1)
      node.endRecording()
      val output = HardwareRenderer()
      renderer = output
      output.setSurface(images.surface)
      output.setContentRoot(node)
      output.setOpaque(false)
      output.setLightSourceGeometry(0f, 0f, 1f, 1f)
      output.setLightSourceAlpha(0f, 0f)
      output.createRenderRequest().syncAndDraw()
      // Waits until the frame is drawn.
      output.stop()
      images.acquireLatestImage()?.use { image ->
        image.hardwareBuffer?.use { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB))?.recycle() }
      }
    } finally {
      renderer?.destroy()
      images.close()
      node.discardDisplayList()
    }
  }

  // Force dark can darken this activity's windows (see CapturePlan.forceDarkCanApply).
  fun forceDarkCanApply(activity: Activity): Boolean {
    val value = TypedValue()
    val theme = activity.theme
    val lightTheme = !theme.resolveAttribute(android.R.attr.isLightTheme, value, true) || value.data != 0
    val allowed = theme.resolveAttribute(android.R.attr.forceDarkAllowed, value, true) && value.data != 0
    val night = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
      Configuration.UI_MODE_NIGHT_YES
    return CapturePlan.forceDarkCanApply(Build.VERSION.SDK_INT, night, lightTheme, allowed)
  }

  // Records draw on the UI thread, renders it into a hardware buffer and calls onResult once on handler, with the
  // bitmap or with null and the reason. readerHandler gets the image, since taking it waits for the GPU to finish.
  // redraw lists the decor views of windows that had a view change waiting for its frame.
  fun capture(
    width: Int,
    height: Int,
    light: Lighting,
    redraw: List<View>,
    handler: Handler,
    readerHandler: Handler,
    draw: (Canvas) -> Unit,
    onResult: (Bitmap?, GpuFailure?) -> Unit,
  ) {
    val node = RenderNode("ThemeRevealSnapshot")
    var reader: ImageReader? = null
    var renderer: HardwareRenderer? = null
    lateinit var timeout: Runnable
    val end = CaptureEnd<Bitmap>(
      release = {
        renderer?.destroy()
        // On the reader's own thread, so the close can't free an image the listener is still reading.
        reader?.let { images -> readerHandler.post { images.close() } }
        node.discardDisplayList()
      },
      recycle = { it.recycle() },
      cancelTimeout = { handler.removeCallbacks(timeout) },
      onResult = onResult,
    )
    timeout = Runnable { end.timeout() }
    try {
      node.setPosition(0, 0, width, height)
      val canvas = node.beginRecording(width, height)
      try {
        draw(canvas)
      } finally {
        node.endRecording()
      }
      val images = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1, USAGE)
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
        handler.postAtFrontOfQueue { end.image(bitmap) }
      }, readerHandler)
      val output = HardwareRenderer()
      renderer = output
      output.setSurface(images.surface)
      output.setContentRoot(node)
      output.setOpaque(false)
      output.setLightSourceGeometry(light.source.x, light.source.y, light.source.z, light.source.radius)
      output.setLightSourceAlpha(light.ambientAlpha, light.spotAlpha)
      val result = output.createRenderRequest().syncAndDraw()
      // The sync consumed the damage of views that changed since the window's last frame, so the window would skip
      // redrawing them. Redraw these windows in full.
      redraw.forEach { it.invalidate() }
      if (CapturePlan.syncFailed(result)) {
        end.fail(GpuFailure.SYNC)
        return
      }
      // A RenderThread animation in the window (a ripple, say) would make this renderer redraw the whole window
      // every vsync until release. Stop it once its frame is drawn. stop() waits for that on the RenderThread, so
      // it runs off the main thread. destroy() stays on main.
      readerHandler.post {
        try {
          output.stop()
        } catch (_: RuntimeException) {
          // Nothing to stop.
        }
      }
    } catch (_: RuntimeException) {
      end.fail(GpuFailure.ERROR)
      return
    } catch (_: OutOfMemoryError) {
      // HardwareRenderer throws it when HWUI can't make its native proxy.
      end.fail(GpuFailure.ERROR)
      return
    }
    handler.postDelayed(timeout, CapturePlan.GPU_TIMEOUT_MS)
  }
}
