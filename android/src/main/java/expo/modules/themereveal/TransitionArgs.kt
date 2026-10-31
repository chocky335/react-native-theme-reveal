package expo.modules.themereveal

// The start argument, read from the JS object by hand. Expo would convert a Record with Kotlin reflection, and its
// first conversion blocked the main thread on the first start (85-102 ms on a Mi 11 Lite, 18-27 ms on a Pixel 8
// Pro). A missing, null or wrong-typed field gets the default the Records had, so start takes the same path for it.
internal data class PointArgs(val x: Double, val y: Double) {
  companion object {
    fun from(value: Any?): PointArgs? {
      val map = value as? Map<*, *> ?: return null
      return PointArgs(map.number("x"), map.number("y"))
    }
  }
}

internal data class ShapeArgs(val path: String, val anchor: PointArgs, val inscribedRadius: Double) {
  companion object {
    fun from(value: Any?): ShapeArgs? {
      val map = value as? Map<*, *> ?: return null
      return ShapeArgs(
        map.string("path") ?: "",
        PointArgs.from(map["anchor"]) ?: PointArgs(0.0, 0.0),
        map.number("inscribedRadius"),
      )
    }
  }
}

internal data class TransitionArgs(
  val type: String,
  val durationMs: Double,
  val origin: PointArgs?,
  val shape: ShapeArgs?,
  val direction: String?,
) {
  companion object {
    fun from(map: Map<*, *>): TransitionArgs = TransitionArgs(
      type = map.string("type") ?: "",
      durationMs = map.number("durationMs"),
      origin = PointArgs.from(map["origin"]),
      shape = ShapeArgs.from(map["shape"]),
      direction = map.string("direction"),
    )
  }
}

private fun Map<*, *>.number(key: String): Double = (this[key] as? Number)?.toDouble() ?: 0.0

private fun Map<*, *>.string(key: String): String? = this[key] as? String
