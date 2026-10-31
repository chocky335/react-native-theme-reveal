package expo.modules.themereveal

internal object CaptureRules {
  // A press-in capture no switch uses is freed after this long, so a long press doesn't hold on to a full-window
  // bitmap.
  const val TIMEOUT_MS = 3_000L

  // A held capture can stand in for prepare's own capture if nothing voided it (ticket), it shows the window prepare
  // would capture, that window still has the snapshot's size, and it's no older than TIMEOUT_MS.
  fun usable(
    ticket: Int,
    currentTicket: Int,
    sameWindow: Boolean,
    snapshotW: Int,
    snapshotH: Int,
    coverW: Int,
    coverH: Int,
    takenAtMs: Long,
    nowMs: Long,
  ): Boolean {
    val age = nowMs - takenAtMs
    return ticket == currentTicket && sameWindow && Motion.sameSize(snapshotW, snapshotH, coverW, coverH) &&
      age >= 0 && age < TIMEOUT_MS
  }
}
