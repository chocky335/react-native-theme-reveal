package expo.modules.themereveal

import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import androidx.core.graphics.PathParser
import kotlin.math.hypot

internal class ShapePath private constructor(
  val path: Path,
  val bounds: RectF,
  val anchorX: Float,
  val anchorY: Float,
  val effectiveInscribedRadius: Float,
) {
  companion object {
    fun parse(path: String, anchor: PointF, inscribedRadius: Float): ShapePath {
      val shape = of(PathParser.createPathFromPathData(path), anchor.x, anchor.y, inscribedRadius)
      require(shape.bounds.width() > 0f && shape.bounds.height() > 0f) { "The shape path is empty" }
      return shape
    }

    fun circle(): ShapePath = of(Path().apply { addCircle(0f, 0f, 1f, Path.Direction.CW) }, 0f, 0f, 1f)

    private fun of(path: Path, anchorX: Float, anchorY: Float, inscribedRadius: Float): ShapePath {
      val bounds = RectF()
      path.computeBounds(bounds, true)
      val radius = effectiveInscribedRadius(
        bounds.left, bounds.top, bounds.right, bounds.bottom, anchorX, anchorY, inscribedRadius,
      )
      return ShapePath(path, bounds, anchorX, anchorY, radius)
    }
  }
}

internal fun effectiveInscribedRadius(
  left: Float,
  top: Float,
  right: Float,
  bottom: Float,
  anchorX: Float,
  anchorY: Float,
  inscribedRadius: Float,
): Float {
  val anchorInside = anchorX in left..right && anchorY in top..bottom
  return if (inscribedRadius > 0f && anchorInside) inscribedRadius else hypot(right - left, bottom - top) / 2
}
