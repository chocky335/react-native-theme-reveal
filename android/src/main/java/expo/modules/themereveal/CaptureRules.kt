package expo.modules.themereveal

internal object CaptureRules {
  // A press-in capture no switch uses is freed after this long, so a long press doesn't hold on to a full-window
  // bitmap.
  const val TIMEOUT_MS = 3_000L

  // A capture's PixelCopy result is kept only if nothing voided the capture (ticket), no prepare started during the
  // copy (generation) and no overlay is showing. Otherwise it could hold a frame from before another switch.
  fun keepResult(
    ticket: Int,
    currentTicket: Int,
    generation: Int,
    currentGeneration: Int,
    overlayShown: Boolean,
  ): Boolean = ticket == currentTicket && generation == currentGeneration && !overlayShown

  // A held capture can stand in for prepare's own capture if nothing voided it (ticket, a guard: voiding also frees
  // it), no other prepare ran since it started (generation is the one before this prepare's), it shows the window
  // prepare would capture, that window still has the snapshot's size, and it's no older than TIMEOUT_MS.
  fun usable(
    ticket: Int,
    currentTicket: Int,
    generation: Int,
    generationBefore: Int,
    sameWindow: Boolean,
    snapshotW: Int,
    snapshotH: Int,
    coverW: Int,
    coverH: Int,
    takenAtMs: Long,
    nowMs: Long,
  ): Boolean {
    val age = nowMs - takenAtMs
    return ticket == currentTicket && generation == generationBefore && sameWindow &&
      Motion.sameSize(snapshotW, snapshotH, coverW, coverH) && age >= 0 && age < TIMEOUT_MS
  }
}
