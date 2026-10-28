import CoreGraphics
import Foundation

enum WipeDirection: String {
  case up, down, left, right
}

// Reveal shape in path units. The anchor goes on the origin, and the radius is the distance
// from the anchor to the nearest edge (a bad value falls back to half the bounds diagonal).
struct RevealShape {
  let path: CGPath
  let bounds: CGRect
  let anchor: CGPoint
  let inscribedRadius: CGFloat

  static let circle = RevealShape(
    path: CGPath(ellipseIn: CGRect(x: -1, y: -1, width: 2, height: 2), transform: nil),
    anchor: .zero,
    inscribedRadius: 1)

  // An unparsable or empty path, or a non-finite anchor, falls back to the unit circle. The
  // path keeps nonzero fill (as on Android) inside the even-odd reveal mask.
  static func resolve(path: String, anchor: CGPoint, inscribedRadius: CGFloat) -> RevealShape {
    guard anchor.x.isFinite, anchor.y.isFinite, let parsed = SVGPath.parse(path) else { return circle }
    let shape = RevealShape(
      path: parsed.normalized(using: .winding), anchor: anchor, inscribedRadius: inscribedRadius)
    guard shape.bounds.width > 0, shape.bounds.height > 0 else { return circle }
    return shape
  }

  private init(path: CGPath, anchor: CGPoint, inscribedRadius: CGFloat) {
    self.path = path
    bounds = path.boundingBoxOfPath
    self.anchor = anchor
    self.inscribedRadius = Motion.effectiveInscribedRadius(
      bounds: bounds, anchor: anchor, inscribedRadius: inscribedRadius)
  }
}

enum Motion {
  static let startHeight: CGFloat = 24
  static let samplesPerSecond = 120.0
  // Keyframes stop at 10 s of samples; a longer duration stretches them.
  static let maxSeconds = 10.0
  static let maxSamples = 1200
  static let maxDurationMs = 10_000.0
  static let easeX1 = 0.65
  static let easeY1 = 0.0
  static let easeX2 = 0.35
  static let easeY2 = 1.0

  private static let growthCurve = 1.4
  private static let easeSteps = 24

  static func growth(g: Double, from: Double, to: Double) -> Double {
    from * pow(to / from, pow(g, growthCurve))
  }

  static func shrink(g: Double, from: Double, to: Double) -> Double {
    max(0, growth(g: 1 - g, from: from, to: to) - from * g)
  }

  static func coverScale(
    originX: CGFloat, originY: CGFloat, width: CGFloat, height: CGFloat, inscribedRadius: CGFloat
  ) -> CGFloat {
    hypot(max(originX, width - originX), max(originY, height - originY)) / inscribedRadius
  }

  static func easeInOut(_ t: Double) -> Double {
    if t <= 0 { return 0 }
    if t >= 1 { return 1 }
    var lo = 0.0
    var hi = 1.0
    for _ in 0..<easeSteps {
      let mid = (lo + hi) / 2
      if bezier(mid, easeX1, easeX2) < t { lo = mid } else { hi = mid }
    }
    return bezier((lo + hi) / 2, easeY1, easeY2)
  }

  // Non-finite, zero, negative or overlong durations are invalid: the overlay would sit on
  // screen too long. Same cap as Android.
  static func durationSeconds(ms: Double) -> Double? {
    guard ms.isFinite, ms > 0, ms <= maxDurationMs else { return nil }
    return ms / 1000
  }

  static func canAnimate(active: Bool, reduceMotion: Bool) -> Bool {
    active && !reduceMotion
  }

  static func sameSize(_ snapshot: CGSize, _ window: CGSize) -> Bool {
    snapshot.width == window.width && snapshot.height == window.height
  }

  static func effectiveInscribedRadius(bounds: CGRect, anchor: CGPoint, inscribedRadius: CGFloat) -> CGFloat {
    let anchorInside = anchor.x >= bounds.minX && anchor.x <= bounds.maxX
      && anchor.y >= bounds.minY && anchor.y <= bounds.maxY
    return inscribedRadius > 0 && anchorInside ? inscribedRadius : hypot(bounds.width, bounds.height) / 2
  }

  // Scale at which the shape is startHeight points tall.
  static func startScale(for shape: RevealShape) -> CGFloat {
    startHeight / shape.bounds.height
  }

  static func shapePath(_ shape: RevealShape, origin: CGPoint, scale: CGFloat) -> CGPath {
    let transform = CGAffineTransform(translationX: origin.x, y: origin.y)
      .scaledBy(x: scale, y: scale)
      .translatedBy(x: -shape.anchor.x, y: -shape.anchor.y)
    let path = CGMutablePath()
    path.addPath(shape.path, transform: transform)
    return path
  }

  // reveal: the window rect with the shape as an even-odd hole. inverse: the shape alone.
  static func maskPath(_ shape: RevealShape, origin: CGPoint, scale: CGFloat, bounds: CGRect, inverse: Bool) -> CGPath {
    if inverse { return shapePath(shape, origin: origin, scale: scale) }
    let path = CGMutablePath()
    path.addRect(bounds)
    path.addPath(shapePath(shape, origin: origin, scale: scale))
    return path
  }

  static func sampleCount(duration: Double) -> Int {
    guard duration > 0 else { return 2 }
    return min(maxSamples, max(2, Int((min(duration, maxSeconds) * samplesPerSecond).rounded())))
  }

  // steps + 1 scales, g linear in time from 0 to 1.
  static func scales(from: Double, to: Double, steps: Int, inverse: Bool) -> [Double] {
    (0...steps).map { step in
      let g = Double(step) / Double(steps)
      return inverse ? shrink(g: g, from: from, to: to) : growth(g: g, from: from, to: to)
    }
  }

  // How far the wipe mask moves. The edge travels in the named direction, uncovering the
  // new theme behind it (up: from the bottom).
  static func wipeOffset(_ direction: WipeDirection, size: CGSize) -> CGPoint {
    switch direction {
    case .up: return CGPoint(x: 0, y: -size.height)
    case .down: return CGPoint(x: 0, y: size.height)
    case .left: return CGPoint(x: -size.width, y: 0)
    case .right: return CGPoint(x: size.width, y: 0)
    }
  }

  private static func bezier(_ s: Double, _ p1: Double, _ p2: Double) -> Double {
    let u = 1 - s
    return 3 * u * u * s * p1 + 3 * u * s * s * p2 + s * s * s
  }
}
