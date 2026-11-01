package expo.modules.themereveal

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import expo.modules.kotlin.Promise
import expo.modules.kotlin.functions.Queues
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

// Overlay has to stay the size of cover. The React root the origin comes from sits at (rootX, rootY) on screen, the
// overlay at (screenX, screenY).
private class OverlayGeometry(val cover: View, val rootX: Int, val rootY: Int, val screenX: Int, val screenY: Int)

// Snapshot from capture(), kept out of the window until prepare() attaches it. root is the window root it shows,
// generation the prepare generation when the capture started.
private class HeldCapture(
  val ticket: Int,
  val generation: Int,
  val activity: Activity,
  val root: View?,
  val snapshot: Snapshot,
  val takenAtMs: Long,
)

// capture() whose PixelCopy is still running. A prepare() for the same window sets waiter and gets the snapshot.
private class PendingCapture(val activity: Activity, val root: View?) {
  var waiter: ((Snapshot?) -> Unit)? = null
}

class ThemeRevealModule : Module() {
  private val mainHandler = Handler(Looper.getMainLooper())
  private var overlay: RevealOverlayView? = null
  private var geometry: OverlayGeometry? = null
  private var started: Promise? = null
  private var generation = 0
  private val failsafe = Runnable { removeOverlay() }
  private var held: HeldCapture? = null
  private var pendingCapture: PendingCapture? = null
  private var captureTicket = 0
  private val captureExpiry = Runnable { voidCapture("expired") }

