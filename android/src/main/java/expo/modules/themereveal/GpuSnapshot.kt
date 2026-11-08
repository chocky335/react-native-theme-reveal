package expo.modules.themereveal

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.util.DisplayMetrics
import android.util.TypedValue
import android.util.Log
import android.view.Choreographer
import android.view.View
import androidx.annotation.RequiresApi

// Elevation shadows: where the light is and how dark its shadows are.
internal class Lighting(val source: LightSource, val ambientAlpha: Float, val spotAlpha: Float)

// GPU capture target made ready ahead of the capture (GpuSnapshot.prepareOutput): its reader, its renderer with the
// surface already made, and its root node. release frees it, once, unless a capture took it. Called on main, the work
// runs on the copy thread (GpuSnapshot.free). what names it in the debug log.
@RequiresApi(Build.VERSION_CODES.Q)
internal class GpuOutput(
  val width: Int,
  val height: Int,
  val reader: ImageReader,
  val renderer: HardwareRenderer,
  val node: RenderNode,
  private val readerHandler: Handler,
) {
  fun release(what: String = "an unused prepared target") =
    GpuSnapshot.free(readerHandler, renderer, reader, node, what)
}

// API 29+: window snapshot drawn on the GPU into a hardware bitmap. No readback to the CPU and no upload back to the
// GPU like PixelCopy needs.
@RequiresApi(Build.VERSION_CODES.Q)
internal object GpuSnapshot {
  private const val USAGE = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT

  private const val TAG = "ThemeReveal"

  // Debug log name for releasing a capture's own renderer, as opposed to an unused or kept target.
  const val CAPTURE_RENDERER = "a capture's renderer"

  // Longest a capture's release waits for the frame after its image. No frame comes while the screen is off.
  private const val RELEASE_TIMEOUT_MS = 100L

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

  // Target side of pre-importing a held snapshot.
  private const val PRE_IMPORT_SIZE = 8

  // Records the snapshot, drawn scaled down, into a tiny node. Runs on the UI thread so freeing the bitmap can't
  // race the recording. The display list keeps the bitmap's buffer alive until preImport discards it. Draws no
  // view.
  fun preImportNode(bitmap: Bitmap): RenderNode {
    val node = RenderNode("ThemeRevealPreImport")
    try {
      node.setPosition(0, 0, PRE_IMPORT_SIZE, PRE_IMPORT_SIZE)
      val canvas = node.beginRecording(PRE_IMPORT_SIZE, PRE_IMPORT_SIZE)
      try {
        canvas.drawBitmap(bitmap, null, Rect(0, 0, PRE_IMPORT_SIZE, PRE_IMPORT_SIZE), null)
      } finally {
        node.endRecording()
      }
    } catch (thrown: RuntimeException) {
      node.discardDisplayList()
      throw thrown
    }
    return node
  }

  // Draws the node into a tiny offscreen target and waits for the frame, then frees target and node. The shared
  // RenderThread imports the snapshot's buffer as a texture now, during the hold, instead of in the overlay's first
  // frame. Draws no view, so it runs off main.
  fun preImport(node: RenderNode) {
    var images: ImageReader? = null
    var renderer: HardwareRenderer? = null
    try {
      val reader = ImageReader.newInstance(PRE_IMPORT_SIZE, PRE_IMPORT_SIZE, PixelFormat.RGBA_8888, 1, USAGE)
      images = reader
      val output = HardwareRenderer()
      renderer = output
      output.setSurface(reader.surface)
      output.setContentRoot(node)
      output.setOpaque(false)
      output.setLightSourceGeometry(0f, 0f, 1f, 1f)
      output.setLightSourceAlpha(0f, 0f)
      output.createRenderRequest().syncAndDraw()
      // Waits until the frame is drawn.
      output.stop()
      reader.acquireLatestImage()?.close()
    } finally {
      renderer?.destroy()
      images?.close()
      node.discardDisplayList()
    }
  }

