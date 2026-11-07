package expo.modules.themereveal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackCounterTest {
  @Test
  fun startsOn() {
    assertFalse(FallbackCounter(3).off)
  }

  @Test
  fun turnsOffAfterThreeFallbacksInARow() {
    val counter = FallbackCounter(3)
    counter.fellBack()
    counter.fellBack()
    assertFalse(counter.off)
    counter.fellBack()
    assertTrue(counter.off)
  }

  @Test
  fun aRevealStartsTheCountAgain() {
    val counter = FallbackCounter(3)
    counter.fellBack()
    counter.fellBack()
    counter.revealed()
    counter.fellBack()
    counter.fellBack()
    assertFalse(counter.off)
    counter.fellBack()
    assertTrue(counter.off)
  }

  @Test
  fun staysOffForTheProcess() {
    val counter = FallbackCounter(3)
    repeat(3) { counter.fellBack() }
    counter.revealed()
    assertTrue(counter.off)
  }
}
