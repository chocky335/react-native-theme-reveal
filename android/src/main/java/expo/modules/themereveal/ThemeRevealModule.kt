package expo.modules.themereveal

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.MessageQueue
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import expo.modules.kotlin.Promise
import expo.modules.kotlin.functions.Queues
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlin.math.roundToInt

// Overlay has to stay the size of cover. The React root the origin comes from sits at (rootX, rootY) on screen, the
// overlay at (screenX, screenY). host is the view whose ViewOverlay holds the overlay, the window root the snapshot
// shows, in activity's window.
private class OverlayGeometry(
  val cover: View,
  val rootX: Int,
  val rootY: Int,
  val screenX: Int,
  val screenY: Int,
  val host: ViewGroup,
  val activity: Activity,
)

// Frame-commit callback that release can empty: nothing unregisters one, and the window holds on to it until its
// next commit.
private class CommitRelay(var target: (() -> Unit)?) : Runnable {
  override fun run() {
    target?.invoke()
  }
}

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

  // RenderThread reveal: the wait for its second snapshot, the view showing that snapshot, and the animator on the
  // RenderThread (reveal or inverse reveal).
  private var secondSnapshot: SecondSnapshot? = null
  private var secondView: RevealOverlayView? = null
  private var renderThreadAnimator: Animator? = null

  // Set in OnDestroy, and again first thing in its main message. From then on this module prepares no kept target.
  @Volatile
  private var destroyed = false

  // Prepare whose own capture is running (its generation), or -1. A cancel or a newer prepare makes it stale.
  private var captureFor = -1

  // The kept capture target's prepare: KEEP_TARGET_DELAY_MS after a switch or capture ends, then in main's next
  // idle time, and only if CaptureRules.keepsTargetNow still holds by then.
  private val keepTargetIdle = MessageQueue.IdleHandler {
    // After OnDestroy, this module's state tells nothing about the new module's switch, and appContext can already
    // be gone.
    if (destroyed) return@IdleHandler false
    val activity = appContext.currentActivity
    val now = activity != null && CaptureRules.keepsTargetNow(
      overlay != null,
      pendingCapture != null,
      held != null,
      captureFor == generation,
      isResumed(activity),
    )
    if (now) WindowSnapshot.keepTarget(activity, mainHandler)
    false
  }
  private val keepTargetSoon = Runnable {
    Looper.getMainLooper().queue.removeIdleHandler(keepTargetIdle)
    Looper.getMainLooper().queue.addIdleHandler(keepTargetIdle)
  }

  // Frees the kept bitmaps when the app is hidden or low on memory, or the screen changes size. Runs on main.
  private val memoryCallbacks = object : ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
      WindowSnapshot.kept.onTrimMemory(level)
      WindowSnapshot.target.onTrimMemory(level)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
      WindowSnapshot.kept.onScreenSize(newConfig.screenWidthDp, newConfig.screenHeightDp)
      WindowSnapshot.target.onScreenSize(newConfig.screenWidthDp, newConfig.screenHeightDp)
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
      WindowSnapshot.kept.clear()
      WindowSnapshot.target.clear()
    }
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
        mainHandler.post {
          WindowSnapshot.kept.onScreenSize(config.screenWidthDp, config.screenHeightDp)
          WindowSnapshot.target.onScreenSize(config.screenWidthDp, config.screenHeightDp)
        }
      }
    }

    OnActivityEntersBackground {
      voidCapture("pause")
    }

    // A prepare refused by a pause runs now. After a trim the target stays hidden until the next snapshot.
    OnActivityEntersForeground {
      scheduleKeepTarget()
    }

    OnActivityDestroys {
      generation++
      voidCapture("destroy")
      removeOverlay()
      WindowSnapshot.kept.hide()
      WindowSnapshot.target.hide()
    }

    OnDestroy {
      // Here too, off main, for an idle handler that runs before the main message below.
      destroyed = true
      callbacksContext?.unregisterComponentCallbacks(memoryCallbacks)
      callbacksContext = null
      mainHandler.post {
        destroyed = true
        generation++
        voidCapture("destroy")
        removeOverlay()
        cancelKeepTarget()
        WindowSnapshot.kept.hide()
        WindowSnapshot.target.hide()
        // Next module keeps a target only if its own warm-up runs.
        WindowSnapshot.target.keep = false
      }
    }
  }

  // Off main: starts the copy thread. Then, on main, prepares the first switch's capture: GPU capture on API 29+,
  // otherwise a kept bitmap of the window's size. Failures go to the log and the promise.
  private fun warmUp(promise: Promise) {
    loadClasses()
    val error = try {
      WindowSnapshot.warmUp()
      null
    } catch (thrown: Exception) {
      Log.w(TAG, "Cannot start the copy thread", thrown)
      thrown
    }
    mainHandler.post {
      WindowSnapshot.kept.keep = true
      WindowSnapshot.target.keep = true
      val activity = appContext.currentActivity
      // A JS reload hid the pool in the old module's OnDestroy. This warm-up comes from a mounted UI.
      if (activity != null && isResumed(activity)) {
        WindowSnapshot.kept.show()
        WindowSnapshot.target.show()
      }
      // Copy thread didn't start, so the first switch's path can't be prepared either.
      if (error == null) {
        WindowSnapshot.prepareFirst(activity, mainHandler)
        // Not right away: the app's first frames after the mount share the RenderThread with it.
        scheduleKeepTarget()
      }
      if (error == null) promise.resolve() else promise.reject("ERR_THEME_REVEAL_WARM_UP", error.message, error)
    }
  }

  // Off main: loads most of the classes a switch uses, so the first switch doesn't load them on the main thread.
  // Synthetic lambda classes still load on first use. A class in this list must not touch the main looper in its
  // static initializer, and none can fail there: a class whose initializer threw can't be used later either.
  private fun loadClasses() {
    val classes = listOf(
      HeldCapture::class.java,
      PendingCapture::class.java,
      OverlayGeometry::class.java,
      CommitRelay::class.java,
      SecondSnapshot::class.java,
      Snapshot::class.java,
      RevealOverlayView::class.java,
      Once::class.java,
      CaptureRules::class.java,
      CaptureEnd::class.java,
      Motion::class.java,
      ShapePath::class.java,
      RevealPlan::class.java,
      RevealPath::class.java,
      GrowthInterpolator::class.java,
      ShrinkInterpolator::class.java,
      FallbackCounter::class.java,
      SecondSnapshotWait::class.java,
      WaitState::class.java,
      KeptTarget::class.java,
    )
    for (type in classes) {
      try {
        Class.forName(type.name, true, type.classLoader)
      } catch (_: LinkageError) {
        // Failed verification or link: the first use reports it.
      } catch (_: ClassNotFoundException) {
        // Not in this build.
      }
    }
  }

  // Takes the snapshot now and keeps it out of the window, so the screen stays live while the finger is down.
  private fun capture(viewTag: Int?, promise: Promise) {
    voidCapture("replaced")
    // The prepare would hold up this capture's image on the copy thread.
    cancelKeepTarget()
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
        scheduleKeepTarget()
        promise.resolve(false)
        return@capture
      }
      // A prepare that started during the copy (its PixelCopy can run before or after this one) makes this a frame
      // from before that switch.
      if (!CaptureRules.keepResult(ticket, captureTicket, gen, generation, overlay != null) || activity.isFinishing ||
        activity.isDestroyed || !isResumed(activity) || !snapshot.host.isAttachedToWindow
      ) {
        WindowSnapshot.kept.give(snapshot.bitmap)
        debug("dropped a capture result: voided, another switch or no window")
        // The capture could have used the kept target, and no overlay is coming.
        scheduleKeepTarget()
        promise.resolve(false)
        return@capture
      }
      // Upload a PixelCopy bitmap during the press, so the overlay's first frame doesn't have to. A GPU snapshot is
      // already on the GPU.
      snapshot.bitmap.prepareToDraw()
      held = HeldCapture(ticket, gen, activity, root, snapshot, SystemClock.uptimeMillis())
      debug("held a capture: ${WindowSnapshot.pathOf(snapshot.bitmap).js}")
      // A GPU snapshot's texture is imported on the RenderThread at its first draw, i.e. the overlay's first frame.
      // Do it now, during the hold.
      WindowSnapshot.preImport(snapshot.bitmap, mainHandler)
      mainHandler.postDelayed(captureExpiry, CaptureRules.TIMEOUT_MS)
      promise.resolve(true)
    }
  }

  // Frees the held capture and voids a running one no prepare took. A PixelCopy bitmap goes back to the kept
  // bitmaps, a GPU snapshot gets freed.
  private fun voidCapture(reason: String) {
    captureTicket++
    val running = pendingCapture != null
    pendingCapture = null
    mainHandler.removeCallbacks(captureExpiry)
    // That capture could have used the kept target, and no overlay is coming for it.
    if (running || held != null) scheduleKeepTarget()
    val current = held ?: return
    held = null
    WindowSnapshot.kept.give(current.snapshot.bitmap)
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
    // This switch's capture is using the copy thread now.
    cancelKeepTarget()
    val activity = appContext.currentActivity
    val window = activity?.window
    val decor = window?.decorView as? ViewGroup
    if (activity == null || window == null || decor == null || activity.isFinishing ||
      !Motion.canAnimate(Build.VERSION.SDK_INT, isResumed(activity))
    ) {
      voidCapture("no window")
      promise.resolve(null)
      return
    }
    val root = rootOf(activity, viewTag)
    val tapped = viewTag?.let { findView(it) }?.takeIf { it.isAttachedToWindow }
    val reactRoot = tapped?.let { WindowSnapshot.reactRootOf(it) } ?: root ?: decor
    // A switch that ends without an overlay still changes the theme (JS applies it), so a capture that started
    // during its copy shows the screen from before. A newer prepare (ticket != generation) handles its own.
    fun fail() {
      if (captureFor == ticket) captureFor = -1
      if (ticket == generation) voidCapture("switch failed")
      // The capture could have used the kept target, and no overlay is coming.
      scheduleKeepTarget()
      promise.resolve(null)
    }
    fun attach(snapshot: Snapshot?) {
      if (captureFor == ticket) captureFor = -1
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
      // frame. A no-op for a held capture, whose upload started at capture time, and for a GPU snapshot, which is
      // already on the GPU.
      bitmap.prepareToDraw()
      val view = RevealOverlayView(activity, bitmap)
      // In the host's overlay, not among its children: adding or removing a child lays out the whole window, and
      // after that layout the traversal made two binder calls to the system (3-4 ms on a Mi 11 Lite) in the overlay's
      // first frame and again in the frame that removes it. The overlay draws above every child and lays nothing out.
      snapshot.host.overlay.add(view)
      view.layout(snapshot.hostX, snapshot.hostY, snapshot.hostX + bitmap.width, snapshot.hostY + bitmap.height)
      val rootAt = WindowSnapshot.locationOf(reactRoot)
      overlay = view
      geometry = OverlayGeometry(
        snapshot.cover,
        rootAt[0],
        rootAt[1],
        snapshot.screenX,
        snapshot.screenY,
        snapshot.host,
        activity,
      )
      mainHandler.postDelayed(failsafe, FAILSAFE_MS)
      // Any capture held or running right now shows the screen from before this switch.
      voidCapture("switch attached")
      // JS applies the theme when this resolves, and that render blocks the JS thread. Resolve in the next frame's
      // callbacks, after React Native's timers for that frame, so the render starts after that frame's JS callbacks
      // instead of holding them up. The overlay draws in that same frame. No frame comes while the screen is off,
      // so a timeout also resolves, whichever fires first.
      val path = WindowSnapshot.pathOf(bitmap).js
      val resolve = Once { promise.resolve(path) }
      lateinit var timeout: Runnable
      val frame = Choreographer.FrameCallback {
        mainHandler.removeCallbacks(timeout)
        resolve.run()
      }
      timeout = Runnable {
        Choreographer.getInstance().removeFrameCallback(frame)
        resolve.run()
      }
      Choreographer.getInstance().postFrameCallback(frame)
      mainHandler.postDelayed(timeout, RESOLVE_TIMEOUT_MS)
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
      captureFor = ticket
      pending.waiter = ::attach
      debug("prepare waits for the running capture")
      return
    }
    voidCapture("not usable")
    captureFor = ticket
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
    // A start while an earlier start's RenderThread animator is still running on this overlay ends the switch right
    // away. A cancelled RevealAnimator clears its view's reveal clip later, which would break the next animation
    // for a frame.
    if (renderThreadAnimator != null) {
      removeOverlay()
      return
    }
    clearRenderThreadReveal()
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
    // Second start on the same overlay: the first one's animation stops here.
    view.still()
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
        val x = point[0]
        val y = point[1]
        val path = RevealPlan.path(
          transition.type,
          transition.shape != null,
          Build.VERSION.SDK_INT,
          view.snapshotPath,
          animatorsEnabled(),
          fallbacks.off,
        )
        when (path) {
          RevealPath.RENDER_THREAD_REVEAL -> {
            revealOnRenderThread(view, space, x, y, durationMs, onEnd)
            return true
          }
          RevealPath.RENDER_THREAD_INVERSE ->
            if (inverseRevealOnRenderThread(view, x, y, durationMs, onEnd)) return true
          RevealPath.UI_THREAD -> Unit
        }
        view.reveal(shapeOf(transition.shape), x, y, transition.type == "inverseReveal", durationMs, onEnd)
      }
      "wipe" -> view.wipe(wipeDirectionOf(transition.direction) ?: return false, durationMs, onEnd)
      "crossfade" -> view.crossfade(durationMs, onEnd)
      else -> return false
    }
    return true
  }

  // Circle reveal on the RenderThread. The overlay stays still while the next frame draws React Native's mount of the
  // new theme. In the frame after that frame's commit, a second GPU snapshot of the same window(s), minus the overlay,
  // goes into a second overlay view above the first, and a circular reveal clip grows on it. If that fails, the
  // UI-thread reveal runs instead (SecondSnapshotWait).
  private fun revealOnRenderThread(
    view: RevealOverlayView,
    space: OverlayGeometry,
    x: Float,
    y: Float,
    durationMs: Long,
    onEnd: () -> Unit,
  ) {
    val run = SecondSnapshot(view, space, x, y, durationMs, onEnd)
    secondSnapshot = run
    run.begin()
  }

  // Inverse circle reveal on the RenderThread: a circular reveal clip on the overlay itself, from the cover radius
  // down to 0. No second snapshot. False if it can't start, and then the caller runs the UI-thread one.
  private fun inverseRevealOnRenderThread(
    view: RevealOverlayView,
    x: Float,
    y: Float,
    durationMs: Long,
    onEnd: () -> Unit,
  ): Boolean {
    val radii = RevealPlan.circleRadii(x, y, view.snapshotWidth, view.snapshotHeight, view.startHeightPx)
    val r0 = radii[0]
    val r1 = radii[1]
    if (!RevealPlan.radiiUsable(r0, r1)) return false
    try {
      startCircularReveal(view, x, y, r1, 0f, ShrinkInterpolator(r0, r1), durationMs, onEnd)
    } catch (_: RuntimeException) {
      clearRenderThreadReveal()
      return false
    }
    debug("inverse reveal runs on the RenderThread")
    return true
  }

  private fun startCircularReveal(
    view: View,
    x: Float,
    y: Float,
    from: Float,
    to: Float,
    curve: TimeInterpolator,
    durationMs: Long,
    onEnd: () -> Unit,
  ) {
    val animator = ViewAnimationUtils.createCircularReveal(view, x.roundToInt(), y.roundToInt(), from, to)
    animator.duration = durationMs
    animator.interpolator = curve
    animator.addListener(object : AnimatorListenerAdapter() {
      override fun onAnimationEnd(animation: Animator) = onEnd()
    })
    renderThreadAnimator = animator
    animator.start()
    // A RenderThread animator doesn't advance while the window isn't drawing, so the overlay is removed then at the
    // latest.
    mainHandler.removeCallbacks(failsafe)
    mainHandler.postDelayed(failsafe, durationMs + FAILSAFE_MS)
  }

  // Shows the second snapshot above the overlay and grows a circular reveal clip on it, on the RenderThread. Null if it
  // runs, otherwise the reason it doesn't. Frees the snapshot if it doesn't use it.
  private fun revealSecond(
    view: RevealOverlayView,
    space: OverlayGeometry,
    x: Float,
    y: Float,
    durationMs: Long,
    onEnd: () -> Unit,
    snapshot: Snapshot,
  ): String? {
    val bitmap = snapshot.bitmap
    val matches = RevealPlan.secondMatches(
      overlay === view,
      view.isAttachedToWindow,
      snapshot.host === space.host,
      WindowSnapshot.pathOf(bitmap) == CapturePath.GPU,
      bitmap.width,
      bitmap.height,
      view.snapshotWidth,
      view.snapshotHeight,
      snapshot.hostX == view.left && snapshot.hostY == view.top,
    )
    if (!matches) {
      WindowSnapshot.kept.give(bitmap)
      return "the second snapshot doesn't match the first"
    }
    val radii = RevealPlan.circleRadii(x, y, view.snapshotWidth, view.snapshotHeight, view.startHeightPx)
    val r0 = radii[0]
    val r1 = radii[1]
    if (!RevealPlan.radiiUsable(r0, r1)) {
      WindowSnapshot.kept.give(bitmap)
      return "radii $r0 to $r1"
    }
    val next = RevealOverlayView(view.context, bitmap)
    // Second view owns the bitmap from here on: clearRenderThreadReveal frees it.
    secondView = next
    try {
      space.host.overlay.add(next)
      next.layout(view.left, view.top, view.right, view.bottom)
      startCircularReveal(next, x, y, r0, r1, GrowthInterpolator(r0, r1), durationMs, onEnd)
    } catch (_: RuntimeException) {
      clearRenderThreadReveal()
      return "cannot start the circular reveal"
    }
    if (Log.isLoggable(TAG, Log.DEBUG)) probe(view, next, x, y)
    return null
  }

  // Debug only, slow: reads one pixel of both snapshots away from the press, to show the second one has the new theme.
  private fun probe(old: RevealOverlayView, next: RevealOverlayView, x: Float, y: Float) {
    val w = old.snapshotWidth
    val h = old.snapshotHeight
    val px = (if (x < w / 2f) w * 0.9f else w * 0.1f).toInt()
    val py = (if (y < h / 2f) h * 0.9f else h * 0.1f).toInt()
    val before = old.pixelAt(px, py)
    val after = next.pixelAt(px, py)
    val hex = { color: Int? -> color?.let { "#%08x".format(it) } ?: "none" }
    Log.d(TAG, "second snapshot probe at ($px, $py): old ${hex(before)}, new ${hex(after)}, differs ${before != after}")
  }

  // Stops the RenderThread reveal: the wait for the second snapshot (its callbacks and prepared target), the animator,
  // and the second view with its bitmap.
  private fun clearRenderThreadReveal() {
    secondSnapshot?.wait?.cancel()
    secondSnapshot = null
    renderThreadAnimator?.let {
      it.removeAllListeners()
      it.cancel()
    }
    renderThreadAnimator = null
    val next = secondView ?: return
    secondView = null
    (next.parent as? ViewGroup)?.removeView(next)
    next.release(WindowSnapshot.kept::give)
  }

  // One RenderThread reveal's wait for its second snapshot (SecondSnapshotWait): the frame callbacks, the commit
  // callback, the timeout and the target prepared for the capture. Main thread only.
  private inner class SecondSnapshot(
    private val view: RevealOverlayView,
    private val space: OverlayGeometry,
    private val x: Float,
    private val y: Float,
    private val durationMs: Long,
    private val onEnd: () -> Unit,
  ) : SecondSnapshotSteps<Snapshot> {
    val wait = SecondSnapshotWait(this)
    private val startedAt = SystemClock.uptimeMillis()

    // Capture's target, from when it's ready until the capture takes it.
    private var output: GpuOutput? = null
    private val frame = Choreographer.FrameCallback {
      val ok = windowOk()
      // That way this frame's traversal always draws and its commit arrives. Without it, a theme mount that a frame
      // drew before start() leaves this frame nothing to draw, and the wait runs into its timeout.
      if (ok) view.invalidate()
      wait.frame(ok)
    }

    // Nothing unregisters a frame-commit callback. release() empties the relay so it doesn't keep this wait alive.
    private val commit = CommitRelay {
      if (wait.state == WaitState.WAITING_FOR_COMMIT) debug("frame commit after ${sinceStart()} ms")
      wait.commit(windowOk())
    }

    // React Native's frame callbacks for this frame (mount dispatch, timers, the JS rAF tick) were posted before this
    // one, so they run first.
    private val captureFrame = Choreographer.FrameCallback { wait.captureFrame(windowOk()) }
    private val timeout = Runnable { wait.timeout() }

    // Starts the wait. The capture's target is made ready now, while JS renders the theme.
    fun begin() {
      WindowSnapshot.prepareOutput(view.snapshotWidth, view.snapshotHeight, mainHandler) { prepared, ms ->
        val used = wait.beforeCapture
        if (Log.isLoggable(TAG, Log.DEBUG)) {
          Log.d(
            TAG,
            "prepared the second snapshot's target after %d ms, %.1f ms on the copy thread: %b, used %b".format(
              sinceStart(),
              ms,
              prepared != null,
              used && prepared != null,
            ),
          )
        }
        if (used) output = prepared else WindowSnapshot.releaseOutput(prepared)
      }
      Choreographer.getInstance().postFrameCallback(frame)
      mainHandler.postDelayed(timeout, SecondSnapshotWait.TIMEOUT_MS)
    }

    private fun sinceStart() = SystemClock.uptimeMillis() - startedAt

    private fun windowOk(): Boolean {
      val activity = space.activity
      return overlay === view && !activity.isFinishing && !activity.isDestroyed && space.host.isAttachedToWindow &&
        view.isAttachedToWindow
    }

    override fun registerCommit(): Boolean {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
      return try {
        // Frame callback invalidated the overlay, so this frame's traversal draws and its commit arrives.
        space.host.viewTreeObserver.registerFrameCommitCallback(commit)
        true
      } catch (_: IllegalStateException) {
        // A dead ViewTreeObserver.
        false
      }
    }

    override fun postCaptureFrame() = Choreographer.getInstance().postFrameCallback(captureFrame)

    override fun capture() {
      val prepared = output
      output = null
      val start = System.nanoTime()
      // Window without the overlay: the capture hides it only while recording the window, on this thread.
      WindowSnapshot.captureSecond(space.activity, space.host, mainHandler, view, prepared) { snapshot ->
        debug("second snapshot result after ${sinceStart()} ms: ${snapshot != null}")
        wait.result(snapshot)
      }
      if (Log.isLoggable(TAG, Log.DEBUG)) {
        Log.d(
          TAG,
          "second snapshot frame after %d ms, capture %.1f ms on main, prepared target %b".format(
            sinceStart(),
            (System.nanoTime() - start) / 1e6,
            prepared != null,
          ),
        )
      }
    }

    override fun reveal(bitmap: Snapshot): String? {
      val failure = revealSecond(view, space, x, y, durationMs, onEnd, bitmap)
      if (failure == null) fallbacks.revealed()
      return failure
    }

    override fun fallBack(reason: String) {
      debug("RenderThread reveal falls back to the UI-thread reveal: $reason")
      if (overlay !== view) return
      // A modal that closed during the wait took the overlay out of the window, so it goes now, same as in start().
      if (!view.isAttachedToWindow || !space.host.isAttachedToWindow) {
        removeOverlay()
        return
      }
      if (RevealPlan.countsFallBack(windowOk(), isResumed(space.activity))) {
        fallbacks.fellBack()
        if (fallbacks.off) debug("the RenderThread reveal is off for this process: too many fall backs in a row")
      }
      view.reveal(ShapePath.circle(), x, y, false, durationMs, onEnd)
    }

    override fun release() {
      if (secondSnapshot === this) secondSnapshot = null
      commit.target = null
      Choreographer.getInstance().removeFrameCallback(frame)
      Choreographer.getInstance().removeFrameCallback(captureFrame)
      mainHandler.removeCallbacks(timeout)
      WindowSnapshot.releaseOutput(output)
      output = null
    }

    override fun free(bitmap: Snapshot) = WindowSnapshot.kept.give(bitmap.bitmap)
  }

  private fun removeOverlay() {
    mainHandler.removeCallbacks(failsafe)
    clearRenderThreadReveal()
    started?.resolve()
    started = null
    val view = overlay ?: return
    overlay = null
    geometry = null
    (view.parent as? ViewGroup)?.removeView(view)
    view.release(WindowSnapshot.kept::give)
    scheduleKeepTarget()
  }

  // Prepares the capture target for the next switch's first snapshot (keepTargetIdle), KEEP_TARGET_DELAY_MS from now,
  // so the frames right after a switch or mount don't share the RenderThread with it. A later call pushes it back.
  private fun scheduleKeepTarget() {
    if (destroyed) return
    mainHandler.removeCallbacks(keepTargetSoon)
    mainHandler.postDelayed(keepTargetSoon, KEEP_TARGET_DELAY_MS)
  }

  private fun cancelKeepTarget() {
    mainHandler.removeCallbacks(keepTargetSoon)
    Looper.getMainLooper().queue.removeIdleHandler(keepTargetIdle)
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

    // Longest prepare waits for the frame callback before resolving.
    const val RESOLVE_TIMEOUT_MS = 100L

    // How long the kept capture target waits after a switch, capture or mount before its prepare.
    const val KEEP_TARGET_DELAY_MS = 500L

    // The RenderThread reveal's fallbacks in a row, process-wide (RevealPlan.path's secondSnapshotOff).
    val fallbacks = FallbackCounter(RevealPlan.FALLBACKS_TO_OFF)
    const val TAG = "ThemeReveal"
  }
}
