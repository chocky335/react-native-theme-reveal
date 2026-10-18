import CoreGraphics
import Foundation

/// Parses SVG path data (the `d` attribute) into a `CGPath`.
///
/// Supports `M L H V C S Q T A Z` in absolute and relative forms, implicit
/// repeated commands, and the SVG number syntax (`1e-3`, `.5.5`, `-1-2`).
/// Returns `nil` for malformed input.
enum SVGPath {
  static func parse(_ d: String) -> CGPath? {
    var parser = SVGPathParser(Array(d.utf8))
    return parser.parse()
  }
}

private struct SVGPathParser {
  private let bytes: [UInt8]
  private var index = 0
  private let path = CGMutablePath()
  private var current = CGPoint.zero
  private var subpathStart = CGPoint.zero
  private var lastCubicControl: CGPoint?
  private var lastQuadControl: CGPoint?
  private var needsMove = false

  init(_ bytes: [UInt8]) {
    self.bytes = bytes
  }

  mutating func parse() -> CGPath? {
    skipSeparators()
    guard let first = peek(), first == ascii("M") || first == ascii("m") else { return nil }

    var command = first
    while true {
      skipSeparators()
      guard let byte = peek() else { break }
      if Self.commands.contains(byte) {
        index += 1
        command = byte
      } else if isNumberStart(byte) {
        // A number with no command letter repeats the previous command. Extra coordinate
        // pairs after M or m are L or l.
        switch command {
        case ascii("M"): command = ascii("L")
        case ascii("m"): command = ascii("l")
        case ascii("Z"), ascii("z"): return nil
        default: break
        }
      } else {
        return nil
      }
      guard execute(command) else { return nil }
    }
    return path.copy()
  }

  // MARK: - Commands

  private static let commands = Set("MmLlHhVvCcSsQqTtAaZz".utf8)

  private mutating func execute(_ command: UInt8) -> Bool {
    let relative = command >= ascii("a")
    let origin = relative ? current : .zero
    let previousCubicControl = lastCubicControl
    let previousQuadControl = lastQuadControl
    lastCubicControl = nil
    lastQuadControl = nil

    switch command | 0x20 {
    case ascii("m"):
      guard let point = point(from: origin) else { return false }
      path.move(to: point)
      subpathStart = point
      current = point
      needsMove = false

    case ascii("l"):
      guard let point = point(from: origin) else { return false }
      line(to: point)

    case ascii("h"):
      guard let x = number(), isFinite(origin.x + x) else { return false }
      line(to: CGPoint(x: origin.x + x, y: current.y))

    case ascii("v"):
      guard let y = number(), isFinite(origin.y + y) else { return false }
      line(to: CGPoint(x: current.x, y: origin.y + y))

    case ascii("c"):
      guard let control1 = point(from: origin),
        let control2 = point(from: origin),
        let end = point(from: origin)
      else { return false }
      cubic(to: end, control1: control1, control2: control2)

    case ascii("s"):
      guard let control2 = point(from: origin), let end = point(from: origin) else { return false }
      let control1 = previousCubicControl.map(reflect) ?? current
      guard isFinite(control1) else { return false }
      cubic(to: end, control1: control1, control2: control2)

    case ascii("q"):
      guard let control = point(from: origin), let end = point(from: origin) else { return false }
      quad(to: end, control: control)

    case ascii("t"):
      guard let end = point(from: origin) else { return false }
      let control = previousQuadControl.map(reflect) ?? current
      guard isFinite(control) else { return false }
      quad(to: end, control: control)

    case ascii("a"):
      guard let rx = number(),
        let ry = number(),
        let rotation = number(),
        let largeArc = flag(),
        let sweep = flag(),
        let end = point(from: origin)
      else { return false }
      guard arc(to: end, rx: rx, ry: ry, rotation: rotation, largeArc: largeArc, sweep: sweep) else {
        return false
      }

    case ascii("z"):
      path.closeSubpath()
      current = subpathStart
      needsMove = true

    default:
      return false
    }
    return true
  }

