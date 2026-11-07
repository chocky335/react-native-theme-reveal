package expo.modules.themereveal

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import com.facebook.react.uimanager.RootView
import com.facebook.react.views.modal.ReactModalHostView

// Overlay goes into host's overlay at (hostX, hostY), bitmap-sized. The bitmap covers cover (the activity decor
// view), whose top-left corner is at (screenX, screenY) on screen.
internal class Snapshot(
  val bitmap: Bitmap,
  val host: ViewGroup,
  val cover: View,
  val hostX: Int,
  val hostY: Int,
  val screenX: Int,
  val screenY: Int,
)

internal object WindowSnapshot {
  private const val TAG = "ThemeReveal"

  // Activity-window bitmaps for the next PixelCopy capture. A GPU snapshot is a hardware bitmap, and those are
  // immutable: PixelCopy can't copy into it, so it isn't kept. Main thread only.
  val kept = KeptBitmaps<Bitmap>(1, { it.width }, { it.height }, { it.isRecycled }, { it.isMutable }) { it.recycle() }

  fun capture(activity: Activity, root: View?, handler: Handler, onResult: (Snapshot?) -> Unit) =
    capture(activity, root, handler, null, null, onResult)

  // The RenderThread reveal's second snapshot, API 29+: GPU capture only, without hide (the overlay), into prepared
  // if it's not null. Takes prepared and frees it, even when it doesn't use it. No PixelCopy fallback, since a
  // PixelCopy would show the overlay. A failure gives null and doesn't turn off the GPU path: the next switch's first
  // snapshot tests that path under its own rules.
  fun captureSecond(
    activity: Activity,
    root: View?,
    handler: Handler,
    hide: View,
    prepared: GpuOutput?,
    onResult: (Snapshot?) -> Unit,
  ) = capture(activity, root, handler, hide, prepared, onResult)

  // hide: see captureWindows.
  private fun capture(
    activity: Activity,
    root: View?,
    handler: Handler,
    hide: View?,
    prepared: GpuOutput?,
    onResult: (Snapshot?) -> Unit,
  ) {
    val window = activity.window
    val decor = window?.decorView as? ViewGroup
    if (window == null || decor == null) {
      releaseOutput(prepared)
      onResult(null)
      return
    }
    val screen = locationOf(decor)
    if (root == null || root === decor) {
      val pixelCopy = { done: (Bitmap?) -> Unit -> capture(window, decor.width, decor.height, handler, true, done) }
      val draw = { canvas: Canvas -> GpuSnapshot.drawWindow(canvas, decor) }
      captureWindows(activity, decor, listOf(window), handler, draw, pixelCopy, hide, prepared) {
        onResult(it?.let { bitmap -> Snapshot(bitmap, decor, decor, 0, 0, screen[0], screen[1]) })
      }
      return
    }
    val dialog = findModalWindow(decor) { it.peekDecorView() === root }
    val dialogDecor = dialog?.peekDecorView() as? ViewGroup
    if (dialog == null || dialogDecor == null) {
      releaseOutput(prepared)
      onResult(null)
      return
    }
    val at = locationOf(dialogDecor)
    val dialogX = at[0] - screen[0]
    val dialogY = at[1] - screen[1]
    val frame = Motion.dialogFrame(dialogX, dialogY, dialogDecor.width, dialogDecor.height, decor.width, decor.height)
    val attributes = dialog.attributes
    val dim = if (attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
      Motion.dimAlpha(attributes.dimAmount)
    } else {
      0
    }
    // The GPU draws both windows and the dim into one picture, like compose does with the two PixelCopy bitmaps.
    val draw = { canvas: Canvas ->
      GpuSnapshot.drawWindow(canvas, decor)
      if (dim > 0) canvas.drawColor(Color.argb(dim, 0, 0, 0))
      if (!frame.isEmpty) {
        val count = canvas.save()
        canvas.clipRect(frame.left, frame.top, frame.right, frame.bottom)
        canvas.translate(dialogX.toFloat(), dialogY.toFloat())
        GpuSnapshot.drawWindow(canvas, dialogDecor)
        canvas.restoreToCount(count)
      }
    }
    val pixelCopy = { done: (Bitmap?) -> Unit ->
      capturePair(window, decor.width, decor.height, dialog, dialogDecor.width, dialogDecor.height, handler) { back, front ->
        if (back == null || front == null) {
          back?.let(kept::give)
          front?.recycle()
          done(null)
          return@capturePair
        }
        val composed = try {
          compose(back, front, frame, dialogX, dialogY, dim)
          true
        } catch (_: RuntimeException) {
          false
        } finally {
          front.recycle()
        }
        if (!composed) {
          kept.give(back)
          done(null)
          return@capturePair
        }
        done(back)
      }
    }
    captureWindows(activity, decor, listOf(window, dialog), handler, draw, pixelCopy, hide, prepared) {
      onResult(it?.let { bitmap -> Snapshot(bitmap, dialogDecor, decor, -dialogX, -dialogY, screen[0], screen[1]) })
    }
  }

