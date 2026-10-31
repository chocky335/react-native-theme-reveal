package expo.modules.themereveal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransitionArgsTest {
  @Test
  fun readsEveryField() {
    val args = TransitionArgs.from(
      mapOf(
        "type" to "reveal",
        "durationMs" to 450.0,
        "origin" to mapOf("x" to 10.5, "y" to 20.0),
        "shape" to mapOf("path" to "M0 0L1 1Z", "anchor" to mapOf("x" to 0.5, "y" to 0.25), "inscribedRadius" to 0.4),
        "direction" to "left",
      ),
    )

    assertEquals("reveal", args.type)
    assertEquals(450.0, args.durationMs, 0.0)
    assertEquals(PointArgs(10.5, 20.0), args.origin)
    assertEquals(ShapeArgs("M0 0L1 1Z", PointArgs(0.5, 0.25), 0.4), args.shape)
    assertEquals("left", args.direction)
  }

  @Test
  fun acceptsAnyNumberType() {
    val args = TransitionArgs.from(mapOf("durationMs" to 300, "origin" to mapOf("x" to 1L, "y" to 2f)))

    assertEquals(300.0, args.durationMs, 0.0)
    assertEquals(PointArgs(1.0, 2.0), args.origin)
  }

  // Defaults the Expo Records had, so each missing field takes the same path in start as before.
  @Test
  fun givesTheOldDefaultsForMissingFields() {
    val args = TransitionArgs.from(emptyMap<String, Any?>())

    assertEquals("", args.type)
    assertEquals(0.0, args.durationMs, 0.0)
    assertNull(args.origin)
    assertNull(args.shape)
    assertNull(args.direction)
  }

  @Test
  fun givesTheOldDefaultsInsideOriginAndShape() {
    val args = TransitionArgs.from(mapOf("origin" to emptyMap<String, Any?>(), "shape" to emptyMap<String, Any?>()))

    assertEquals(PointArgs(0.0, 0.0), args.origin)
    assertEquals(ShapeArgs("", PointArgs(0.0, 0.0), 0.0), args.shape)
  }

  @Test
  fun treatsNullAsMissing() {
    val args = TransitionArgs.from(
      mapOf("type" to null, "durationMs" to null, "origin" to null, "shape" to null, "direction" to null),
    )

    assertEquals("", args.type)
    assertEquals(0.0, args.durationMs, 0.0)
    assertNull(args.origin)
    assertNull(args.shape)
    assertNull(args.direction)
  }

  // A wrong-typed value counts as missing, so start drops the overlay right away (no animation) instead of failing
  // the call.
  @Test
  fun treatsAWrongTypeAsMissing() {
    val args = TransitionArgs.from(
      mapOf(
        "type" to 3.0,
        "durationMs" to "450",
        "origin" to listOf(1.0, 2.0),
        "shape" to mapOf("path" to 7.0, "anchor" to "centre", "inscribedRadius" to true),
        "direction" to mapOf("x" to 1.0),
      ),
    )

    assertEquals("", args.type)
    assertEquals(0.0, args.durationMs, 0.0)
    assertNull(args.origin)
    assertEquals(ShapeArgs("", PointArgs(0.0, 0.0), 0.0), args.shape)
    assertNull(args.direction)
  }

  @Test
  fun keepsNonFiniteDurationsForMotionToReject() {
    val args = TransitionArgs.from(mapOf("durationMs" to Double.NaN))

    assertEquals(Double.NaN, args.durationMs, 0.0)
    assertNull(Motion.durationMs(args.durationMs))
  }
}
