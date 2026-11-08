package expo.modules.themereveal

import android.content.ComponentCallbacks2

// GPU capture target kept between switches for the next first snapshot (GpuSnapshot.prepareOutput): at most one, made
// in idle time, so the capture skips creating a renderer, surface and buffer. The capture takes it, and frees it if
// the window has another size. It's freed when the app is hidden or low on memory, the screen changes size, the
// activity or module goes away, or the GPU path turns off. Main thread only. Generic over the target type so JVM
// tests can run it.
internal class KeptTarget<T : Any>(private val free: (T) -> Unit) {
  // Off until a warm-up turns it on (the warmUp option).
  var keep = false

  private var held: T? = null
  private var width = 0
  private var height = 0
  private var preparing = false

  // A prepare that started before a clear ends with a stale ticket, so its target is freed.
  private var ticket = 0

  // App is hidden or the activity is gone, so keep nothing until the next capture or warm-up.
  private var hidden = false
  private var screenWidthDp = 0
  private var screenHeightDp = 0

  val size: Int get() = if (held == null) 0 else 1

  // Whether a prepare of a width x height target needs to start now. gpuOn: the window's next capture takes the GPU
  // path.
  fun wants(width: Int, height: Int, gpuOn: Boolean): Boolean =
    keep && !hidden && gpuOn && held == null && !preparing && width > 0 && height > 0

  // A prepare starts. Its result goes to ready along with the ticket returned here.
  fun started(): Int {
    preparing = true
    return ticket
  }

  // A prepare ended with its target, or null if it failed. Kept only if nothing cleared the target since the prepare
  // started, keeping is on and nothing else is kept. Freed otherwise.
  fun ready(ticket: Int, target: T?, width: Int, height: Int) {
    val current = ticket == this.ticket
    if (current) preparing = false
    if (target == null) return
    if (!current || !keep || hidden || held != null) {
      free(target)
      return
    }
    held = target
    this.width = width
    this.height = height
  }

  // Next capture of width x height: the kept target if it has that size. One of another size is freed.
  fun take(width: Int, height: Int): T? {
    hidden = false
    val target = held ?: return null
    held = null
    if (width == this.width && height == this.height) return target
    free(target)
    return null
  }

  fun onTrimMemory(level: Int) {
    if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) hide()
  }

  // Frees the target and keeps none until the next capture or show.
  fun hide() {
    hidden = true
    clear()
  }

  // A warm-up from a mounted UI: keep a target again.
  fun show() {
    hidden = false
  }

  fun onScreenSize(widthDp: Int, heightDp: Int) {
    val known = screenWidthDp != 0 || screenHeightDp != 0
    if (known && (widthDp != screenWidthDp || heightDp != screenHeightDp)) clear()
    screenWidthDp = widthDp
    screenHeightDp = heightDp
  }

  // Frees the kept target, and the one a running prepare makes.
  fun clear() {
    ticket++
    preparing = false
    val target = held ?: return
    held = null
    free(target)
  }
}
