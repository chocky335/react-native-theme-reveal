package expo.modules.themereveal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptureEndTest {
  private val events = mutableListOf<String>()

  private fun end(onResult: (String?, GpuFailure?) -> Unit = { bitmap, failure -> events += "result $bitmap $failure" }) =
    CaptureEnd<String>(
      release = { events += "release" },
      recycle = { events += "recycle $it" },
      cancelTimeout = { events += "cancel timeout" },
      onResult = onResult,
    )

  @Test
  fun anImageEndsTheCaptureBeforeTheRendererGoes() {
    val end = end()
    end.image("a")
    end.timeout()
    end.fail(GpuFailure.SYNC)
    assertEquals(listOf("cancel timeout", "result a null", "release"), events)
  }

  @Test
  fun anImageAfterTheTimeoutIsRecycled() {
    val end = end()
    end.timeout()
    end.image("a")
    assertEquals(listOf("release", "result null TIMEOUT", "recycle a"), events)
  }

  @Test
  fun anImageAfterASyncFailureIsRecycled() {
    val end = end()
    end.fail(GpuFailure.SYNC)
    end.image("a")
    end.timeout()
    assertEquals(listOf("cancel timeout", "release", "result null SYNC", "recycle a"), events)
  }

  @Test
  fun aMissingImageIsAnError() {
    val end = end()
    end.image(null)
    assertEquals(listOf("cancel timeout", "release", "result null ERROR"), events)
  }

  @Test
  fun theRendererGoesEvenWhenTheResultThrows() {
    val end = end { _, _ -> throw IllegalStateException("attach failed") }
    assertThrows(IllegalStateException::class.java) { end.image("a") }
    end.timeout()
    assertEquals(listOf("cancel timeout", "release"), events)
  }
}
