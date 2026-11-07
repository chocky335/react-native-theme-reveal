package expo.modules.themereveal

import android.animation.TimeInterpolator
import android.os.Build

// Interpolators for ViewAnimationUtils.createCircularReveal that reproduce the UI-thread reveal's radius curves.
// RenderNodeAnimator bakes a non-native interpolator into a lookup table, so the animation still runs on the
// RenderThread.
internal class GrowthInterpolator(private val r0: Float, private val r1: Float) : TimeInterpolator {
  override fun getInterpolation(input: Float): Float =
    Motion.growthFraction(input.toDouble(), r0.toDouble(), r1.toDouble()).toFloat()
}

internal class ShrinkInterpolator(private val r0: Float, private val r1: Float) : TimeInterpolator {
  override fun getInterpolation(input: Float): Float =
    Motion.shrinkFraction(input.toDouble(), r0.toDouble(), r1.toDouble()).toFloat()
}

// Where a reveal's animation runs.
internal enum class RevealPath {
  // A second snapshot of the new screen, revealed by a circular clip that grows on the RenderThread.
  RENDER_THREAD_REVEAL,

  // A circular clip on the old snapshot that shrinks on the RenderThread.
  RENDER_THREAD_INVERSE,

  // RevealOverlayView's ValueAnimator, which re-records the overlay every frame.
  UI_THREAD,
}

internal object RevealPlan {
  // Only the default circle can run on the RenderThread, since createCircularReveal only clips to a circle. Needs
  // the GPU capture (API 29+): the second snapshot of the new screen is a GPU capture too, so both look the same.
  // With animators off, the UI-thread path keeps today's behavior. secondSnapshotOff (FallbackCounter.off): the
  // reveal's second snapshot failed too often in this process. The inverse reveal doesn't take one, so it stays.
  fun path(
    type: String,
    customShape: Boolean,
    apiLevel: Int,
    snapshot: CapturePath,
    animatorsEnabled: Boolean,
    secondSnapshotOff: Boolean,
  ): RevealPath {
    if (customShape || apiLevel < Build.VERSION_CODES.Q || snapshot != CapturePath.GPU || !animatorsEnabled) {
      return RevealPath.UI_THREAD
    }
    return when (type) {
      "reveal" -> if (secondSnapshotOff) RevealPath.UI_THREAD else RevealPath.RENDER_THREAD_REVEAL
      "inverseReveal" -> RevealPath.RENDER_THREAD_INVERSE
      else -> RevealPath.UI_THREAD
    }
  }

  // Default circle's start and end radius in px for a reveal from (x, y) in a width x height overlay, matching what
  // the UI-thread reveal draws: ShapePath.circle() has radius 1 and height 2, so it starts at startHeightPx / 2 and
  // ends at the cover radius.
  fun circleRadii(x: Float, y: Float, width: Int, height: Int, startHeightPx: Float): FloatArray =
    floatArrayOf(startHeightPx / 2f, Motion.coverScale(x, y, width, height, 1f))

  // Second snapshot can go over the first: the overlay is the same view, still in the window, and the snapshot is a
  // GPU capture of the same window at the same size and position.
  fun secondMatches(
    sameOverlay: Boolean,
    attached: Boolean,
    sameHost: Boolean,
    gpu: Boolean,
    width: Int,
    height: Int,
    firstWidth: Int,
    firstHeight: Int,
    sameOffset: Boolean,
  ): Boolean =
    sameOverlay && attached && sameHost && gpu && Motion.sameSize(width, height, firstWidth, firstHeight) && sameOffset

  // Circle has to grow from a positive, finite radius.
  fun radiiUsable(r0: Float, r1: Float): Boolean = r0 > 0f && r1 > r0 && r1.isFinite()

  // A fallback only counts toward FallbackCounter if it tells us something about the second snapshot, i.e. the
  // overlay is still in an open window of a resumed activity. In the background no frame comes, so the wait times
  // out anyway.
  fun countsFallBack(windowOk: Boolean, resumed: Boolean): Boolean = windowOk && resumed

  // Fallbacks in a row that turn off the reveal's second snapshot (FallbackCounter).
  const val FALLBACKS_TO_OFF = 3

  // A held GPU snapshot gets its texture imported during the hold so the overlay's first frame doesn't have to.
  fun preImports(apiLevel: Int, snapshot: CapturePath): Boolean =
    apiLevel >= Build.VERSION_CODES.Q && snapshot == CapturePath.GPU
}

// Counts consecutive RenderThread reveals that fell back to the UI-thread reveal. After limit of them, off stays
// true for the rest of the process: each such fallback cost the switch up to SecondSnapshotWait.TIMEOUT_MS. Main
// thread only.
internal class FallbackCounter(private val limit: Int) {
  var off = false
    private set
  private var inARow = 0

  fun fellBack() {
    inARow++
    if (inARow >= limit) off = true
  }

  fun revealed() {
    inARow = 0
  }
}
