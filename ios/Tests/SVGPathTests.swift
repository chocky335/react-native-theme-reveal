import CoreGraphics
import Foundation

private enum Kind: Equatable {
  case move, line, quad, cubic, close
}

private struct Element {
  let kind: Kind
  let points: [CGPoint]
}

private func elements(_ path: CGPath) -> [Element] {
  var result: [Element] = []
  path.applyWithBlock { pointer in
    let element = pointer.pointee
    switch element.type {
    case .moveToPoint:
      result.append(Element(kind: .move, points: [element.points[0]]))
    case .addLineToPoint:
      result.append(Element(kind: .line, points: [element.points[0]]))
    case .addQuadCurveToPoint:
      result.append(Element(kind: .quad, points: (0..<2).map { element.points[$0] }))
    case .addCurveToPoint:
      result.append(Element(kind: .cubic, points: (0..<3).map { element.points[$0] }))
    case .closeSubpath:
      result.append(Element(kind: .close, points: []))
    @unknown default:
      break
    }
  }
  return result
}

private let epsilon: CGFloat = 1e-3

private func close(_ a: CGFloat, _ b: CGFloat) -> Bool {
  abs(a - b) <= epsilon
}

private func close(_ a: CGPoint, _ b: CGPoint) -> Bool {
  close(a.x, b.x) && close(a.y, b.y)
}

private func close(_ a: CGRect, _ b: CGRect) -> Bool {
  close(a.minX, b.minX) && close(a.minY, b.minY) && close(a.width, b.width) && close(a.height, b.height)
}

private func same(_ a: [Element], _ b: [Element]) -> Bool {
  guard a.count == b.count else { return false }
  return zip(a, b).allSatisfy { lhs, rhs in
    lhs.kind == rhs.kind && lhs.points.count == rhs.points.count
      && zip(lhs.points, rhs.points).allSatisfy { close($0, $1) }
  }
}

private func move(_ x: CGFloat, _ y: CGFloat) -> Element {
  Element(kind: .move, points: [CGPoint(x: x, y: y)])
}

private func line(_ x: CGFloat, _ y: CGFloat) -> Element {
  Element(kind: .line, points: [CGPoint(x: x, y: y)])
}

private let closePath = Element(kind: .close, points: [])

// Heart drawn for this test: six cubic segments (C and S, absolute). Each segment's control
// polygon stays inside the box spanned by the segment endpoints that touch the outer edges,
// and the tangent is axis-aligned at those endpoints, so the exact bounds are the endpoint
// extremes: x from 0 to 100, y from 5 (both lobe tops) to 90 (the tip).
private let heart =
  "M50 90C20 80 0 50 0 30S10 5 25 5S50 10 50 25C50 10 60 5 75 5S100 10 100 30S80 80 50 90Z"

