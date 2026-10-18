package expo.modules.themereveal

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.view.PixelCopy
import android.view.Window

internal object WindowSnapshot {
  fun capture(window: Window, width: Int, height: Int, handler: Handler, onResult: (Bitmap?) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || width <= 0 || height <= 0) {
      onResult(null)
      return
    }
    val bitmap = try {
      Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    } catch (_: OutOfMemoryError) {
      onResult(null)
      return
    }
    val listener = PixelCopy.OnPixelCopyFinishedListener { result ->
      if (result == PixelCopy.SUCCESS) {
        onResult(bitmap)
      } else {
        bitmap.recycle()
        onResult(null)
      }
    }
    try {
      PixelCopy.request(window, bitmap, listener, handler)
    } catch (_: IllegalArgumentException) {
      bitmap.recycle()
      onResult(null)
    }
  }
}
