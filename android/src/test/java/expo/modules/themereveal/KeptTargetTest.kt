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
  private val kept = KeptTarget<Fake> {
    it.freed = true
    freed += it.name
  }

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
  fun aCaptureOfAnotherSizeFreesTheTarget() {
    val target = keepOne()
    assertNull(kept.take(2400, 1080))
    assertTrue(target.freed)
    assertEquals(0, kept.size)
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
}
