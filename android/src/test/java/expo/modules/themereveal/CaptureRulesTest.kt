package expo.modules.themereveal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRulesTest {
  private fun usable(
    ticket: Int = 4,
    currentTicket: Int = 4,
    sameWindow: Boolean = true,
    snapshotW: Int = 1080,
    snapshotH: Int = 2400,
    coverW: Int = 1080,
    coverH: Int = 2400,
    takenAtMs: Long = 10_000L,
    nowMs: Long = 10_100L,
  ) = CaptureRules.usable(ticket, currentTicket, sameWindow, snapshotW, snapshotH, coverW, coverH, takenAtMs, nowMs)

  @Test
  fun aFreshCaptureOfTheSameWindowAndSizeIsUsable() {
    assertTrue(usable())
  }

  @Test
  fun aVoidedCaptureIsNotUsable() {
    assertFalse(usable(ticket = 4, currentTicket = 5))
  }

  @Test
  fun aCaptureOfAnotherWindowIsNotUsable() {
    assertFalse(usable(sameWindow = false))
  }

  @Test
  fun aCaptureOfAnotherSizeIsNotUsable() {
    assertFalse(usable(coverW = 2400, coverH = 1080))
    assertFalse(usable(coverH = 2300))
    assertFalse(usable(coverW = 1000))
  }

  @Test
  fun aCaptureExpiresAtTheTimeout() {
    assertTrue(usable(takenAtMs = 0L, nowMs = CaptureRules.TIMEOUT_MS - 1))
    assertFalse(usable(takenAtMs = 0L, nowMs = CaptureRules.TIMEOUT_MS))
  }

  @Test
  fun aCaptureFromTheFutureIsNotUsable() {
    assertFalse(usable(takenAtMs = 10_000L, nowMs = 9_999L))
  }

  @Test
  fun theTimeoutIsThreeSeconds() {
    assertTrue(CaptureRules.TIMEOUT_MS == 3_000L)
  }
}
