package expo.modules.themereveal

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import com.facebook.react.uimanager.RootView
import com.facebook.react.views.modal.ReactModalHostView

// Overlay goes into host at (hostX, hostY), bitmap-sized. The bitmap covers cover (the activity decor view), whose
// top-left corner is at (screenX, screenY) on screen.
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
  fun capture(activity: Activity, root: View?, handler: Handler, onResult: (Snapshot?) -> Unit) {
    val window = activity.window
    val decor = window?.decorView as? ViewGroup
    if (window == null || decor == null) {
      onResult(null)
      return
    }
    val screen = locationOf(decor)
    if (root == null || root === decor) {
      capture(window, decor.width, decor.height, handler) { bitmap ->
        onResult(bitmap?.let { Snapshot(it, decor, decor, 0, 0, screen[0], screen[1]) })
      }
      return
    }
    val dialog = findModalWindow(decor) { it.peekDecorView() === root }
    val dialogDecor = dialog?.peekDecorView() as? ViewGroup
    if (dialog == null || dialogDecor == null) {
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
    capturePair(window, decor.width, decor.height, dialog, dialogDecor.width, dialogDecor.height, handler) { back, front ->
      if (back == null || front == null) {
        back?.recycle()
        front?.recycle()
        onResult(null)
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
        back.recycle()
        onResult(null)
        return@capturePair
      }
      onResult(Snapshot(back, dialogDecor, decor, -dialogX, -dialogY, screen[0], screen[1]))
    }
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

  fun capture(window: Window, width: Int, height: Int, handler: Handler, onResult: (Bitmap?) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || width <= 0 || height <= 0) {
      onResult(null)
      return
    }
    val bitmap = try {
      Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    } catch (_: OutOfMemoryError) {
      onResult(null)
      return
    }
    val listener = PixelCopy.OnPixelCopyFinishedListener { result ->
      if (result == PixelCopy.SUCCESS) {
        onResult(bitmap)
      } else {
        bitmap.recycle()
        onResult(null)
      }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      try {
        PixelCopy.request(window, bitmap, listener, handler)
      } catch (_: RuntimeException) {
        bitmap.recycle()
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
    // with one short message per copy (two for a modal). The last one does a single addView and resolve.
    copyHandler.post {
      try {
        PixelCopy.request(window, bitmap, toFront, copyHandler)
      } catch (_: RuntimeException) {
        handler.post {
          bitmap.recycle()
          onResult(null)
        }
      }
    }
  }

  // Never quit the copy thread. Without it PixelCopy never calls back, prepare never resolves and the switch never
  // applies the theme.
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
    capture(first, firstW, firstH, handler) { done(0, it) }
    capture(second, secondW, secondH, handler) { done(1, it) }
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
