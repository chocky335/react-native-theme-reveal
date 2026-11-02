package expo.modules.themereveal

import org.junit.Assert.assertEquals
import org.junit.Test

class OnceTest {
  @Test
  fun theFirstTriggerRunsTheAction() {
    var runs = 0
    val once = Once { runs++ }
    once.run()
    assertEquals(1, runs)
  }

  @Test
  fun laterTriggersDoNothing() {
    var runs = 0
    val once = Once { runs++ }
    // The frame callback and the timeout both fire.
    once.run()
    once.run()
    once.run()
    assertEquals(1, runs)
  }

  @Test
  fun anActionThatThrowsDoesNotRunAgain() {
    var runs = 0
    val once = Once {
      runs++
      throw IllegalStateException("resolve failed")
    }
    runCatching { once.run() }
    once.run()
    assertEquals(1, runs)
  }
}
