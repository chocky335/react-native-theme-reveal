package expo.modules.themereveal

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeptTargetTest {
  private class Fake(val name: String) {
    var freed = false
  }

  private val freed = mutableListOf<String>()

  // Targets handed to a later free (off the capture's path), in order.
  private val later = mutableListOf<Fake>()
  private val kept = KeptTarget<Fake>(
    free = {
      it.freed = true
      freed += it.name
    },
    freeLater = { later += it },
  )

  // A warm-up turned keeping on, and a 1080x2400 target is ready.
  private fun keepOne(name: String = "a"): Fake {
    kept.keep = true
    val target = Fake(name)
    kept.ready(kept.started(), target, 1080, 2400)
    return target
  }

  @Test
  fun wantsNothingUntilAWarmUpTurnsKeepingOn() {
    assertFalse(kept.wants(1080, 2400, gpuOn = true))
    kept.keep = true
    assertTrue(kept.wants(1080, 2400, gpuOn = true))
  }

  @Test
  fun wantsNothingWithoutTheGpuPathOrASize() {
    kept.keep = true
    assertFalse(kept.wants(1080, 2400, gpuOn = false))
    assertFalse(kept.wants(0, 2400, gpuOn = true))
    assertFalse(kept.wants(1080, 0, gpuOn = true))
  }

  @Test
  fun theNextCaptureOfTheSameSizeTakesTheTargetOnce() {
    val target = keepOne()
    assertSame(target, kept.take(1080, 2400))
    assertNull(kept.take(1080, 2400))
    assertFalse(target.freed)
  }

  @Test
  fun aCaptureOfAnotherSizeFreesTheTargetLater() {
    val target = keepOne()
    assertNull(kept.take(2400, 1080))
    // Off the capture's path: a later free gets it.
    assertFalse(target.freed)
    assertEquals(listOf(target), later)
    assertEquals(0, kept.size)
  }

  @Test
  fun aTargetThatNoCaptureCanUseIsDropped() {
    val target = keepOne()
    kept.drop(1080, 2400, gpuOn = true)
    assertFalse(target.freed)
    kept.drop(1080, 2300, gpuOn = true)
    assertTrue(target.freed)
    assertEquals(0, kept.size)
    val wide = keepOne("wide")
    // The window moved to a path without the GPU capture (wide color, say).
    kept.drop(1080, 2400, gpuOn = false)
    assertTrue(wide.freed)
  }

  @Test
  fun aClearDuringAPrepareStartsNoSecondUntilItEnds() {
    kept.keep = true
    val stale = kept.started()
    kept.clear()
    // The first prepare's buffer still exists on the copy thread.
    assertFalse(kept.wants(1080, 2400, gpuOn = true))
    kept.ready(stale, Fake("stale"), 1080, 2400)
    assertTrue(kept.wants(1080, 2400, gpuOn = true))
  }

  @Test
  fun aStaleResultWhileANewerPrepareRunsStartsNoThird() {
    kept.keep = true
    val stale = kept.started()
    kept.clear()
    kept.ready(stale, Fake("stale"), 1080, 2400)
    val newer = kept.started()
    kept.clear()
    val newest = kept.started()
    kept.ready(newer, Fake("newer"), 1080, 2400)
    // Newest prepare is still running.
    assertFalse(kept.wants(1080, 2400, gpuOn = true))
    val target = Fake("newest")
    kept.ready(newest, target, 1080, 2400)
    assertSame(target, kept.take(1080, 2400))
  }

  @Test
  fun aResultThatComesWhileHiddenIsFreed() {
    kept.keep = true
    kept.hide()
    // Ticket is current, so only the hidden state frees it.
    val target = Fake("a")
    kept.ready(kept.started(), target, 1080, 2400)
    assertTrue(target.freed)
  }

  @Test
  fun aResultThatComesAfterKeepingTurnedOffIsFreed() {
    kept.keep = true
    val ticket = kept.started()
    kept.keep = false
    val target = Fake("a")
    kept.ready(ticket, target, 1080, 2400)
    assertTrue(target.freed)
  }

  @Test
  fun neverKeepsTwo() {
    kept.keep = true
    val first = kept.started()
    // A prepare is running, so no second one starts.
    assertFalse(kept.wants(1080, 2400, gpuOn = true))
    kept.ready(first, Fake("a"), 1080, 2400)
    // One is kept, so no second one starts.
    assertFalse(kept.wants(1080, 2400, gpuOn = true))
    val extra = Fake("b")
    kept.ready(kept.started(), extra, 1080, 2400)
    assertTrue(extra.freed)
    assertEquals(1, kept.size)
  }

  @Test
  fun aFailedPrepareLetsTheNextOneStart() {
    kept.keep = true
    kept.ready(kept.started(), null, 1080, 2400)
    assertTrue(kept.wants(1080, 2400, gpuOn = true))
    assertEquals(0, kept.size)
  }

  @Test
  fun aPrepareThatEndsAfterAClearIsFreed() {
    kept.keep = true
    val ticket = kept.started()
    kept.clear()
    val late = Fake("late")
    kept.ready(ticket, late, 1080, 2400)
    assertTrue(late.freed)
    assertEquals(0, kept.size)
  }

  @Test
  fun aPrepareThatEndsWhileHiddenIsFreed() {
    kept.keep = true
    val ticket = kept.started()
    kept.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
    val late = Fake("late")
    kept.ready(ticket, late, 1080, 2400)
    assertTrue(late.freed)
  }

  @Test
  fun hidingTheAppFreesTheTargetUntilTheNextCapture() {
    val target = keepOne()
    kept.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
    assertTrue(target.freed)
    assertFalse(kept.wants(1080, 2400, gpuOn = true))
    // The next switch's capture shows the app again.
    assertNull(kept.take(1080, 2400))
    assertTrue(kept.wants(1080, 2400, gpuOn = true))
  }

  @Test
  fun aWarmUpShowsTheAppAgain() {
    keepOne()
    kept.hide()
    kept.show()
    assertTrue(kept.wants(1080, 2400, gpuOn = true))
  }

  @Test
  fun aLowerTrimLevelKeepsTheTarget() {
    val target = keepOne()
    kept.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
    assertFalse(target.freed)
    assertEquals(1, kept.size)
  }

  @Test
  fun aScreenSizeChangeFreesTheTarget() {
    kept.onScreenSize(392, 872)
    val target = keepOne()
    kept.onScreenSize(392, 872)
    assertFalse(target.freed)
    kept.onScreenSize(872, 392)
    assertTrue(target.freed)
    assertEquals(0, kept.size)
  }

  @Test
  fun clearFreesTheTargetOnce() {
    keepOne()
    kept.clear()
    kept.clear()
    assertEquals(listOf("a"), freed)
  }

  @Test
  fun preparingHoldsWhileAnyPrepareRuns() {
    kept.keep = true
    assertFalse(kept.preparing)
    val stale = kept.started()
    kept.clear()
    // The stale prepare still holds the copy thread.
    assertTrue(kept.preparing)
    kept.ready(stale, null, 1080, 2400)
    assertFalse(kept.preparing)
  }
}
