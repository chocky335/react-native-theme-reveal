import CoreGraphics
import Foundation

private func close(_ a: Double, _ b: Double, _ epsilon: Double) -> Bool {
  abs(a - b) <= epsilon
}

private func close(_ a: CGPoint, _ b: CGPoint) -> Bool {
  close(Double(a.x), Double(b.x), 1e-6) && close(Double(a.y), Double(b.y), 1e-6)
}

private func close(_ a: CGRect, _ b: CGRect) -> Bool {
  close(a.origin, b.origin) && close(Double(a.width), Double(b.width), 1e-6)
    && close(Double(a.height), Double(b.height), 1e-6)
}

private func elementCount(_ path: CGPath) -> Int {
  var count = 0
  path.applyWithBlock { _ in count += 1 }
  return count
}

@main
enum MotionTests {
  static func main() {
    var failures = 0

    func check(_ condition: Bool, _ name: String) {
      if !condition {
        print("FAIL: \(name)")
        failures += 1
      }
    }

    // growth: same values as the Android MotionTest.
    check(close(Motion.growth(g: 0, from: 0.17, to: 20), 0.17, 1e-9), "growth starts at from")
    check(close(Motion.growth(g: 1, from: 0.17, to: 20), 20, 1e-9), "growth ends at to")
    var previous = Motion.growth(g: 0, from: 0.17, to: 20)
    for step in 1...100 {
      let next = Motion.growth(g: Double(step) / 100, from: 0.17, to: 20)
      check(next > previous, "growth is monotonic at step \(step)")
      previous = next
    }

    // shrink: from the cover scale down to 0 at the end, always falling.
    check(close(Motion.shrink(g: 0, from: 0.17, to: 20), 20, 1e-9), "shrink starts at to")
    check(close(Motion.shrink(g: 1, from: 0.17, to: 20), 0, 1e-9), "shrink ends at 0")
    previous = Motion.shrink(g: 0, from: 0.17, to: 20)
    for step in 1...100 {
      let next = Motion.shrink(g: Double(step) / 100, from: 0.17, to: 20)
      check(next < previous, "shrink is monotonic at step \(step)")
      previous = next
    }

    // coverScale: the distance to the farthest corner over the inscribed radius.
    check(
      close(
        Double(Motion.coverScale(originX: 0, originY: 0, width: 1080, height: 2400, inscribedRadius: 46.7)),
        hypot(1080, 2400) / 46.7, 1e-3),
      "coverScale from a corner reaches the opposite corner")
    check(
      close(
        Double(Motion.coverScale(originX: 540, originY: 1200, width: 1080, height: 2400, inscribedRadius: 10)),
        hypot(540, 1200) / 10, 1e-3),
      "coverScale from the centre uses half the diagonal")

    // easeInOut: cubic 0.65, 0, 0.35, 1.
    check(close(Motion.easeInOut(0), 0, 1e-6), "easeInOut(0) is 0")
    check(close(Motion.easeInOut(1), 1, 1e-6), "easeInOut(1) is 1")
    check(close(Motion.easeInOut(0.5), 0.5, 1e-4), "easeInOut(0.5) is 0.5")
    var eased = 0.0
    for step in 1...100 {
      let next = Motion.easeInOut(Double(step) / 100)
      check(next >= eased, "easeInOut is monotonic at step \(step)")
      eased = next
    }
    check(Motion.easeInOut(0.1) < 0.1, "easeInOut is slow at the start")
    check(Motion.easeInOut(0.9) > 0.9, "easeInOut is slow at the end")

    // canAnimate: an active app with reduce motion off.
    check(Motion.canAnimate(active: false, reduceMotion: false) == false, "canAnimate needs an active app")
    check(Motion.canAnimate(active: true, reduceMotion: true) == false, "canAnimate respects reduce motion")
    check(Motion.canAnimate(active: false, reduceMotion: true) == false, "canAnimate: inactive and reduce motion")
    check(Motion.canAnimate(active: true, reduceMotion: false), "canAnimate: active, reduce motion off")

    // sameSize: both sides must match (a rotation swaps them).
    check(Motion.sameSize(CGSize(width: 390, height: 844), CGSize(width: 390, height: 844)), "sameSize: equal")
    check(!Motion.sameSize(CGSize(width: 390, height: 844), CGSize(width: 844, height: 390)), "sameSize: rotated")
    check(!Motion.sameSize(CGSize(width: 390, height: 844), CGSize(width: 390, height: 800)), "sameSize: shorter")

    // canUseCapture: same window, same size, younger than the 3 s capture timeout.
    let phone = CGSize(width: 390, height: 844)
    check(Motion.captureTimeoutSeconds == 3, "the capture timeout is 3 s")
    check(Motion.canUseCapture(sameWindow: true, captured: phone, current: phone, ageSeconds: 0.1),
          "canUseCapture: a fresh capture of the same window")
    check(!Motion.canUseCapture(sameWindow: false, captured: phone, current: phone, ageSeconds: 0.1),
          "canUseCapture: another window")
    check(!Motion.canUseCapture(sameWindow: true, captured: phone, current: CGSize(width: 844, height: 390),
                                ageSeconds: 0.1), "canUseCapture: another size")
    check(Motion.canUseCapture(sameWindow: true, captured: phone, current: phone, ageSeconds: 2.999),
          "canUseCapture: just before the timeout")
    check(!Motion.canUseCapture(sameWindow: true, captured: phone, current: phone, ageSeconds: 3),
          "canUseCapture: at the timeout")
    check(!Motion.canUseCapture(sameWindow: true, captured: phone, current: phone, ageSeconds: -0.1),
          "canUseCapture: a capture from the future")

    // durationSeconds: a finite duration above 0 and up to 10 000 ms, in seconds (the same cap as Android).
    check(Motion.durationSeconds(ms: 450) == 0.45, "durationSeconds converts ms to seconds")
    check(Motion.durationSeconds(ms: 10_000) == 10, "durationSeconds keeps the 10 000 ms cap")
    for ms in [Double.nan, .infinity, -.infinity, 0, -1, 10_000.5, 450_000] {
      check(Motion.durationSeconds(ms: ms) == nil, "durationSeconds rejects \(ms) ms")
    }

    // effectiveInscribedRadius: same values as the Android ShapePathTest.
    let box = CGRect(x: 0, y: 0, width: 40, height: 30)
    let halfDiagonal = hypot(40.0, 30.0) / 2
    check(
      close(Double(Motion.effectiveInscribedRadius(bounds: box, anchor: CGPoint(x: 20, y: 15), inscribedRadius: 12)),
            12, 1e-6),
      "keeps a positive radius with the anchor inside")
    check(
      close(Double(Motion.effectiveInscribedRadius(bounds: box, anchor: CGPoint(x: 20, y: 15), inscribedRadius: 0)),
            halfDiagonal, 1e-6),
      "a zero radius falls back to half the diagonal")
    check(
      close(Double(Motion.effectiveInscribedRadius(bounds: box, anchor: CGPoint(x: 20, y: 15), inscribedRadius: -3)),
            halfDiagonal, 1e-6),
      "a negative radius falls back to half the diagonal")
    check(
      close(Double(Motion.effectiveInscribedRadius(bounds: box, anchor: CGPoint(x: 50, y: 15), inscribedRadius: 12)),
            halfDiagonal, 1e-6),
      "an anchor right of the bounds falls back to half the diagonal")
    check(
      close(Double(Motion.effectiveInscribedRadius(bounds: box, anchor: CGPoint(x: 20, y: -1), inscribedRadius: 12)),
            halfDiagonal, 1e-6),
      "an anchor above the bounds falls back to half the diagonal")
    check(
      close(Double(Motion.effectiveInscribedRadius(
        bounds: CGRect(x: -1, y: -1, width: 2, height: 2), anchor: .zero, inscribedRadius: 1)), 1, 1e-6),
      "a unit circle keeps its radius")

    // RevealShape: the unit circle default and the fallback for a bad path.
    let circle = RevealShape.circle
    check(close(circle.bounds, CGRect(x: -1, y: -1, width: 2, height: 2)), "the unit circle spans -1...1")
    check(close(circle.anchor, .zero), "the unit circle is anchored at its centre")
    check(close(Double(circle.inscribedRadius), 1, 1e-6), "the unit circle has radius 1")
    let square = RevealShape.resolve(path: "M0 0H40V30H0Z", anchor: CGPoint(x: 20, y: 15), inscribedRadius: 12)
    check(close(square.bounds, box), "a parsed shape keeps its bounds")
    check(close(Double(square.inscribedRadius), 12, 1e-6), "a parsed shape keeps its radius")
    let bad = RevealShape.resolve(path: "M0 0 L", anchor: CGPoint(x: 20, y: 15), inscribedRadius: 12)
    check(close(bad.bounds, circle.bounds), "an unparsable path falls back to the unit circle")
    let flat = RevealShape.resolve(path: "M0 0H40", anchor: .zero, inscribedRadius: 1)
    check(close(flat.bounds, circle.bounds), "an empty-area path falls back to the unit circle")
    let lost = RevealShape.resolve(path: "M0 0H40V30H0Z", anchor: CGPoint(x: Double.nan, y: 0), inscribedRadius: 1)
    check(close(lost.bounds, circle.bounds), "a non-finite anchor falls back to the unit circle")
    check(RevealShape.parse(path: "M0 0H40V30H0Z", anchor: CGPoint(x: 20, y: 15), inscribedRadius: 12) != nil,
          "parse returns a valid shape")
    check(RevealShape.parse(path: "M0 0 L", anchor: CGPoint(x: 20, y: 15), inscribedRadius: 12) == nil,
          "parse returns nil for an unparsable path")
    check(RevealShape.parse(path: "M0 0H40", anchor: .zero, inscribedRadius: 1) == nil,
          "parse returns nil for an empty-area path")
    check(RevealShape.parse(path: "M0 0H40V30H0Z", anchor: CGPoint(x: Double.nan, y: 0), inscribedRadius: 1) == nil,
          "parse returns nil for a non-finite anchor")

    // startScale: the shape starts 24 pt tall.
    check(close(Double(Motion.startScale(for: square)), 24.0 / 30.0, 1e-9), "the start scale makes the shape 24 pt tall")
    check(close(Double(Motion.startScale(for: circle)), 12, 1e-9), "the unit circle starts 24 pt tall")

    // shapePath: the anchor goes to the origin, the shape is scaled around it.
    let placed = Motion.shapePath(square, origin: CGPoint(x: 100, y: 200), scale: 2).boundingBoxOfPath
    check(close(placed, CGRect(x: 60, y: 170, width: 80, height: 60)), "shapePath places the anchor at the origin")

    // maskPath: reveal keeps the window rect plus the hole, inverse keeps the shape alone.
    let window = CGRect(x: 0, y: 0, width: 390, height: 844)
    let revealMask = Motion.maskPath(square, origin: CGPoint(x: 100, y: 200), scale: 2, bounds: window, inverse: false)
    check(close(revealMask.boundingBoxOfPath, window), "the reveal mask covers the window")
    check(!revealMask.contains(CGPoint(x: 100, y: 200), using: .evenOdd), "the reveal mask has a hole at the origin")
    check(revealMask.contains(CGPoint(x: 10, y: 10), using: .evenOdd), "the reveal mask keeps the rest")
    let inverseMask = Motion.maskPath(square, origin: CGPoint(x: 100, y: 200), scale: 2, bounds: window, inverse: true)
    check(close(inverseMask.boundingBoxOfPath, placed), "the inverse mask is the shape alone")

    // keyframes: 120 samples per second, both ends included, at least two.
    check(Motion.sampleCount(duration: 0.45) == 54, "450 ms gives 54 steps")
    check(Motion.sampleCount(duration: 0.001) == 2, "a tiny duration still gives 2 steps")
    check(Motion.sampleCount(duration: 10) == 1200, "10 s gives the cap of 1200 steps")
    check(Motion.sampleCount(duration: 60) == 1200, "a long duration is capped at 1200 steps")
    check(Motion.sampleCount(duration: .infinity) == 1200, "an infinite duration is capped at 1200 steps")
    check(Motion.sampleCount(duration: .nan) == 2, "a NaN duration gives 2 steps")
    let grow = Motion.scales(from: 0.5, to: 30, steps: 54, inverse: false)
    check(grow.count == 55, "scales include both ends")
    check(close(grow.first ?? 0, 0.5, 1e-9) && close(grow.last ?? 0, 30, 1e-9), "reveal scales run from to to")
    let shrinkScales = Motion.scales(from: 0.5, to: 30, steps: 54, inverse: true)
    check(close(shrinkScales.first ?? 0, 30, 1e-9) && close(shrinkScales.last ?? 1, 0, 1e-9),
          "inverse scales run from the cover scale to 0")

    // Keyframed paths interpolate only with the same element count in every path,
    // scale 0 included.
    for inverse in [false, true] {
      let counts = Set(Motion.scales(from: 0.8, to: 30, steps: 54, inverse: inverse).map { scale in
        elementCount(Motion.maskPath(square, origin: CGPoint(x: 100, y: 200), scale: CGFloat(scale),
                                     bounds: window, inverse: inverse))
      })
      check(counts.count == 1, "every \(inverse ? "inverse " : "")mask path has the same element count: \(counts)")
    }
    let lastInverse = Motion.maskPath(square, origin: CGPoint(x: 100, y: 200), scale: 0, bounds: window, inverse: true)
    check(elementCount(lastInverse) == elementCount(inverseMask), "a scale-0 mask keeps its elements")

    // Self-intersecting shapes fill nonzero (as on Android): the pentagram centre is inside
    // the shape under even-odd, so it's a hole in the reveal mask.
    let star = RevealShape.resolve(path: "M50 0 L79 90 L2 35 L98 35 L21 90 Z", anchor: CGPoint(x: 50, y: 50),
                                   inscribedRadius: 20)
    let centre = CGPoint(x: 50, y: 50)
    check(star.path.contains(centre, using: .evenOdd), "the pentagram centre is inside the shape under even-odd")
    let starMask = Motion.maskPath(star, origin: centre, scale: 1, bounds: CGRect(x: 0, y: 0, width: 100, height: 100),
                                   inverse: false)
    check(!starMask.contains(centre, using: .evenOdd), "the pentagram centre is a hole in the reveal mask")

    // wipeOffset: the direction is the way the edge travels.
    let size = CGSize(width: 390, height: 844)
    check(close(Motion.wipeOffset(.up, size: size), CGPoint(x: 0, y: -844)), "up moves the edge up")
    check(close(Motion.wipeOffset(.down, size: size), CGPoint(x: 0, y: 844)), "down moves the edge down")
    check(close(Motion.wipeOffset(.left, size: size), CGPoint(x: -390, y: 0)), "left moves the edge left")
    check(close(Motion.wipeOffset(.right, size: size), CGPoint(x: 390, y: 0)), "right moves the edge right")
    check(WipeDirection(rawValue: "up") == .up && WipeDirection(rawValue: "sideways") == nil, "wipe direction names")

    if failures > 0 {
      print("\(failures) Motion check(s) failed")
      exit(1)
    }
    print("Motion tests passed")
  }
}