  /// After Z, the next drawing command starts at the closed subpath's start point.
  private mutating func beginSegment() {
    if needsMove {
      path.move(to: current)
      needsMove = false
    }
  }

  private mutating func line(to point: CGPoint) {
    beginSegment()
    path.addLine(to: point)
    current = point
  }

  private mutating func cubic(to end: CGPoint, control1: CGPoint, control2: CGPoint) {
    beginSegment()
    path.addCurve(to: end, control1: control1, control2: control2)
    lastCubicControl = control2
    current = end
  }

  private mutating func quad(to end: CGPoint, control: CGPoint) {
    beginSegment()
    path.addQuadCurve(to: end, control: control)
    lastQuadControl = control
    current = end
  }

  private func reflect(_ control: CGPoint) -> CGPoint {
    CGPoint(x: 2 * current.x - control.x, y: 2 * current.y - control.y)
  }

  /// Elliptical arc per the SVG 1.1 implementation notes: endpoint to centre conversion
  /// (F.6.5) with out-of-range radii correction (F.6.6), drawn as cubic segments of at most
  /// 90 degrees each. Returns false if the arithmetic leaves the finite range, so the parse
  /// fails instead of trapping.
  private mutating func arc(
    to end: CGPoint, rx: CGFloat, ry: CGFloat, rotation: CGFloat, largeArc: Bool, sweep: Bool
  ) -> Bool {
    let start = current
    guard isFinite(start), isFinite(end) else { return false }
    guard start != end else { return true }
    var rx = abs(rx)
    var ry = abs(ry)
    guard rx > 0, ry > 0 else {
      line(to: end)
      return true
    }

    let phi = rotation * .pi / 180
    let cosPhi = cos(phi)
    let sinPhi = sin(phi)

    // F.6.5.1: the start point in the ellipse's rotated frame.
    let halfDx = (start.x - end.x) / 2
    let halfDy = (start.y - end.y) / 2
    let x1 = cosPhi * halfDx + sinPhi * halfDy
    let y1 = -sinPhi * halfDx + cosPhi * halfDy

    // F.6.6: scale the radii up if they cannot span the end points.
    let lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
    if lambda > 1 {
      let scale = lambda.squareRoot()
      rx *= scale
      ry *= scale
    }

    // F.6.5.2: the centre in the rotated frame.
    let rx2 = rx * rx
    let ry2 = ry * ry
    let denominator = rx2 * y1 * y1 + ry2 * x1 * x1
    let ratio = (rx2 * ry2 - denominator) / denominator
    // End points too close for these radii to place a centre, so draw a line.
    guard denominator != 0, ratio != .infinity else {
      line(to: end)
      return true
    }
    var coefficient = max(0, ratio).squareRoot()
    if largeArc == sweep {
      coefficient = -coefficient
    }
    let centreX1 = coefficient * rx * y1 / ry
    let centreY1 = -coefficient * ry * x1 / rx

    // F.6.5.3: the centre in user space.
    let centreX = cosPhi * centreX1 - sinPhi * centreY1 + (start.x + end.x) / 2
    let centreY = sinPhi * centreX1 + cosPhi * centreY1 + (start.y + end.y) / 2

    // F.6.5.5 and F.6.5.6: the start angle and the sweep angle.
    let startAngle = atan2((y1 - centreY1) / ry, (x1 - centreX1) / rx)
    let endAngle = atan2((-y1 - centreY1) / ry, (-x1 - centreX1) / rx)
    var sweepAngle = endAngle - startAngle
    if sweep, sweepAngle < 0 {
      sweepAngle += 2 * .pi
    } else if !sweep, sweepAngle > 0 {
      sweepAngle -= 2 * .pi
    }
    guard centreX.isFinite, centreY.isFinite, startAngle.isFinite, sweepAngle.isFinite else {
      return false
    }

    func onEllipse(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
      CGPoint(
        x: centreX + rx * cosPhi * x - ry * sinPhi * y,
        y: centreY + rx * sinPhi * x + ry * cosPhi * y
      )
    }

    let segments = max(1, Int((abs(sweepAngle) / (.pi / 2) - 1e-7).rounded(.up)))
    let step = sweepAngle / CGFloat(segments)
    let handle = 4 / 3 * tan(step / 4)
    var angle = startAngle
    var curves: [(point: CGPoint, control1: CGPoint, control2: CGPoint)] = []
    for segment in 0..<segments {
      let next = angle + step
      let cos1 = cos(angle)
      let sin1 = sin(angle)
      let cos2 = cos(next)
      let sin2 = sin(next)
      let control1 = onEllipse(cos1 - handle * sin1, sin1 + handle * cos1)
      let control2 = onEllipse(cos2 + handle * sin2, sin2 - handle * cos2)
      let point = segment == segments - 1 ? end : onEllipse(cos2, sin2)
      guard isFinite(control1), isFinite(control2), isFinite(point) else { return false }
      curves.append((point, control1, control2))
      angle = next
    }
    beginSegment()
    for curve in curves {
      path.addCurve(to: curve.point, control1: curve.control1, control2: curve.control2)
    }
    current = end
    return true
  }