  // Frees the kept bitmaps when the app is hidden or low on memory, or the screen changes size. Runs on main.
  private val memoryCallbacks = object : ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) = WindowSnapshot.kept.onTrimMemory(level)

    override fun onConfigurationChanged(newConfig: Configuration) =
      WindowSnapshot.kept.onScreenSize(newConfig.screenWidthDp, newConfig.screenHeightDp)

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = WindowSnapshot.kept.clear()
  }

  // Context memoryCallbacks are registered with.
  private var callbacksContext: Context? = null

  override fun definition() = ModuleDefinition {
    Name("ThemeReveal")

    AsyncFunction("prepare") { viewTag: Int?, promise: Promise ->
      prepare(viewTag, promise)
    }.runOnQueue(Queues.MAIN)

    AsyncFunction("start") { transition: Map<String, Any?>, promise: Promise ->
      start(TransitionArgs.from(transition), promise)
    }.runOnQueue(Queues.MAIN)

    AsyncFunction("capture") { viewTag: Int?, promise: Promise ->
      capture(viewTag, promise)
    }.runOnQueue(Queues.MAIN)

    AsyncFunction("dropCapture") {
      voidCapture("dropped")
    }.runOnQueue(Queues.MAIN)

    AsyncFunction("cancel") {
      generation++
      voidCapture("cancel")
      removeOverlay()
    }.runOnQueue(Queues.MAIN)

    // Runs off main. JS only calls it when the warmUp option is on.
    AsyncFunction("warmUp") { promise: Promise ->
      warmUp(promise)
    }

    OnCreate {
      callbacksContext = appContext.reactContext?.applicationContext?.also {
        it.registerComponentCallbacks(memoryCallbacks)
        // Size to compare the first change against.
        val config = it.resources.configuration
        mainHandler.post { WindowSnapshot.kept.onScreenSize(config.screenWidthDp, config.screenHeightDp) }
      }
    }

    OnActivityEntersBackground {
      voidCapture("pause")
    }

    OnActivityDestroys {
      generation++
      voidCapture("destroy")
      removeOverlay()
      WindowSnapshot.kept.hide()
    }

    OnDestroy {
      callbacksContext?.unregisterComponentCallbacks(memoryCallbacks)
      callbacksContext = null
      mainHandler.post {
        generation++
        voidCapture("destroy")
        removeOverlay()
        WindowSnapshot.kept.hide()
      }
    }
  }

  // Off main: starts the copy thread. Then, on main, keeps a bitmap of the window's size. Failures go to the log
  // and the promise.
  private fun warmUp(promise: Promise) {
    val error = try {
      WindowSnapshot.warmUp()
      null
    } catch (thrown: Exception) {
      Log.w(TAG, "Cannot start the copy thread", thrown)
      thrown
    }
    mainHandler.post {
      WindowSnapshot.kept.keep = true
      val activity = appContext.currentActivity
      // A JS reload hid the pool in the old module's OnDestroy. This warm-up comes from a mounted UI.
      if (activity != null && isResumed(activity)) WindowSnapshot.kept.show()
      // Copy thread didn't start, so keepOne would fail the same way.
      if (error == null) WindowSnapshot.keepOne(activity, mainHandler)
      if (error == null) promise.resolve() else promise.reject("ERR_THEME_REVEAL_WARM_UP", error.message, error)
    }
  }

  // Takes the snapshot now and keeps it out of the window, so the screen stays live while the finger is down.
  private fun capture(viewTag: Int?, promise: Promise) {
    voidCapture("replaced")
    val ticket = captureTicket
    val gen = generation
    val activity = appContext.currentActivity
    // An overlay on screen would end up in the snapshot, so the switch captures after it instead.
    if (overlay != null || activity == null || activity.window?.decorView == null || activity.isFinishing ||
      !Motion.canAnimate(Build.VERSION.SDK_INT, isResumed(activity))
    ) {
      promise.resolve(false)
      return
    }
    val root = rootOf(activity, viewTag)
    val pending = PendingCapture(activity, root)
    pendingCapture = pending
    WindowSnapshot.capture(activity, root, mainHandler) { snapshot ->
      val waiter = pending.waiter
      if (waiter != null) {
        promise.resolve(snapshot != null)
        waiter(snapshot)
        return@capture
      }
      if (pendingCapture === pending) pendingCapture = null
      if (snapshot == null) {
        promise.resolve(false)
        return@capture
      }
      // A prepare that started during the copy (its PixelCopy can run before or after this one) makes this a frame
      // from before that switch.
      if (!CaptureRules.keepResult(ticket, captureTicket, gen, generation, overlay != null) || activity.isFinishing ||
        activity.isDestroyed || !isResumed(activity) || !snapshot.host.isAttachedToWindow
      ) {
        snapshot.bitmap.recycle()
        debug("dropped a capture result: voided, another switch or no window")
        promise.resolve(false)
        return@capture
      }
      // Upload during the press, so the overlay's first frame doesn't have to.
      snapshot.bitmap.prepareToDraw()
      held = HeldCapture(ticket, gen, activity, root, snapshot, SystemClock.uptimeMillis())
      mainHandler.postDelayed(captureExpiry, CaptureRules.TIMEOUT_MS)
      promise.resolve(true)
    }
  }

  // Frees the held capture and voids a running one no prepare took.
  private fun voidCapture(reason: String) {
    captureTicket++
    pendingCapture = null
    mainHandler.removeCallbacks(captureExpiry)
    val current = held ?: return
    held = null
    current.snapshot.bitmap.recycle()
    debug("freed the held capture: $reason")
  }

  private fun rootOf(activity: Activity, viewTag: Int?): View? {
    val tapped = viewTag?.let { findView(it) }?.takeIf { it.isAttachedToWindow }
    return tapped?.rootView ?: WindowSnapshot.focusedRoot(activity)
  }

  private fun prepare(viewTag: Int?, promise: Promise) {
    val generationBefore = generation
    val ticket = ++generation
    removeOverlay()
    val activity = appContext.currentActivity
    val window = activity?.window
    val decor = window?.decorView as? ViewGroup
    if (activity == null || window == null || decor == null || activity.isFinishing ||
      !Motion.canAnimate(Build.VERSION.SDK_INT, isResumed(activity))
    ) {
      voidCapture("no window")
      promise.resolve(false)
      return
    }
    val root = rootOf(activity, viewTag)
    val tapped = viewTag?.let { findView(it) }?.takeIf { it.isAttachedToWindow }
    val reactRoot = tapped?.let { WindowSnapshot.reactRootOf(it) } ?: root ?: decor
    // A switch that ends without an overlay still changes the theme (JS applies it), so a capture that started
    // during its copy shows the screen from before. A newer prepare (ticket != generation) handles its own.
    fun fail() {
      if (ticket == generation) voidCapture("switch failed")
      promise.resolve(false)
    }
    fun attach(snapshot: Snapshot?) {
      if (snapshot == null) {
        fail()
        return
      }
      val bitmap = snapshot.bitmap
      if (ticket != generation || activity.isFinishing || activity.isDestroyed || !isResumed(activity) ||
        !snapshot.host.isAttachedToWindow
      ) {
        WindowSnapshot.kept.give(bitmap)
        fail()
        return
      }
      // Kick off the snapshot's GPU upload now, on the RenderThread, so it doesn't land in the overlay's first
      // frame. A no-op for a held capture, whose upload started at capture time.
      bitmap.prepareToDraw()
      val view = RevealOverlayView(activity, bitmap)
      val params = FrameLayout.LayoutParams(bitmap.width, bitmap.height, Gravity.TOP or Gravity.LEFT).apply {
        leftMargin = snapshot.hostX
        topMargin = snapshot.hostY
      }
      snapshot.host.addView(view, params)
      val rootAt = WindowSnapshot.locationOf(reactRoot)
      overlay = view
      geometry = OverlayGeometry(snapshot.cover, rootAt[0], rootAt[1], snapshot.screenX, snapshot.screenY)
      mainHandler.postDelayed(failsafe, FAILSAFE_MS)
      // Any capture held or running right now shows the screen from before this switch.
      voidCapture("switch attached")
      // JS applies the theme when this resolves, and that render blocks the JS thread. Resolve in the next frame's
      // callbacks, after React Native's timers for that frame, so the render starts after that frame's JS callbacks
      // instead of holding them up. The overlay draws in that same frame.
      Choreographer.getInstance().postFrameCallback { promise.resolve(true) }
    }
    val kept = held
    if (kept != null && CaptureRules.usable(
        kept.ticket,
        captureTicket,
        kept.generation,
        generationBefore,
        kept.activity === activity && kept.root === root,
        kept.snapshot.bitmap.width,
        kept.snapshot.bitmap.height,
        kept.snapshot.cover.width,
        kept.snapshot.cover.height,
        kept.takenAtMs,
        SystemClock.uptimeMillis(),
      )
    ) {
      held = null
      mainHandler.removeCallbacks(captureExpiry)
      debug("prepare uses the held capture")
      attach(kept.snapshot)
      return
    }
    val pending = pendingCapture
    if (pending != null && pending.activity === activity && pending.root === root) {
      pendingCapture = null
      pending.waiter = ::attach
      debug("prepare waits for the running capture")
      return
    }
    voidCapture("not usable")
    WindowSnapshot.capture(activity, root, mainHandler, ::attach)
  }

  private fun start(transition: TransitionArgs, promise: Promise) {
    val view = overlay
    if (view == null) {
      promise.resolve()
      return
    }
    mainHandler.removeCallbacks(failsafe)
    started?.resolve()
    started = promise
    val cover = geometry?.cover
    val durationMs = Motion.durationMs(transition.durationMs)
    // A modal closing between prepare and start detaches the overlay, so resolve right away.
    if (view.parent == null || !view.isAttachedToWindow || cover == null || durationMs == null ||
      !animatorsEnabled() || !Motion.sameSize(view.snapshotWidth, view.snapshotHeight, cover.width, cover.height)
    ) {
      removeOverlay()
      return
    }
    val onEnd = { if (overlay === view) removeOverlay() }
    val playing = try {
      play(view, transition, durationMs, onEnd)
    } catch (_: RuntimeException) {
      false
    }
    if (!playing) removeOverlay()
  }

  private fun play(view: RevealOverlayView, transition: TransitionArgs, durationMs: Long, onEnd: () -> Unit): Boolean {
    when (transition.type) {
      "reveal", "inverseReveal" -> {
        val origin = transition.origin ?: return false
        val space = geometry ?: return false
        val density = view.resources.displayMetrics.density
        val point = Motion.toOverlay(
          (origin.x * density).toFloat(),
          (origin.y * density).toFloat(),
          space.rootX,
          space.rootY,
          space.screenX,
          space.screenY,
        )
        view.reveal(shapeOf(transition.shape), point[0], point[1], transition.type == "inverseReveal", durationMs, onEnd)
      }
      "wipe" -> view.wipe(wipeDirectionOf(transition.direction) ?: return false, durationMs, onEnd)
      "crossfade" -> view.crossfade(durationMs, onEnd)
      else -> return false
    }
    return true
  }

  private fun removeOverlay() {
    mainHandler.removeCallbacks(failsafe)
    started?.resolve()
    started = null
    val view = overlay ?: return
    overlay = null
    geometry = null
    (view.parent as? ViewGroup)?.removeView(view)
    view.release(WindowSnapshot.kept::give)
  }

  private fun debug(message: String) {
    if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message)
  }

  private fun findView(viewTag: Int): View? = try {
    appContext.findView<View>(viewTag)
  } catch (_: RuntimeException) {
    null
  }

  private fun isResumed(activity: Activity): Boolean =
    (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true

  private fun animatorsEnabled(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

  private fun shapeOf(record: ShapeArgs?): ShapePath {
    if (record == null) return ShapePath.circle()
    return try {
      ShapePath.parse(
        record.path,
        PointF(record.anchor.x.toFloat(), record.anchor.y.toFloat()),
        record.inscribedRadius.toFloat(),
      )
    } catch (error: RuntimeException) {
      Log.w(TAG, "Cannot parse the shape path \"${record.path}\", using the circle", error)
      ShapePath.circle()
    }
  }

  private fun wipeDirectionOf(direction: String?): WipeDirection? = when (direction) {
    "up" -> WipeDirection.UP
    "down" -> WipeDirection.DOWN
    "left" -> WipeDirection.LEFT
    "right" -> WipeDirection.RIGHT
    else -> null
  }

  private companion object {
    const val FAILSAFE_MS = 1000L
    const val TAG = "ThemeReveal"
  }
}
