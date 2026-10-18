package expo.modules.themereveal

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

internal class PointRecord : Record {
  @Field val x: Double = 0.0

  @Field val y: Double = 0.0
}

internal class ShapeRecord : Record {
  @Field val path: String = ""

  @Field val anchor: PointRecord = PointRecord()

  @Field val inscribedRadius: Double = 0.0
}

internal class TransitionRecord : Record {
  @Field val type: String = ""

  @Field val durationMs: Double = 0.0

  @Field val origin: PointRecord? = null

  @Field val shape: ShapeRecord? = null

  @Field val direction: String? = null
}

// Overlay has to stay the size of cover. The React root the origin comes from sits at (rootX, rootY) on screen, the
// overlay at (screenX, screenY).
private class OverlayGeometry(val cover: View, val rootX: Int, val rootY: Int, val screenX: Int, val screenY: Int)

class ThemeRevealModule : Module() {
  private val mainHandler = Handler(Looper.getMainLooper())
  private var overlay: RevealOverlayView? = null
  private var geometry: OverlayGeometry? = null
  private var started: Promise? = null
  private var generation = 0
  private val failsafe = Runnable { removeOverlay() }

  override fun definition() = ModuleDefinition {
    Name("ThemeReveal")

    AsyncFunction("prepare") { viewTag: Int?, promise: Promise ->
      prepare(viewTag, promise)
    }.runOnQueue(Queues.MAIN)

    AsyncFunction("start") { transition: TransitionRecord, promise: Promise ->
      start(transition, promise)
    }.runOnQueue(Queues.MAIN)

    AsyncFunction("cancel") {
      generation++
      removeOverlay()
    }.runOnQueue(Queues.MAIN)

    OnActivityDestroys {
      generation++
      removeOverlay()
    }

    OnDestroy {
      mainHandler.post {
        generation++
        removeOverlay()
      }
    }
  }

  private fun prepare(viewTag: Int?, promise: Promise) {
    val ticket = ++generation
    removeOverlay()
    val activity = appContext.currentActivity
    val window = activity?.window
    val decor = window?.decorView as? ViewGroup
    if (activity == null || window == null || decor == null || activity.isFinishing ||
      !Motion.canAnimate(Build.VERSION.SDK_INT, isResumed(activity))
    ) {
      promise.resolve(false)
      return
    }
    val tapped = viewTag?.let { findView(it) }?.takeIf { it.isAttachedToWindow }
    val root = tapped?.rootView ?: WindowSnapshot.focusedRoot(activity)
    val reactRoot = tapped?.let { WindowSnapshot.reactRootOf(it) } ?: root ?: decor
    WindowSnapshot.capture(activity, root, mainHandler) { snapshot ->
      if (snapshot == null) {
        promise.resolve(false)
        return@capture
      }
      val bitmap = snapshot.bitmap
      if (ticket != generation || activity.isFinishing || activity.isDestroyed || !isResumed(activity) ||
        !snapshot.host.isAttachedToWindow
      ) {
        bitmap.recycle()
        promise.resolve(false)
        return@capture
      }
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
      promise.resolve(true)
    }
  }

  private fun start(transition: TransitionRecord, promise: Promise) {
    val view = overlay
    if (view == null) {
      promise.resolve()
      return
    }
    mainHandler.removeCallbacks(failsafe)
    started?.resolve()
    started = promise
    val cover = geometry?.cover
    val durationMs = transition.durationMs.toLong()
    if (view.parent == null || cover == null || durationMs <= 0 || !animatorsEnabled() ||
      !Motion.sameSize(view.snapshotWidth, view.snapshotHeight, cover.width, cover.height)
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

  private fun play(view: RevealOverlayView, transition: TransitionRecord, durationMs: Long, onEnd: () -> Unit): Boolean {
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
    view.release()
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

  private fun shapeOf(record: ShapeRecord?): ShapePath {
    if (record == null) return ShapePath.circle()
    return try {
      ShapePath.parse(
        record.path,
        PointF(record.anchor.x.toFloat(), record.anchor.y.toFloat()),
        record.inscribedRadius.toFloat(),
      )
    } catch (_: RuntimeException) {
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
  }
}