  // Set when a GPU capture timed out or threw: from then on the process captures with PixelCopy. Main thread only.
  var gpuOff = false
    private set

  // Snapshot of windows, decor-sized, drawn on the GPU on API 29+. Read back with PixelCopy on API 26-28, for a wide
  // color window, and when the GPU capture fails. With hide set (the second snapshot): the draw leaves that view
  // out, null instead of PixelCopy, and a failure leaves gpuOff alone. prepared: see captureSecond.
  private fun captureWindows(
    activity: Activity,
    decor: View,
    windows: List<Window>,
    handler: Handler,
    draw: (Canvas) -> Unit,
    pixelCopy: ((Bitmap?) -> Unit) -> Unit,
    hide: View?,
    prepared: GpuOutput?,
    onResult: (Bitmap?) -> Unit,
  ) {
    val gpuOnly = hide != null
    val noCopy = { done: (Bitmap?) -> Unit -> done(null) }
    val fallback = if (gpuOnly) noCopy else pixelCopy
    val api = Build.VERSION.SDK_INT
    if (api >= Build.VERSION_CODES.Q) {
      val decors = windows.mapNotNull { it.peekDecorView() }
      val defaultColor = windows.all { it.colorMode == ActivityInfo.COLOR_MODE_DEFAULT }
      val accelerated = decors.all { it.isHardwareAccelerated }
      // A decor is dirty while a view change waits for the window's next frame. Read it before the draw clears it.
      val dirty = decors.filter { it.isDirty }
      val pending = dirty.isNotEmpty()
      val forceDark = pending && GpuSnapshot.forceDarkCanApply(activity)
      val light = if (CapturePlan.path(api, defaultColor, accelerated, forceDark, gpuOff) == CapturePath.GPU) {
        GpuSnapshot.lightOf(activity, decor)
      } else {
        null
      }
      if (light != null && decor.width > 0 && decor.height > 0 && !preCreates.skipsGpu()) {
        if (pending && Log.isLoggable(TAG, Log.DEBUG)) {
          Log.d(TAG, "a view change waits for its frame in ${dirty.size} window(s)")
        }
        // Image arrives on the copy thread, where a warm-up pre-create can hold it back past the timeout.
        val busyAtStart = preCreates.busy
        val countAtStart = preCreates.count
        // The view gets hidden inside the draw, after the dirty read above: hiding it marks its window dirty, which
        // would read as a view change under force dark.
        val record = if (hide == null) draw else drawWithout(hide, draw)
        GpuSnapshot.capture(decor.width, decor.height, light, dirty, handler, copyHandler, record, prepared) {
            bitmap, failure ->
          if (bitmap != null) {
            onResult(bitmap)
            return@capture
          }
          if (!gpuOnly && failure != null && preCreates.turnsGpuOff(failure, busyAtStart, countAtStart)) gpuOff = true
          fallback(onResult)
        }
        return
      }
    }
    releaseOutput(prepared)
    fallback(onResult)
  }

