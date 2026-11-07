package expo.modules.themereveal

import android.os.Build
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

internal data class Frame(val left: Int, val top: Int, val right: Int, val bottom: Int) {
  val isEmpty: Boolean get() = right <= left || bottom <= top
}

internal object Motion {
  private const val GROWTH_CURVE = 1.4
  private const val EASE_X1 = 0.65f
  private const val EASE_Y1 = 0f
  private const val EASE_X2 = 0.35f
  private const val EASE_Y2 = 1f
  private const val EASE_STEPS = 24
  private const val MAX_DURATION_MS = 10_000L

  fun growth(g: Double, from: Double, to: Double): Double = from * (to / from).pow(g.pow(GROWTH_CURVE))

  fun shrink(g: Double, from: Double, to: Double): Double = max(0.0, growth(1 - g, from, to) - from * g)

  // For an animator whose radius runs from r0 to r1 as r0 + (r1 - r0) * fraction: the fraction that gives growth's
  // radius at time g. r1 > r0 > 0.
  fun growthFraction(g: Double, r0: Double, r1: Double): Double =
    ((growth(g.coerceIn(0.0, 1.0), r0, r1) - r0) / (r1 - r0)).coerceIn(0.0, 1.0)

  // For an animator whose radius runs from r1 to 0 as r1 * (1 - fraction): the fraction that gives shrink's radius
  // at time g. r1 > r0 > 0.
  fun shrinkFraction(g: Double, r0: Double, r1: Double): Double =
    (1 - shrink(g.coerceIn(0.0, 1.0), r0, r1) / r1).coerceIn(0.0, 1.0)

  fun coverScale(originX: Float, originY: Float, width: Int, height: Int, inscribedRadiusPx: Float): Float =
    hypot(max(originX, width - originX), max(originY, height - originY)) / inscribedRadiusPx

  fun easeInOut(t: Float): Float {
    if (t <= 0f) return 0f
    if (t >= 1f) return 1f
    var lo = 0f
    var hi = 1f
    repeat(EASE_STEPS) {
      val mid = (lo + hi) / 2
      if (bezier(mid, EASE_X1, EASE_X2) < t) lo = mid else hi = mid
    }
    return bezier((lo + hi) / 2, EASE_Y1, EASE_Y2)
  }

  // Non-finite, zero, negative or overlong durations are invalid: the overlay would sit on screen too long.
  fun durationMs(ms: Double): Long? =
    if (!ms.isFinite() || ms > MAX_DURATION_MS) null else ms.toLong().takeIf { it > 0 }

  fun canAnimate(apiLevel: Int, resumed: Boolean): Boolean = apiLevel >= Build.VERSION_CODES.O && resumed

  fun sameSize(snapshotW: Int, snapshotH: Int, w: Int, h: Int): Boolean = snapshotW == w && snapshotH == h

  fun dialogFrame(decorX: Int, decorY: Int, decorW: Int, decorH: Int, screenW: Int, screenH: Int): Frame =
    Frame(max(decorX, 0), max(decorY, 0), min(decorX + decorW, screenW), min(decorY + decorH, screenH))

  fun dimAlpha(dimAmount: Float): Int = if (dimAmount.isNaN()) 0 else (dimAmount.coerceIn(0f, 1f) * 255).roundToInt()

  fun toOverlay(
    xPx: Float,
    yPx: Float,
    rootScreenX: Int,
    rootScreenY: Int,
    overlayScreenX: Int,
    overlayScreenY: Int,
  ): FloatArray =
    floatArrayOf(xPx + (rootScreenX - overlayScreenX), yPx + (rootScreenY - overlayScreenY))

  private fun bezier(s: Float, p1: Float, p2: Float): Float {
    val u = 1 - s
    return 3 * u * u * s * p1 + 3 * u * s * s * p2 + s * s * s
  }
}
