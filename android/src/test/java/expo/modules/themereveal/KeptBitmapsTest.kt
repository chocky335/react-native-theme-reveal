package expo.modules.themereveal

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KeptBitmapsTest {
  private class Fake(val width: Int, val height: Int) {
    var freed = false
  }

  private fun pool(capacity: Int = 1) = KeptBitmaps<Fake>(capacity, { it.width }, { it.height }, { it.freed }) {
    it.freed = true
  }

  @Test
  fun keepsNothingUntilWarmUpAsksForIt() {
    val pool = pool()
    val bitmap = Fake(1080, 2400)

    assertNull(pool.take(1080, 2400))
    pool.give(bitmap)

    assertTrue(bitmap.freed)
    assertEquals(0, pool.size)
  }

  @Test
  fun givesBackTheKeptBitmapForTheSameSize() {
    val pool = pool().apply { keep = true }
    val bitmap = Fake(1080, 2400)
    assertNull(pool.take(1080, 2400))

    pool.give(bitmap)

    assertFalse(bitmap.freed)
    assertSame(bitmap, pool.take(1080, 2400))
    assertEquals(0, pool.size)
  }

  @Test
  fun neverHandsOutABitmapTwice() {
    val pool = pool().apply { keep = true }
    pool.fill(Fake(1080, 2400))

    val shown = pool.take(1080, 2400)

    assertTrue(shown != null)
    assertNull(pool.take(1080, 2400))
  }

  @Test
  fun freesAKeptBitmapOfAnotherSize() {
    val pool = pool().apply { keep = true }
    val old = Fake(1080, 2400)
    pool.fill(old)

    assertNull(pool.take(2400, 1080))

    assertTrue(old.freed)
    assertEquals(0, pool.size)
  }

  @Test
  fun keepsOnlyBitmapsOfTheLastRequestedSize() {
    val pool = pool().apply { keep = true }
    pool.take(1080, 2400)
    val dialog = Fake(900, 1200)

    pool.give(dialog)

    assertTrue(dialog.freed)
    assertEquals(0, pool.size)
  }

  @Test
  fun freesWhatComesBackWhenFull() {
    val pool = pool().apply { keep = true }
    val kept = Fake(1080, 2400)
    val extra = Fake(1080, 2400)
    pool.fill(kept)

    pool.give(extra)

    assertFalse(kept.freed)
    assertTrue(extra.freed)
    assertEquals(1, pool.size)
  }

  @Test
  fun fillKeepsOnlyWhenEmptyAndOn() {
    val pool = pool()
    val off = Fake(1080, 2400)
    assertFalse(pool.fill(off))
    assertTrue(off.freed)

    pool.keep = true
    val first = Fake(1080, 2400)
    val second = Fake(1080, 2400)
    assertTrue(pool.fill(first))
    assertFalse(pool.fill(second))
    assertTrue(second.freed)
    assertFalse(pool.hasRoom(1080, 2400))
  }

  @Test
  fun hasRoomOnlyWhenOnAndNoBitmapOfThatSizeIsKept() {
    val pool = pool()
    assertFalse(pool.hasRoom(1080, 2400))
    pool.keep = true
    assertTrue(pool.hasRoom(1080, 2400))
    pool.fill(Fake(1080, 2400))
    assertFalse(pool.hasRoom(1080, 2400))
    assertTrue(pool.hasRoom(2400, 1080))
  }

  @Test
  fun ignoresARecycledBitmap() {
    val pool = pool().apply { keep = true }
    val recycled = Fake(1080, 2400).apply { freed = true }

    pool.give(recycled)

    assertEquals(0, pool.size)
  }

  @Test
  fun clearFreesEveryKeptBitmapAndKeepsKeepOn() {
    val pool = pool(capacity = 2).apply { keep = true }
    val a = Fake(1080, 2400)
    val b = Fake(1080, 2400)
    pool.take(1080, 2400)
    pool.give(a)
    pool.give(b)

    pool.clear()

    assertTrue(a.freed)
    assertTrue(b.freed)
    assertEquals(0, pool.size)
    assertTrue(pool.keep)
  }

  // Levels are deprecated, but older Android versions still send them.
  @Suppress("DEPRECATION")
  @Test
  fun trimFreesFromUiHiddenUp() {
    val pool = pool().apply { keep = true }
    val bitmap = Fake(1080, 2400)
    pool.fill(bitmap)

    pool.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
    assertFalse(bitmap.freed)

    pool.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
    assertTrue(bitmap.freed)
    assertEquals(0, pool.size)
  }

  @Test
  fun freesWhatComesBackAfterTheAppIsHidden() {
    val pool = pool().apply { keep = true }
    pool.take(1080, 2400)
    pool.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
    val shown = Fake(1080, 2400)
    val warmUp = Fake(1080, 2400)

    pool.give(shown)
    assertFalse(pool.hasRoom(1080, 2400))
    assertFalse(pool.fill(warmUp))

    assertTrue(shown.freed)
    assertTrue(warmUp.freed)
    assertEquals(0, pool.size)
  }

  @Test
  fun freesWhatComesBackAfterHide() {
    val pool = pool().apply { keep = true }
    val kept = Fake(1080, 2400)
    pool.fill(kept)
    val shown = pool.take(1080, 2400)!!
    val other = Fake(1080, 2400)
    pool.fill(other)

    pool.hide()
    pool.give(shown)

    assertTrue(other.freed)
    assertTrue(shown.freed)
    assertEquals(0, pool.size)
  }

  @Test
  fun keepsAgainAfterTheNextCapture() {
    val pool = pool().apply { keep = true }
    pool.hide()

    assertNull(pool.take(1080, 2400))
    val bitmap = Fake(1080, 2400)
    pool.give(bitmap)

    assertFalse(bitmap.freed)
    assertEquals(1, pool.size)
  }

  @Test
  fun freesOnTheFirstChangeAfterTheSeededSize() {
    val pool = pool().apply { keep = true }
    pool.onScreenSize(411, 914)
    val bitmap = Fake(1080, 2400)
    pool.fill(bitmap)

    pool.onScreenSize(914, 411)

    assertTrue(bitmap.freed)
  }

  @Test
  fun freesWhenTheScreenSizeChanges() {
    val pool = pool().apply { keep = true }
    val first = Fake(1080, 2400)
    pool.onScreenSize(411, 914)
    pool.fill(first)

    pool.onScreenSize(411, 914)
    assertFalse(first.freed)

    pool.onScreenSize(914, 411)
    assertTrue(first.freed)
    assertEquals(0, pool.size)
  }

  // Levels are deprecated, but older Android versions still send them.
  @Suppress("DEPRECATION")
  @Test
  fun trimFreesOnBackgroundLevels() {
    for (level in listOf(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, ComponentCallbacks2.TRIM_MEMORY_COMPLETE)) {
      val pool = pool().apply { keep = true }
      val bitmap = Fake(1080, 2400)
      pool.fill(bitmap)

      pool.onTrimMemory(level)

      assertTrue("level $level", bitmap.freed)
    }
  }
}
