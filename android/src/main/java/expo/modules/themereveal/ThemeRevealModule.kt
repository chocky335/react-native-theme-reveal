package expo.modules.themereveal

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
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

class ThemeRevealModule : Module() {
  private val mainHandler = Handler(Looper.getMainLooper())
  private var overlay: RevealOverlayView? = null
  private var started: Promise? = null
  private var generation = 0
  private val failsafe = Runnable { removeOverlay() }

  override fun definition() = ModuleDefinition {
    Name("ThemeReveal")

    AsyncFunction("prepare") { _: Int?, promise: Promise ->
      prepare(promise)
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
  }

  private fun prepare(promise: Promise) {
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
    WindowSnapshot.capture(window, decor.width, decor.height, mainHandler) { bitmap ->
      if (bitmap == null) {
        promise.resolve(false)
        return@capture
      }
      if (ticket != generation || activity.isFinishing || activity.isDestroyed) {
        bitmap.recycle()
        promise.resolve(false)
        return@capture
      }
      val view = RevealOverlayView(activity, bitmap)
      decor.addView(view, ViewGroup.LayoutParams(bitmap.width, bitmap.height))
      overlay = view
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
    val decor = view.parent as? View
    val durationMs = transition.durationMs.toLong()
    if (decor == null || durationMs <= 0 || !animatorsEnabled() ||
      !Motion.sameSize(view.snapshotWidth, view.snapshotHeight, decor.width, decor.height)
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
        val density = view.resources.displayMetrics.density
        val x = (origin.x * density).toFloat()
        val y = (origin.y * density).toFloat()
        view.reveal(shapeOf(transition.shape), x, y, transition.type == "inverseReveal", durationMs, onEnd)
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
    (view.parent as? ViewGroup)?.removeView(view)
    view.release()
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
