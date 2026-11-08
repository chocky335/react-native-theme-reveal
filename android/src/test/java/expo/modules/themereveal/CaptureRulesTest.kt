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
    generation: Int = 9,
    generationBefore: Int = 9,
  ) = CaptureRules.usable(
    ticket,
    currentTicket,
    generation,
    generationBefore,
    sameWindow,
    snapshotW,
    snapshotH,
    coverW,
    coverH,
    takenAtMs,
    nowMs,
  )

  private fun keep(
    ticket: Int = 4,
    currentTicket: Int = 4,
    generation: Int = 9,
    currentGeneration: Int = 9,
    overlayShown: Boolean = false,
  ) = CaptureRules.keepResult(ticket, currentTicket, generation, currentGeneration, overlayShown)

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
  fun aPrepareAfterTheCaptureStartedVoidsIt() {
    assertFalse(usable(generation = 9, generationBefore = 10))
  }

  @Test
  fun aCaptureResultWithNothingInBetweenIsKept() {
    assertTrue(keep())
  }

  @Test
  fun aVoidedCaptureResultIsDropped() {
    assertFalse(keep(currentTicket = 5))
  }

  @Test
  fun aCaptureResultIsDroppedWhenAPrepareStartedDuringTheCopy() {
    assertFalse(keep(generation = 9, currentGeneration = 10))
  }

  @Test
  fun aCaptureResultIsDroppedWhenAnOverlayShows() {
    assertFalse(keep(overlayShown = true))
  }

  @Test
  fun theTimeoutIsThreeSeconds() {
    assertTrue(CaptureRules.TIMEOUT_MS == 3_000L)
  }

  private fun keepsTarget(
    overlayShown: Boolean = false,
    captureRunning: Boolean = false,
    captureHeld: Boolean = false,
    switchCapturing: Boolean = false,
    resumed: Boolean = true,
  ) = CaptureRules.keepsTargetNow(overlayShown, captureRunning, captureHeld, switchCapturing, resumed)

  @Test
  fun aQuietResumedAppPreparesTheNextTarget() {
    assertTrue(keepsTarget())
  }

  @Test
  fun noTargetIsPreparedDuringASwitch() {
    // The reveal runs.
    assertFalse(keepsTarget(overlayShown = true))
    // A switch's capture is running, and the prepare would hold its image back on the copy thread.
    assertFalse(keepsTarget(switchCapturing = true))
  }

  @Test
  fun noTargetIsPreparedDuringAPressIn() {
    // The press-in capture is running, or held for a switch about to start.
    assertFalse(keepsTarget(captureRunning = true))
    assertFalse(keepsTarget(captureHeld = true))
  }

  @Test
  fun noTargetIsPreparedInTheBackground() {
    assertFalse(keepsTarget(resumed = false))
  }
}