  // Gets a width x height capture target ready for a later capture: draws one empty frame into it and waits for that
  // frame, so the surface exists on the RenderThread and its buffer is allocated. The capture's frame reuses that
  // buffer. Draws no view, so it runs off main, on readerHandler's thread. The renderer stays started, since stop()
  // would let its surface go.
  fun prepareOutput(width: Int, height: Int, readerHandler: Handler): GpuOutput {
    val node = RenderNode("ThemeRevealSnapshot")
    var images: ImageReader? = null
    var renderer: HardwareRenderer? = null
    try {
      val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1, USAGE)
      images = reader
      node.setPosition(0, 0, width, height)
      node.beginRecording(width, height)
      node.endRecording()
      val output = HardwareRenderer()
      renderer = output
      output.setSurface(reader.surface)
      output.setContentRoot(node)
      output.setOpaque(false)
      output.setLightSourceGeometry(0f, 0f, 1f, 1f)
      output.setLightSourceAlpha(0f, 0f)
      // Waits until the frame is presented, so its image is already in the reader.
      output.createRenderRequest().setWaitForPresent(true).syncAndDraw()
      // Back to the reader, so the capture's frame dequeues this buffer again.
      reader.acquireNextImage()?.close()
      return GpuOutput(width, height, reader, output, node, readerHandler)
    } catch (thrown: Throwable) {
      renderer?.destroy()
      images?.close()
      node.discardDisplayList()
      throw thrown
    }
  }

  // Runs release on handler after the next frame, which is the overlay's frame when an image just attached the
  // overlay. release only posts the work to the copy thread (free), so its RenderThread work lands after that frame.
  // A timeout runs it if no frame comes.
  private fun afterNextFrame(handler: Handler, release: () -> Unit) {
    val once = Once(release)
    lateinit var timeout: Runnable
    val frame = Choreographer.FrameCallback {
      handler.removeCallbacks(timeout)
      // A message posted after this one runs after the frame's traversal.
      handler.post { once.run() }
    }
    timeout = Runnable {
      Choreographer.getInstance().removeFrameCallback(frame)
      once.run()
    }
    Choreographer.getInstance().postFrameCallback(frame)
    handler.postDelayed(timeout, RELEASE_TIMEOUT_MS)
  }

  // Frees a capture's renderer, reader and node on readerHandler's thread (the reader's own) instead of main:
  // renderer.destroy waits for the RenderThread, 1.0-19.4 ms on a Mi 11 Lite. By then nothing else uses them. Main is
  // done with the renderer, and its stop() and the image listener already ran on that same thread, so the close can't
  // free an image the listener is still reading. A bitmap from the image holds its own reference to the buffer.
  fun free(
    readerHandler: Handler,
    renderer: HardwareRenderer?,
    reader: ImageReader?,
    node: RenderNode,
    what: String = CAPTURE_RENDERER,
  ) {
    readerHandler.post {
      val start = System.nanoTime()
      try {
        renderer?.destroy()
      } finally {
        reader?.close()
        node.discardDisplayList()
      }
      if (Log.isLoggable(TAG, Log.DEBUG)) {
        val ms = (System.nanoTime() - start) / 1e6
        Log.d(TAG, "released $what in %.1f ms on the copy thread".format(ms))
      }
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
  // redraw lists the decor views of windows that had a view change waiting for its frame. prepared: a target from
  // prepareOutput, made with the same readerHandler, to draw into instead of a new one. The capture takes it and frees
  // it when done. One of another size is freed right away.
  fun capture(
    width: Int,
    height: Int,
    light: Lighting,
    redraw: List<View>,
    handler: Handler,
    readerHandler: Handler,
    draw: (Canvas) -> Unit,
    prepared: GpuOutput?,
    onResult: (Bitmap?, GpuFailure?) -> Unit,
  ) {
    val ready = prepared?.takeIf { it.width == width && it.height == height }
    if (prepared != null && ready == null) prepared.release()
    val node = ready?.node ?: RenderNode("ThemeRevealSnapshot")
    var reader: ImageReader? = null
    var renderer: HardwareRenderer? = null
    lateinit var timeout: Runnable
    val end = CaptureEnd<Bitmap>(
      release = {
        if (ready != null) ready.release(CAPTURE_RENDERER) else free(readerHandler, renderer, reader, node)
      },
      recycle = { it.recycle() },
      cancelTimeout = { handler.removeCallbacks(timeout) },
      onResult = onResult,
      afterImage = { release -> afterNextFrame(handler, release) },
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
      val images = ready?.reader ?: ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1, USAGE)
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
      val output = ready?.renderer ?: HardwareRenderer()
      renderer = output
      if (ready == null) {
        output.setSurface(images.surface)
        output.setContentRoot(node)
        output.setOpaque(false)
      }
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
      // it runs off the main thread. The release (free) runs later on the same thread.
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
