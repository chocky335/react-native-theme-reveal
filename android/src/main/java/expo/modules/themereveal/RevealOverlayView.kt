package expo.modules.themereveal

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator

internal enum class WipeDirection { UP, DOWN, LEFT, RIGHT }

@SuppressLint("ViewConstructor")
internal class RevealOverlayView(context: Context, private val snapshot: Bitmap) : View(context) {
  private enum class Mode { STILL, REVEAL, INVERSE_REVEAL, WIPE, CROSSFADE }

  private val hole = Path()
  private val holeMatrix = Matrix()
  private val wipeClip = RectF()
  private var mode = Mode.STILL
  private var shape: ShapePath? = null
  private var originX = 0f
  private var originY = 0f
  private var fromScale = 0.0
  private var toScale = 0.0
  private var scale = 0f
  private var wipeDirection = WipeDirection.UP
  private var animator: ValueAnimator? = null

  val snapshotWidth: Int get() = snapshot.width
  val snapshotHeight: Int get() = snapshot.height

  init {
    isClickable = false
    isFocusable = false
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
  }

  @SuppressLint("ClickableViewAccessibility")
  override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

  fun reveal(shape: ShapePath, x: Float, y: Float, inverse: Boolean, durationMs: Long, onEnd: () -> Unit) {
    this.shape = shape
    originX = x
    originY = y
    fromScale = (START_HEIGHT_DP * resources.displayMetrics.density / shape.bounds.height()).toDouble()
    toScale = Motion.coverScale(x, y, snapshot.width, snapshot.height, shape.effectiveInscribedRadius).toDouble()
    play(if (inverse) Mode.INVERSE_REVEAL else Mode.REVEAL, durationMs, onEnd)
  }

  fun wipe(direction: WipeDirection, durationMs: Long, onEnd: () -> Unit) {
    wipeDirection = direction
    wipeClip.set(0f, 0f, snapshot.width.toFloat(), snapshot.height.toFloat())
    play(Mode.WIPE, durationMs, onEnd)
  }

  fun crossfade(durationMs: Long, onEnd: () -> Unit) {
    setLayerType(LAYER_TYPE_HARDWARE, null)
    play(Mode.CROSSFADE, durationMs, onEnd)
  }

  // Stops the animation and hands the snapshot off, either to the kept bitmaps or to be freed.
  fun release(drop: (Bitmap) -> Unit) {
    stopAnimator()
    drop(snapshot)
  }

  private fun stopAnimator() {
    animator?.removeAllUpdateListeners()
    animator?.removeAllListeners()
    animator?.cancel()
    animator = null
  }

  private fun play(next: Mode, durationMs: Long, onEnd: () -> Unit) {
    stopAnimator()
    mode = next
    onFrame(0f)
    animator = ValueAnimator.ofFloat(0f, 1f).apply {
      duration = durationMs
      interpolator = LinearInterpolator()
      addUpdateListener { onFrame(it.animatedFraction) }
      addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) = onEnd()
      })
      start()
    }
  }

  private fun onFrame(fraction: Float) {
    when (mode) {
      Mode.REVEAL -> scale = Motion.growth(fraction.toDouble(), fromScale, toScale).toFloat()
      Mode.INVERSE_REVEAL -> scale = Motion.shrink(fraction.toDouble(), fromScale, toScale).toFloat()
      Mode.WIPE -> moveWipeEdge(Motion.easeInOut(fraction))
      Mode.CROSSFADE -> {
        alpha = 1f - Motion.easeInOut(fraction)
        return
      }
      Mode.STILL -> return
    }
    invalidate()
  }

  private fun moveWipeEdge(progress: Float) {
    val w = snapshot.width.toFloat()
    val h = snapshot.height.toFloat()
    when (wipeDirection) {
      WipeDirection.UP -> wipeClip.set(0f, 0f, w, h * (1f - progress))
      WipeDirection.DOWN -> wipeClip.set(0f, h * progress, w, h)
      WipeDirection.LEFT -> wipeClip.set(0f, 0f, w * (1f - progress), h)
      WipeDirection.RIGHT -> wipeClip.set(w * progress, 0f, w, h)
    }
  }

  override fun onDraw(canvas: Canvas) {
    if (snapshot.isRecycled) return
    // A software canvas (a screenshot drawn with View.draw, say) throws on a hardware bitmap, so leave it out.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && snapshot.config == Bitmap.Config.HARDWARE &&
      !canvas.isHardwareAccelerated
    ) {
      return
    }
    when (mode) {
      Mode.REVEAL -> if (scale > 0f) clipOut(canvas, shapeAtScale())
      Mode.INVERSE_REVEAL -> canvas.clipPath(shapeAtScale())
      Mode.WIPE -> canvas.clipRect(wipeClip)
      Mode.CROSSFADE, Mode.STILL -> Unit
    }
    canvas.drawBitmap(snapshot, 0f, 0f, null)
  }

  private fun shapeAtScale(): Path {
    val current = shape ?: return hole.apply { reset() }
    holeMatrix.setTranslate(-current.anchorX, -current.anchorY)
    holeMatrix.postScale(scale, scale)
    holeMatrix.postTranslate(originX, originY)
    current.path.transform(holeMatrix, hole)
    return hole
  }

  private fun clipOut(canvas: Canvas, path: Path) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      canvas.clipOutPath(path)
    } else {
      @Suppress("DEPRECATION")
      canvas.clipPath(path, Region.Op.DIFFERENCE)
    }
  }

  private companion object {
    const val START_HEIGHT_DP = 24f
  }
}