@main
enum SVGPathTests {
  static func main() {
    var failures = 0

    func check(_ condition: Bool, _ name: String) {
      if !condition {
        print("FAIL: \(name)")
        failures += 1
      }
    }

    func parsed(_ d: String) -> CGPath? {
      let path = SVGPath.parse(d)
      check(path != nil, "parses \(d)")
      return path
    }

    func bounds(_ d: String, _ expected: CGRect) {
      guard let path = parsed(d) else { return }
      let actual = path.boundingBoxOfPath
      check(close(actual, expected), "bounds of \(d): got \(actual), expected \(expected)")
    }

    func shape(_ d: String, _ expected: [Element]) {
      guard let path = parsed(d) else { return }
      let actual = elements(path)
      check(same(actual, expected), "elements of \(d): got \(actual)")
    }

    func equal(_ a: String, _ b: String) {
      guard let lhs = parsed(a), let rhs = parsed(b) else { return }
      check(same(elements(lhs), elements(rhs)), "\(a) equals \(b)")
    }

    func rejects(_ d: String) {
      check(SVGPath.parse(d) == nil, "rejects \"\(d)\"")
    }

    // Unit square and its relative forms.
    bounds("M0 0H1V1H0Z", CGRect(x: 0, y: 0, width: 1, height: 1))
    bounds("m0 0l1 0 0 1z", CGRect(x: 0, y: 0, width: 1, height: 1))
    shape("M0 0H1V1H0Z", [move(0, 0), line(1, 0), line(1, 1), line(0, 1), closePath])
    equal("m0 0h1v1h-1z", "M0 0H1V1H0Z")
    equal("m0 0l1 0 0 1z", "M0 0L1 0L1 1Z")

    // Implicit repeated commands: extra pairs after M or m become L or l.
    equal("M0 0 1 0 1 1 0 1z", "M0 0L1 0L1 1L0 1Z")
    shape("m1 1 1 0 0 1", [move(1, 1), line(2, 1), line(2, 2)])
    shape("M0 0L1 1 2 0 3 1", [move(0, 0), line(1, 1), line(2, 0), line(3, 1)])

    // Number syntax, commas and whitespace.
    shape("M.5.5L1e-3-2", [move(0.5, 0.5), line(0.001, -2)])
    shape("M-1-2l+3,4", [move(-1, -2), line(2, 2)])
    shape("M5. 1.L1E1 2e+0", [move(5, 1), line(10, 2)])
    shape(" \tM 0 , 0 \n L\r1,1 ", [move(0, 0), line(1, 1)])

    // Relative commands after Z start from the closed subpath's start point.
    shape(
      "M1 1h1v1zm1 0h1",
      [move(1, 1), line(2, 1), line(2, 2), closePath, move(2, 1), line(3, 1)]
    )
    // A draw command right after Z continues from the subpath start.
    shape("M1 1h1v1zl0 2", [move(1, 1), line(2, 1), line(2, 2), closePath, move(1, 1), line(1, 3)])

    // Quadratic: T reflects the previous Q control point (1,1) about (2,0) to (3,-1).
    shape(
      "M0 0Q1 1 2 0T4 0",
      [
        move(0, 0),
        Element(kind: .quad, points: [CGPoint(x: 1, y: 1), CGPoint(x: 2, y: 0)]),
        Element(kind: .quad, points: [CGPoint(x: 3, y: -1), CGPoint(x: 4, y: 0)]),
      ]
    )
    equal("m0 0q1 1 2 0t2 0", "M0 0Q1 1 2 0T4 0")
    // Each quad peaks at half its control height: y from -0.5 to 0.5.
    bounds("M0 0Q1 1 2 0T4 0", CGRect(x: 0, y: -0.5, width: 4, height: 1))
    // T without a previous Q uses the current point as the control point.
    shape(
      "M0 0T2 0",
      [move(0, 0), Element(kind: .quad, points: [CGPoint(x: 0, y: 0), CGPoint(x: 2, y: 0)])]
    )

    // Cubic: S reflects the previous C control point (1,1) about (1,0) to (1,-1).
    shape(
      "M0 0C0 1 1 1 1 0S2 -1 2 0",
      [
        move(0, 0),
        Element(kind: .cubic, points: [CGPoint(x: 0, y: 1), CGPoint(x: 1, y: 1), CGPoint(x: 1, y: 0)]),
        Element(kind: .cubic, points: [CGPoint(x: 1, y: -1), CGPoint(x: 2, y: -1), CGPoint(x: 2, y: 0)]),
      ]
    )
    equal("m0 0c0 1 1 1 1 0s1-1 1 0", "M0 0C0 1 1 1 1 0S2 -1 2 0")
    // S after a non-cubic command uses the current point as the first control point.
    shape(
      "M0 0L1 0S2 1 3 0",
      [
        move(0, 0), line(1, 0),
        Element(kind: .cubic, points: [CGPoint(x: 1, y: 0), CGPoint(x: 2, y: 1), CGPoint(x: 3, y: 0)]),
      ]
    )

    // The heart: one move, six cubics, one close; S reflects (0,50) about (0,30) to (0,10).
    if let path = parsed(heart) {
      let parts = elements(path)
      check(parts.map(\.kind) == [.move] + Array(repeating: .cubic, count: 6) + [.close], "heart elements")
      check(parts.count > 2 && close(parts[2].points[0], CGPoint(x: 0, y: 10)), "heart S reflection")
      check(
        close(path.boundingBoxOfPath, CGRect(x: 0, y: 5, width: 100, height: 85)),
        "heart bounds: got \(path.boundingBoxOfPath)"
      )
    }

    // Arc from (0,0) to (2,0), radius 1: centre (1,0), half circle. Sweep 1 runs
    // from angle 180 to 360 degrees, through (1,-1); sweep 0 runs through (1,1).
    bounds("M0 0 A1 1 0 0 1 2 0", CGRect(x: 0, y: -1, width: 2, height: 1))
    bounds("M0 0 A1 1 0 0 0 2 0", CGRect(x: 0, y: 0, width: 2, height: 1))
    if let path = parsed("M0 0 A1 1 0 0 1 2 0") {
      check(close(path.boundingBoxOfPath.width, 2), "arc width 2")
      check(close(path.currentPoint, CGPoint(x: 2, y: 0)), "arc ends at its end point")
      check(elements(path).dropFirst().allSatisfy { $0.kind == .cubic }, "arc is cubics")
      check(elements(path).count == 3, "half circle is two 90 degree cubics")
    }
    // Compact flags and relative end point.
    equal("M0 0a1 1 0 012 0", "M0 0 A1 1 0 0 1 2 0")
    // Radius too small: scaled up to 1 (F.6.6), same half circle.
    bounds("M0 0 A0.5 0.5 0 0 1 2 0", CGRect(x: 0, y: -1, width: 2, height: 1))
    // Negative radii use their absolute value.
    bounds("M0 0 A-1 -1 0 0 1 2 0", CGRect(x: 0, y: -1, width: 2, height: 1))
    // Large arc from (0,0) to (1,1), radius 1. The centre candidates are (1,0)
    // and (0,1); large 1 with sweep 1 picks (1,0). The arc starts at 180 degrees
    // and runs forward 270 degrees through (1,-1) and (2,0) to (1,1).
    bounds("M0 0 A1 1 0 1 1 1 1", CGRect(x: 0, y: -1, width: 2, height: 2))
    // The small arc with the same sweep picks centre (0,1): a quarter circle.
    bounds("M0 0 A1 1 0 0 1 1 1", CGRect(x: 0, y: 0, width: 1, height: 1))
    // Rotated ellipse: rx 2 and ry 1 rotated 90 degrees give a vertical radius 2
    // and a horizontal radius 1. From (0,0) to (0,4) the centre is (0,2), and
    // sweep 1 runs from 180 to 360 degrees through (1,2).
    bounds("M0 0 A2 1 90 0 1 0 4", CGRect(x: 0, y: 0, width: 1, height: 4))
    // Zero radius: a straight line.
    shape("M0 0 A0 1 0 0 1 2 2", [move(0, 0), line(2, 2)])
    // Same start and end point: the arc is skipped.
    shape("M1 1 A1 1 0 0 1 1 1L2 2", [move(1, 1), line(2, 2)])

    // End points too close for the radius to place a centre (the centre arithmetic
    // underflows), so the arc becomes a line to the end point.
    shape("M0 0 A1 1 0 0 1 1e-160 0", [move(0, 0), line(1e-160, 0)])
    shape("M0 0 A1 1 0 0 1 1e-320 0", [move(0, 0), line(1e-320, 0)])

    // Arithmetic that leaves the finite range returns nil, never a trap or an inf point.
    rejects("M0 0 A1e200 1e-200 45 0 1 1 1")
    rejects("M1e308 0 l1e308 0 A1 1 0 0 1 0 0")
    rejects("M1e308 0 l1e308 0 l1 1")
    rejects("M1e308 0h1e308")
    rejects("M0 1e308v1e308")
    rejects("M1e308 0C0 0 -1e308 0 1e308 0S1 1 1 1")
    rejects("M1e308 0Q-1e308 0 1e308 0T1 1")
    rejects("M-1.7e308 0 A1 1 0 1 1 1.7e308 0")

    // Malformed input returns nil.
    rejects("M0 0 L")
    rejects("M0 0 L1")
    rejects("M0")
    rejects("")
    rejects("   ")
    rejects("L1 1")
    rejects("1 1")
    rejects("M0 0 X1 1")
    rejects("M0 0 L1 1 #")
    rejects("M0 0 C1 1 2 2")
    rejects("M0 0 A1 1 0 2 1 2 0")
    rejects("M0 0 A1 1 0 0 1 2")
    rejects("M0 0Z1 1")
    rejects("M0 0L-")
    rejects("M0 0L.")
    rejects("M0 0L1e999 0")

    if failures > 0 {
      print("\(failures) SVGPath check(s) failed")
      exit(1)
    }
    print("SVGPath tests passed")
  }
}
