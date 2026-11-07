package expo.modules.themereveal

// Where the RenderThread reveal is in its wait for the second snapshot.
internal enum class WaitState {
  // For the frame after start(), which draws React Native's mount of the new theme.
  WAITING_FOR_FRAME,

  // For that frame's commit.
  WAITING_FOR_COMMIT,

  // For the frame after the commit, behind React Native's own frame callbacks.
  WAITING_FOR_CAPTURE_FRAME,

  // For the capture's result.
  CAPTURING,

  // Revealed, fell back or cancelled.
  DONE,
}

// What the wait does at each step. Main thread only.
internal interface SecondSnapshotSteps<B : Any> {
  // Registers the one-shot frame-commit callback. False if the window won't take it.
  fun registerCommit(): Boolean

  fun postCaptureFrame()

  // Starts the capture. Its result goes to SecondSnapshotWait.result, even from inside this call.
  fun capture()

  // Shows the second snapshot and starts its reveal. Null if it's running, otherwise the reason it isn't (it has
  // then freed the bitmap).
  fun reveal(bitmap: B): String?

  // Runs the UI-thread reveal instead.
  fun fallBack(reason: String)

  // Ends the wait: removes its frame callbacks and timeout, and frees the target prepared for the capture.
  fun release()

  // Frees a bitmap no reveal uses.
  fun free(bitmap: B)
}

// The RenderThread reveal's wait for its second snapshot: frame, commit, capture frame, capture, result. It ends once,
// with the reveal, with one fallback to the UI-thread reveal (no window, no commit callback, no result, a result that
// can't reveal, the timeout), or with cancel (overlay is gone, nothing to fall back on). release runs once, first, at
// that end. A callback that comes late or out of order is a no-op, and a late result is freed. Main thread only.
internal class SecondSnapshotWait<B : Any>(private val steps: SecondSnapshotSteps<B>) {
  var state = WaitState.WAITING_FOR_FRAME
    private set

  // A target prepared for the capture is still usable.
  val beforeCapture: Boolean
    get() = state != WaitState.CAPTURING && state != WaitState.DONE

  fun frame(windowOk: Boolean) {
    if (!moveOn(WaitState.WAITING_FOR_FRAME, WaitState.WAITING_FOR_COMMIT, windowOk)) return
    if (!steps.registerCommit()) end("cannot wait for the frame commit")
  }

  fun commit(windowOk: Boolean) {
    if (moveOn(WaitState.WAITING_FOR_COMMIT, WaitState.WAITING_FOR_CAPTURE_FRAME, windowOk)) steps.postCaptureFrame()
  }

  fun captureFrame(windowOk: Boolean) {
    if (moveOn(WaitState.WAITING_FOR_CAPTURE_FRAME, WaitState.CAPTURING, windowOk)) steps.capture()
  }

  fun result(bitmap: B?) {
    if (state != WaitState.CAPTURING) {
      bitmap?.let(steps::free)
      return
    }
    if (bitmap == null) {
      end("no second snapshot")
      return
    }
    end(null)
    steps.reveal(bitmap)?.let(steps::fallBack)
  }

  fun timeout() {
    if (state != WaitState.DONE) end("no second snapshot within $TIMEOUT_MS ms")
  }

  fun cancel() {
    if (state != WaitState.DONE) end(null)
  }

  // Moves from one state to the next. A callback in any other state is a no-op. Without a window the wait ends.
  private fun moveOn(from: WaitState, to: WaitState, windowOk: Boolean): Boolean {
    if (state != from) return false
    if (!windowOk) {
      end("no window")
      return false
    }
    state = to
    return true
  }

  private fun end(fallBack: String?) {
    state = WaitState.DONE
    steps.release()
    fallBack?.let(steps::fallBack)
  }

  companion object {
    // Longest the wait can take from start(): 3 frames, the commit and the capture.
    const val TIMEOUT_MS = 200L
  }
}
