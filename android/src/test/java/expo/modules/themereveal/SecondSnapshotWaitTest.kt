package expo.modules.themereveal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondSnapshotWaitTest {
  private val events = mutableListOf<String>()

  // Records each step in log.
  private class Steps(private val log: MutableList<String>) : SecondSnapshotSteps<String> {
    var canRegister = true
    var revealFailure: String? = null

    // Set by a test whose capture delivers its result inside capture(), like a capture that fails right away.
    var syncResult: (() -> Unit)? = null

    override fun registerCommit(): Boolean {
      log += "register commit"
      return canRegister
    }

    override fun postCaptureFrame() {
      log += "post capture frame"
    }

    override fun capture() {
      log += "capture"
      syncResult?.invoke()
    }

    override fun reveal(bitmap: String): String? {
      log += "reveal $bitmap"
      return revealFailure
    }

    override fun fallBack(reason: String) {
      log += "fall back: $reason"
    }

    override fun release() {
      log += "release"
    }

    override fun free(bitmap: String) {
      log += "free $bitmap"
    }
  }

  private val steps = Steps(events)
  private val wait = SecondSnapshotWait(steps)

  private fun toCapturing() {
    wait.frame(windowOk = true)
    wait.commit(windowOk = true)
    wait.captureFrame(windowOk = true)
    events.clear()
  }

  @Test
  fun startsWaitingForTheFrame() {
    assertEquals(WaitState.WAITING_FOR_FRAME, wait.state)
    assertTrue(events.isEmpty())
  }

  @Test
  fun theFrameRegistersTheCommitCallback() {
    wait.frame(windowOk = true)
    assertEquals(WaitState.WAITING_FOR_COMMIT, wait.state)
    assertEquals(listOf("register commit"), events)
  }

  @Test
  fun theCommitPostsTheCaptureFrame() {
    wait.frame(windowOk = true)
    wait.commit(windowOk = true)
    assertEquals(WaitState.WAITING_FOR_CAPTURE_FRAME, wait.state)
    assertEquals(listOf("register commit", "post capture frame"), events)
  }

  @Test
  fun theCaptureFrameCaptures() {
    wait.frame(windowOk = true)
    wait.commit(windowOk = true)
    wait.captureFrame(windowOk = true)
    assertEquals(WaitState.CAPTURING, wait.state)
    assertEquals(listOf("register commit", "post capture frame", "capture"), events)
  }

  @Test
  fun aResultStartsTheRevealAfterTheRelease() {
    toCapturing()
    wait.result("b")
    assertEquals(WaitState.DONE, wait.state)
    assertEquals(listOf("release", "reveal b"), events)
  }

  @Test
  fun aResultThatCannotRevealFallsBack() {
    steps.revealFailure = "the second snapshot doesn't match the first"
    toCapturing()
    wait.result("b")
    assertEquals(WaitState.DONE, wait.state)
    assertEquals(listOf("release", "reveal b", "fall back: the second snapshot doesn't match the first"), events)
  }

  @Test
  fun noResultFallsBack() {
    toCapturing()
    wait.result(null)
    assertEquals(WaitState.DONE, wait.state)
    assertEquals(listOf("release", "fall back: no second snapshot"), events)
  }

  @Test
  fun aResultInsideTheCaptureCallEndsTheWait() {
    steps.syncResult = { wait.result(null) }
    wait.frame(windowOk = true)
    wait.commit(windowOk = true)
    events.clear()
    wait.captureFrame(windowOk = true)
    assertEquals(WaitState.DONE, wait.state)
    assertEquals(listOf("capture", "release", "fall back: no second snapshot"), events)
  }

  @Test
  fun theTimeoutFallsBackFromEachWaitingState() {
    val reach = listOf<(SecondSnapshotWait<String>) -> Unit>(
      {},
      { it.frame(true) },
      { it.frame(true); it.commit(true) },
      { it.frame(true); it.commit(true); it.captureFrame(true) },
    )
    for ((index, go) in reach.withIndex()) {
      val log = mutableListOf<String>()
      val wait = SecondSnapshotWait(Steps(log))
      go(wait)
      log.clear()
      wait.timeout()
      assertEquals("state $index", WaitState.DONE, wait.state)
      assertEquals("state $index", listOf("release", "fall back: no second snapshot within 200 ms"), log)
    }
  }

  @Test
  fun aLateResultAfterTheTimeoutIsFreed() {
    toCapturing()
    wait.timeout()
    wait.result("b")
    assertEquals(listOf("release", "fall back: no second snapshot within 200 ms", "free b"), events)
  }

  @Test
  fun cancelEndsTheWaitWithoutAFallBack() {
    toCapturing()
    wait.cancel()
    assertEquals(WaitState.DONE, wait.state)
    assertEquals(listOf("release"), events)
  }

  @Test
  fun aLateResultAfterCancelIsFreed() {
    toCapturing()
    wait.cancel()
    wait.result("b")
    wait.result(null)
    assertEquals(listOf("release", "free b"), events)
  }

  @Test
  fun noWindowAtAnyCallbackFallsBack() {
    val noWindow = listOf("release", "fall back: no window")
    val reach = listOf<Pair<(SecondSnapshotWait<String>) -> Unit, List<String>>>(
      { it: SecondSnapshotWait<String> -> it.frame(false) } to noWindow,
      { it: SecondSnapshotWait<String> -> it.frame(true); it.commit(false) } to listOf("register commit") + noWindow,
      { it: SecondSnapshotWait<String> ->
        it.frame(true)
        it.commit(true)
        it.captureFrame(false)
      } to listOf("register commit", "post capture frame") + noWindow,
    )
    for ((index, case) in reach.withIndex()) {
      val log = mutableListOf<String>()
      val wait = SecondSnapshotWait(Steps(log))
      case.first(wait)
      assertEquals("step $index", WaitState.DONE, wait.state)
      assertEquals("step $index", case.second, log)
    }
  }

  @Test
  fun aCommitCallbackThatCannotRegisterFallsBack() {
    steps.canRegister = false
    wait.frame(windowOk = true)
    assertEquals(WaitState.DONE, wait.state)
    assertEquals(listOf("register commit", "release", "fall back: cannot wait for the frame commit"), events)
  }

  @Test
  fun callbacksOutOfOrderOrRepeatedAreIgnored() {
    wait.commit(windowOk = true)
    wait.captureFrame(windowOk = true)
    assertEquals(WaitState.WAITING_FOR_FRAME, wait.state)
    wait.frame(windowOk = true)
    wait.frame(windowOk = true)
    wait.captureFrame(windowOk = true)
    assertEquals(WaitState.WAITING_FOR_COMMIT, wait.state)
    wait.commit(windowOk = true)
    wait.commit(windowOk = true)
    wait.frame(windowOk = true)
    assertEquals(WaitState.WAITING_FOR_CAPTURE_FRAME, wait.state)
    assertEquals(listOf("register commit", "post capture frame"), events)
  }

  @Test
  fun callbacksAfterTheEndDoNothing() {
    toCapturing()
    wait.result("b")
    events.clear()
    wait.frame(windowOk = true)
    wait.commit(windowOk = true)
    wait.captureFrame(windowOk = true)
    wait.timeout()
    wait.cancel()
    assertEquals(WaitState.DONE, wait.state)
    assertTrue(events.isEmpty())
  }

  @Test
  fun aPreparedOutputIsTakenOnlyBeforeTheCapture() {
    assertTrue(wait.beforeCapture)
    wait.frame(windowOk = true)
    assertTrue(wait.beforeCapture)
    wait.commit(windowOk = true)
    assertTrue(wait.beforeCapture)
    wait.captureFrame(windowOk = true)
    assertFalse(wait.beforeCapture)
    wait.result("b")
    assertFalse(wait.beforeCapture)
  }

  @Test
  fun theFallBackRunsOnceAcrossEveryEnd() {
    toCapturing()
    wait.result(null)
    wait.timeout()
    wait.cancel()
    wait.result("c")
    assertEquals(listOf("release", "fall back: no second snapshot", "free c"), events)
  }
}
