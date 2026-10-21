import ExpoModulesCore
import UIKit

struct PointRecord: Record {
  @Field var x: Double = 0
  @Field var y: Double = 0
}

struct ShapeRecord: Record {
  @Field var path: String = ""
  @Field var anchor: PointRecord = PointRecord()
  @Field var inscribedRadius: Double = 0
}

struct TransitionRecord: Record {
  @Field var type: String = ""
  @Field var durationMs: Double = 0
  @Field var origin: PointRecord?
  @Field var shape: ShapeRecord?
  @Field var direction: String?
}

public class ThemeRevealModule: Module {
  private var overlay: RevealOverlay?
  // React root view a tapped view's origin is measured in (pageX / pageY).
  private weak var originRoot: UIView?
  private var started: Promise?
  // A snapshot that never gets start() would freeze the screen; drop it.
  private var failsafe: DispatchWorkItem?

  public func definition() -> ModuleDefinition {
    Name("ThemeReveal")

    AsyncFunction("prepare") { (viewTag: Int?) -> Bool in
      self.prepare(viewTag)
    }.runOnQueue(.main)

    AsyncFunction("start") { (transition: TransitionRecord, promise: Promise) in
      self.start(transition, promise)
    }.runOnQueue(.main)

    AsyncFunction("cancel") {
      self.removeOverlay()
    }.runOnQueue(.main)

    OnDestroy {
      if Thread.isMainThread {
        self.removeOverlay()
      } else {
        DispatchQueue.main.async { self.removeOverlay() }
      }
    }
  }

  private func prepare(_ viewTag: Int?) -> Bool {
    removeOverlay()
    let active = UIApplication.shared.applicationState == .active
    guard Motion.canAnimate(active: active, reduceMotion: UIAccessibility.isReduceMotionEnabled),
          let window = Self.keyWindow(),
          let next = RevealOverlay(window: window) else {
      return false
    }
    overlay = next
    originRoot = viewTag.flatMap { findView($0) }.flatMap(Self.reactRoot(of:))
    let item = DispatchWorkItem { [weak self] in self?.removeOverlay() }
    failsafe = item
    DispatchQueue.main.asyncAfter(deadline: .now() + Self.failsafeSeconds, execute: item)
    return true
  }

  private func start(_ transition: TransitionRecord, _ promise: Promise) {
    guard let current = overlay else {
      promise.resolve()
      return
    }
    failsafe?.cancel()
    failsafe = nil
    started?.resolve()
    started = promise
    let duration = transition.durationMs / 1000
    guard let window = current.view.window, duration.isFinite, duration > 0,
          Motion.sameSize(current.size, window.bounds.size) else {
      removeOverlay()
      return
    }
    let onEnd = { [weak self, weak current] in
      guard let self, let current, self.overlay === current else { return }
      self.removeOverlay()
    }
    if !play(current, transition, duration, onEnd) {
      removeOverlay()
    }
  }

  private func play(
    _ current: RevealOverlay, _ transition: TransitionRecord, _ duration: Double, _ onEnd: @escaping () -> Void
  ) -> Bool {
    switch transition.type {
    case "reveal", "inverseReveal":
      guard let origin = transition.origin, origin.x.isFinite, origin.y.isFinite else { return false }
      let point = toOverlay(CGPoint(x: origin.x, y: origin.y), current)
      current.reveal(
        Self.shape(of: transition.shape), origin: point, inverse: transition.type == "inverseReveal",
        duration: duration, onEnd: onEnd)
    case "wipe":
      guard let direction = transition.direction.flatMap(WipeDirection.init(rawValue:)) else { return false }
      current.wipe(direction, duration: duration, onEnd: onEnd)
    case "crossfade":
      current.crossfade(duration: duration, onEnd: onEnd)
    default:
      return false
    }
    return true
  }

  private func removeOverlay() {
    failsafe?.cancel()
    failsafe = nil
    let pending = started
    started = nil
    let current = overlay
    overlay = nil
    originRoot = nil
    current?.remove()
    pending?.resolve()
  }

  // Origin is relative to the tapped view's React root, or in window points if there's none.
  private func toOverlay(_ point: CGPoint, _ current: RevealOverlay) -> CGPoint {
    if let root = originRoot, root.window != nil {
      return root.convert(point, to: current.view)
    }
    return current.view.convert(point, from: nil)
  }

  private func findView(_ viewTag: Int) -> UIView? {
    appContext?.findView(withTag: viewTag, ofType: UIView.self)
  }

  private static func keyWindow() -> UIWindow? {
    UIApplication.shared.connectedScenes
      .compactMap { $0 as? UIWindowScene }
      .flatMap { $0.windows }
      .first { $0.isKeyWindow }
  }

  // React Native measures pageX / pageY relative to the view its touch handler is attached
  // to: the surface view, or a presented <Modal>'s view.
  private static func reactRoot(of view: UIView) -> UIView? {
    guard let handler: AnyClass = NSClassFromString("RCTSurfaceTouchHandler") else { return nil }
    var candidate: UIView? = view
    while let current = candidate {
      if current.gestureRecognizers?.contains(where: { $0.isKind(of: handler) }) == true {
        return current
      }
      candidate = current.superview
    }
    return nil
  }

  private static func shape(of record: ShapeRecord?) -> RevealShape {
    guard let record else { return .circle }
    return RevealShape.resolve(
      path: record.path,
      anchor: CGPoint(x: record.anchor.x, y: record.anchor.y),
      inscribedRadius: CGFloat(record.inscribedRadius))
  }

  private static let failsafeSeconds = 1.0
}