  // MARK: - Tokens

  private func peek() -> UInt8? {
    index < bytes.count ? bytes[index] : nil
  }

  private mutating func skipSeparators() {
    while let byte = peek(), Self.separators.contains(byte) {
      index += 1
    }
  }

  private static let separators = Set(" \t\n\r\u{0C},".utf8)

  private func isDigit(_ byte: UInt8) -> Bool {
    byte >= ascii("0") && byte <= ascii("9")
  }

  private func isNumberStart(_ byte: UInt8) -> Bool {
    isDigit(byte) || byte == ascii(".") || byte == ascii("-") || byte == ascii("+")
  }

  private mutating func point(from origin: CGPoint) -> CGPoint? {
    guard let x = number(), let y = number() else { return nil }
    let point = CGPoint(x: origin.x + x, y: origin.y + y)
    return isFinite(point) ? point : nil
  }

  /// Scans `[sign] digits [. digits] [e [sign] digits]`. A sign or a second
  /// dot ends the number, so `-1-2` and `.5.5` are two numbers each.
  private mutating func number() -> CGFloat? {
    skipSeparators()
    let start = index
    if let byte = peek(), byte == ascii("-") || byte == ascii("+") {
      index += 1
    }
    var digits = 0
    while let byte = peek(), isDigit(byte) {
      index += 1
      digits += 1
    }
    // SVG allows `5.` but Double(String) rejects it, so drop that trailing dot from the
    // text.
    var bareDot: Int?
    if peek() == ascii(".") {
      bareDot = index
      index += 1
      while let byte = peek(), isDigit(byte) {
        index += 1
        digits += 1
        bareDot = nil
      }
    }
    guard digits > 0 else {
      index = start
      return nil
    }
    if let byte = peek(), byte == ascii("e") || byte == ascii("E") {
      var exponent = index + 1
      if exponent < bytes.count, bytes[exponent] == ascii("-") || bytes[exponent] == ascii("+") {
        exponent += 1
      }
      if exponent < bytes.count, isDigit(bytes[exponent]) {
        while exponent < bytes.count, isDigit(bytes[exponent]) {
          exponent += 1
        }
        index = exponent
      }
    }
    var text = bytes[start..<index]
    if let bareDot {
      text.remove(at: bareDot)
    }
    guard let value = Double(String(decoding: text, as: UTF8.self)), value.isFinite else { return nil }
    return CGFloat(value)
  }

  /// Arc flags are a single `0` or `1` and need no separator after them.
  private mutating func flag() -> Bool? {
    skipSeparators()
    switch peek() {
    case ascii("0"):
      index += 1
      return false
    case ascii("1"):
      index += 1
      return true
    default:
      return nil
    }
  }
}

private func isFinite(_ value: CGFloat) -> Bool {
  value.isFinite
}

private func isFinite(_ point: CGPoint) -> Bool {
  point.x.isFinite && point.y.isFinite
}

private func ascii(_ scalar: Unicode.Scalar) -> UInt8 {
  UInt8(ascii: scalar)
}