  // draw with view hidden, on the UI thread. The window re-records the view in its next frame.
  private fun drawWithout(view: View, draw: (Canvas) -> Unit): (Canvas) -> Unit = { canvas ->
    view.visibility = View.INVISIBLE
    try {
      draw(canvas)
    } finally {
      view.visibility = View.VISIBLE
    }
  }

  // Gets a GPU capture target of width x height ready on the copy thread (GpuSnapshot.prepareOutput). onReady gets it
  // on handler, with the ms it took on the copy thread, or null if it failed. The caller frees it with releaseOutput or
  // hands it to captureSecond. Main thread.
  fun prepareOutput(width: Int, height: Int, handler: Handler, onReady: (GpuOutput?, Double) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || width <= 0 || height <= 0) {
      onReady(null, 0.0)
      return
    }
    // Holds the copy thread like a warm-up pre-create.
    preCreates.started()
    copyHandler.post {
      val start = System.nanoTime()
      val output = try {
        GpuSnapshot.prepareOutput(width, height, copyHandler)
      } catch (_: RuntimeException) {
        null
      } catch (_: OutOfMemoryError) {
        // HardwareRenderer throws it when HWUI can't make its native proxy.
        null
      }
      val ms = (System.nanoTime() - start) / 1e6
      handler.post {
        preCreates.finished()
        onReady(output, ms)
      }
    }
  }

  // Frees a target from prepareOutput. Main thread.
  fun releaseOutput(prepared: GpuOutput?) {
    if (prepared != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) prepared.release()
  }

  // Pre-imports a held GPU snapshot's texture on the copy thread (GpuSnapshot.preImport). Failures are ignored: the
  // overlay's first frame imports it as before. Main thread: handler is the main thread's.
  fun preImport(bitmap: Bitmap, handler: Handler) {
    val api = Build.VERSION.SDK_INT
    // Same API check again, in a form lint understands.
    if (api < Build.VERSION_CODES.Q || bitmap.isRecycled || !RevealPlan.preImports(api, pathOf(bitmap))) return
    val node = try {
      GpuSnapshot.preImportNode(bitmap)
    } catch (_: RuntimeException) {
      return
    }
    // Holds the copy thread like a warm-up pre-create.
    preCreates.started()
    copyHandler.post {
      val start = System.nanoTime()
      try {
        GpuSnapshot.preImport(node)
        if (Log.isLoggable(TAG, Log.DEBUG)) {
          Log.d(TAG, "pre-imported the held snapshot in %.1f ms".format((System.nanoTime() - start) / 1e6))
        }
      } catch (_: RuntimeException) {
        // Ignored.
      } catch (_: OutOfMemoryError) {
        // HardwareRenderer throws it when HWUI can't make its native proxy.
      } finally {
        handler.post { preCreates.finished() }
      }
    }
  }

  // The path that took a snapshot: a GPU capture gives a hardware bitmap.
  fun pathOf(bitmap: Bitmap): CapturePath =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
      CapturePath.GPU
    } else {
      CapturePath.PIXEL_COPY
    }

  // Focused window root: the activity decor view, or a React Native modal's decor view.
  fun focusedRoot(activity: Activity): View? {
    val decor = activity.window?.decorView as? ViewGroup ?: return null
    if (decor.hasWindowFocus()) return decor
    return findModalWindow(decor) { it.peekDecorView()?.hasWindowFocus() == true }?.peekDecorView() ?: decor
  }

  // React root that JS measures pageX and pageY against.
  fun reactRootOf(view: View): View {
    var current: View? = view
    while (current != null) {
      if (current is RootView) return current
      current = current.parent as? View
    }
    return view.rootView
  }

  fun locationOf(view: View): IntArray = IntArray(2).also { view.getLocationOnScreen(it) }

  // pooled: the bitmap comes from kept and goes back there (the activity window). A dialog window's bitmap doesn't.
  fun capture(window: Window, width: Int, height: Int, handler: Handler, pooled: Boolean, onResult: (Bitmap?) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || width <= 0 || height <= 0) {
      onResult(null)
      return
    }
    val bitmap = (if (pooled) kept.take(width, height) else null) ?: try {
      Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    } catch (_: OutOfMemoryError) {
      onResult(null)
      return
    }
    copyInto(window, bitmap, handler, { if (pooled) kept.give(bitmap) else bitmap.recycle() }, onResult)
  }

  // Copies window into bitmap. On failure calls drop, then onResult(null).
  private fun copyInto(window: Window, bitmap: Bitmap, handler: Handler, drop: () -> Unit, onResult: (Bitmap?) -> Unit) {
    val listener = PixelCopy.OnPixelCopyFinishedListener { result ->
      if (result == PixelCopy.SUCCESS) {
        onResult(bitmap)
      } else {
        drop()
        onResult(null)
      }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      try {
        PixelCopy.request(window, bitmap, listener, handler)
      } catch (_: RuntimeException) {
        drop()
        onResult(null)
      }
      return
    }
    // API 26-33: PixelCopy.request blocks the calling thread until the RenderThread has read the window back (40-47
    // ms for 1080x2400 on a Mi 11 Lite). So the request runs on copyHandler and the main thread stays free. The
    // listener and all failures still run on handler. The listener is posted to the front of handler's queue: the
    // readback often finishes right as a vsync arrives, and this way the frame queued for that vsync runs after the
    // listener, with the overlay, instead of before it (one frame earlier on a Mi 11 Lite). The result can also
    // jump ahead of a queued cancel, prepare or onPause. Harmless: JS drops a result whose switch is no longer the
    // latest, and the next removeOverlay cleans up its overlay.
    val toFront = PixelCopy.OnPixelCopyFinishedListener { result ->
      handler.postAtFrontOfQueue { listener.onPixelCopyFinished(result) }
    }
    // Off main, PixelCopy.request(window) reads peekDecorView().getViewRootImpl(), its mSurface (final field, Surface
    // locks internally), mWindowAttributes.surfaceInsets and mWidth/mHeight. A stale read means either a failed copy
    // (invalid surface throws, caught below, or the listener gets an error) or a size mismatch (Motion.sameSize in
    // start drops the overlay), and both go down the existing failure path. postAtFrontOfQueue jumps the main queue
    // with one short message per copy (two for a modal). The last one attaches the overlay and resolves.
    copyHandler.post {
      try {
        PixelCopy.request(window, bitmap, toFront, copyHandler)
      } catch (_: RuntimeException) {
        handler.post {
          drop()
          onResult(null)
        }
      }
    }
  }

  // Off main. Starts the copy thread so the first switch doesn't have to.
  fun warmUp() {
    copyHandler
  }

  // Main thread, after warmUp. Prepares the path the activity window's first switch takes: on API 29+, a GPU
  // capture of nothing on the copy thread with no bitmap kept. Otherwise (a window that takes PixelCopy) keepOne.
  fun prepareFirst(activity: Activity?, handler: Handler) {
    if (warmUpOf(activity) == WarmUp.KEPT_BITMAP) {
      keepOne(activity, handler)
      return
    }
    preCreates.started()
    copyHandler.post {
      try {
        GpuSnapshot.preCreate()
      } catch (thrown: Exception) {
        // Then the first switch pays for it.
        Log.w(TAG, "Cannot prepare the GPU capture", thrown)
      } catch (thrown: OutOfMemoryError) {
        Log.w(TAG, "Cannot prepare the GPU capture", thrown)
      } finally {
        handler.post { preCreates.finished() }
      }
    }
  }

  // The activity window's inputs to the capture path, read the same way captureWindows reads them. Without a
  // window, the GPU capture is the likely path from API 29.
  private fun warmUpOf(activity: Activity?): WarmUp {
    val api = Build.VERSION.SDK_INT
    if (api < Build.VERSION_CODES.Q) return WarmUp.KEPT_BITMAP
    val window = activity?.window
    val decor = window?.peekDecorView()
    if (activity == null || window == null || decor == null) return CapturePlan.warmUp(api, true, true, true, gpuOff)
    return CapturePlan.warmUp(
      api,
      window.colorMode == ActivityInfo.COLOR_MODE_DEFAULT,
      decor.isHardwareAccelerated,
      GpuSnapshot.lightOf(activity, decor) != null,
      gpuOff,
    )
  }

  private val preCreates = PreCreates()

  // Main thread. If kept has room, allocates a bitmap of the activity window's size on the copy thread, copies the
  // window into it once and keeps it. The first copy into a bitmap is the slow one (roughly twice as long as later
  // ones into the same bitmap on a Pixel 8 Pro), so the first switch gets a warm one.
  fun keepOne(activity: Activity?, handler: Handler) {
    val window = activity?.window ?: return
    val decor = window.decorView
    val width = decor.width
    val height = decor.height
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || keeping || width <= 0 || height <= 0 ||
      !kept.hasRoom(width, height)
    ) {
      return
    }
    keeping = true
    copyHandler.post {
      val bitmap = try {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      } catch (_: OutOfMemoryError) {
        null
      }
      handler.post {
        if (bitmap == null) {
          keeping = false
          return@post
        }
        val keep = {
          keeping = false
          kept.fill(bitmap)
        }
        copyInto(window, bitmap, handler, { keep() }) { if (it != null) keep() }
      }
    }
  }

  // A keepOne is in flight. Main thread only.
  private var keeping = false

  // Never quit the copy thread. Without it PixelCopy never calls back, prepare never resolves and the switch never
  // applies the theme. The GPU capture takes its images on it too.
  private val copyHandler: Handler by lazy {
    val thread = HandlerThread("ThemeRevealCopy", Process.THREAD_PRIORITY_DISPLAY)
    thread.start()
    Handler(thread.looper)
  }

  // Both copies run concurrently. onResult fires once, after the second one finishes.
  private fun capturePair(
    first: Window,
    firstW: Int,
    firstH: Int,
    second: Window,
    secondW: Int,
    secondH: Int,
    handler: Handler,
    onResult: (Bitmap?, Bitmap?) -> Unit,
  ) {
    val results = arrayOfNulls<Bitmap>(2)
    var pending = 2
    val done = { index: Int, bitmap: Bitmap? ->
      results[index] = bitmap
      pending--
      if (pending == 0) onResult(results[0], results[1])
    }
    capture(first, firstW, firstH, handler, true) { done(0, it) }
    capture(second, secondW, secondH, handler, false) { done(1, it) }
  }

  // PixelCopy doesn't capture the dim behind a dialog: the compositor draws it.
  private fun compose(back: Bitmap, front: Bitmap, frame: Frame, frontX: Int, frontY: Int, dimAlpha: Int) {
    val canvas = Canvas(back)
    if (dimAlpha > 0) canvas.drawColor(Color.argb(dimAlpha, 0, 0, 0))
    if (frame.isEmpty) return
    val source = Rect(frame.left - frontX, frame.top - frontY, frame.right - frontX, frame.bottom - frontY)
    canvas.drawBitmap(front, source, Rect(frame.left, frame.top, frame.right, frame.bottom), null)
  }

  private fun findModalWindow(view: View, match: (Window) -> Boolean): Window? {
    if (view is ReactModalHostView) {
      val window = view.dialog?.takeIf { it.isShowing }?.window
      if (window != null && match(window)) return window
    }
    if (view !is ViewGroup) return null
    for (index in 0 until view.childCount) {
      val found = findModalWindow(view.getChildAt(index) ?: continue, match)
      if (found != null) return found
    }
    return null
  }
}
