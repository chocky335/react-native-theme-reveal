import UIKit

// Window snapshot on top of the app content, animated away to reveal the new theme.
final class RevealOverlay {
  let view: UIView
  let size: CGSize
  private var playing = 0

  init?(window: UIWindow) {
    guard let snapshot = window.snapshotView(afterScreenUpdates: false) else { return nil }
    snapshot.frame = window.bounds
    snapshot.isUserInteractionEnabled = false
    snapshot.isAccessibilityElement = false
    snapshot.accessibilityElementsHidden = true
    window.addSubview(snapshot)
    view = snapshot
    size = window.bounds.size
  }

  func reveal(_ shape: RevealShape, origin: CGPoint, inverse: Bool, duration: Double, onEnd: @escaping () -> Void) {
    let bounds = view.bounds
    let from = Double(Motion.startScale(for: shape))
    let to = Double(Motion.coverScale(
      originX: origin.x, originY: origin.y, width: bounds.width, height: bounds.height,
      inscribedRadius: shape.inscribedRadius))
    let steps = Motion.sampleCount(duration: duration)
    let paths = Motion.scales(from: from, to: to, steps: steps, inverse: inverse).map { scale in
      Motion.maskPath(shape, origin: origin, scale: CGFloat(scale), bounds: bounds, inverse: inverse)
    }
    play(onEnd) {
      let mask = CAShapeLayer()
      mask.frame = bounds
      mask.fillRule = inverse ? .nonZero : .evenOdd
      mask.path = paths.last
      view.layer.mask = mask
      let animation = CAKeyframeAnimation(keyPath: "path")
      animation.values = paths
      animation.calculationMode = .linear
      animation.duration = duration
      mask.add(animation, forKey: "reveal")
    }
  }

  func wipe(_ direction: WipeDirection, duration: Double, onEnd: @escaping () -> Void) {
    let bounds = view.bounds
    let offset = Motion.wipeOffset(direction, size: bounds.size)
    play(onEnd) {
      let mask = CALayer()
      mask.frame = bounds
      mask.backgroundColor = UIColor.black.cgColor
      let start = mask.position
      let end = CGPoint(x: start.x + offset.x, y: start.y + offset.y)
      mask.position = end
      view.layer.mask = mask
      let animation = CABasicAnimation(keyPath: "position")
      animation.fromValue = NSValue(cgPoint: start)
      animation.toValue = NSValue(cgPoint: end)
      animation.duration = duration
      animation.timingFunction = Self.easing
      mask.add(animation, forKey: "wipe")
    }
  }

  func crossfade(duration: Double, onEnd: @escaping () -> Void) {
    play(onEnd) {
      view.layer.opacity = 0
      let animation = CABasicAnimation(keyPath: "opacity")
      animation.fromValue = 1
      animation.toValue = 0
      animation.duration = duration
      animation.timingFunction = Self.easing
      view.layer.add(animation, forKey: "crossfade")
    }
  }

  func remove() {
    playing += 1
    view.layer.mask?.removeAllAnimations()
    view.layer.removeAllAnimations()
    view.removeFromSuperview()
  }

  // Calls onEnd when the animation added in body ends. An earlier animation that body
  // replaces never calls its onEnd.
  private func play(_ onEnd: @escaping () -> Void, _ body: () -> Void) {
    playing += 1
    let ticket = playing
    view.layer.mask?.removeAllAnimations()
    view.layer.removeAllAnimations()
    view.layer.mask = nil
    view.layer.opacity = 1
    CATransaction.begin()
    CATransaction.setDisableActions(true)
    CATransaction.setCompletionBlock { [weak self] in
      guard let self, self.playing == ticket else { return }
      onEnd()
    }
    body()
    CATransaction.commit()
  }

  private static let easing = CAMediaTimingFunction(
    controlPoints: Float(Motion.easeX1), Float(Motion.easeY1), Float(Motion.easeX2), Float(Motion.easeY2))
}
