package expo.modules.themereveal

import android.content.ComponentCallbacks2

// Window-size bitmaps kept for the next capture so it doesn't have to allocate one. Only bitmaps that no overlay
// shows live here: a capture takes one out, and it comes back when its overlay goes away. Main thread only. Generic
// over the bitmap type so JVM tests can run it.
internal class KeptBitmaps<B : Any>(
  private val capacity: Int,
  private val widthOf: (B) -> Int,
  private val heightOf: (B) -> Int,
  private val isFreed: (B) -> Boolean,
  private val free: (B) -> Unit,
) {
  // Off until a warm-up asks for a kept bitmap. While off, every returned bitmap is freed.
  var keep = false

  private val kept = ArrayList<B>(capacity)
  private var width = 0
  private var height = 0
  private var screenWidthDp = 0
  private var screenHeightDp = 0

  val size: Int get() = kept.size

  // Kept bitmap of this size, or null. Kept bitmaps of another size get freed, since the window changed size.
  fun take(width: Int, height: Int): B? {
    this.width = width
    this.height = height
    val index = kept.indexOfFirst { matches(it) }
    if (index < 0) {
      clear()
      return null
    }
    return kept.removeAt(index)
  }

  // A bitmap whose overlay is gone. Kept if keep is on, it has the size last asked for and there's room.
  fun give(bitmap: B) {
    if (isFreed(bitmap)) return
    if (keep && kept.size < capacity && matches(bitmap) && kept.none { it === bitmap }) {
      kept.add(bitmap)
    } else {
      free(bitmap)
    }
  }

  // Whether a warm-up needs to allocate a bitmap of this size. Kept bitmaps of another size don't count: fill frees
  // them.
  fun hasRoom(width: Int, height: Int): Boolean =
    keep && kept.count { widthOf(it) == width && heightOf(it) == height } < capacity

  // A warm-up's new bitmap. Returns whether it's kept; if not, it's freed.
  fun fill(bitmap: B): Boolean {
    if (!hasRoom(widthOf(bitmap), heightOf(bitmap))) {
      free(bitmap)
      return false
    }
    width = widthOf(bitmap)
    height = heightOf(bitmap)
    kept.filterNot { matches(it) }.forEach(free)
    kept.retainAll { matches(it) }
    kept.add(bitmap)
    return true
  }

  fun onTrimMemory(level: Int) {
    if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) clear()
  }

  fun onScreenSize(widthDp: Int, heightDp: Int) {
    val known = screenWidthDp != 0 || screenHeightDp != 0
    if (known && (widthDp != screenWidthDp || heightDp != screenHeightDp)) clear()
    screenWidthDp = widthDp
    screenHeightDp = heightDp
  }

  fun clear() {
    kept.forEach(free)
    kept.clear()
  }

  private fun matches(bitmap: B): Boolean = widthOf(bitmap) == width && heightOf(bitmap) == height
}
