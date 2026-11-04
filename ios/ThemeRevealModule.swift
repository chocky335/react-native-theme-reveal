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

// Snapshot from capture(), kept out of the window until prepare() shows it. presented is the
// window's top view controller at capture time, which a modal presented or dismissed since then
// changes.
private struct HeldCapture {
  let view: UIView
  weak var window: UIWindow?
  weak var presented: UIViewController?
  let takenAt: CFTimeInterval
}

public class ThemeRevealModule: Module {
  private var overlay: RevealOverlay?
  private var held: HeldCapture?
  private var captureExpiry: DispatchWorkItem?
  // Offset, in the overlay, of the React root a tapped view's origin is measured in (pageX /
  // pageY). Read at snapshot time like on Android, so a modal that moves or closes before start
  // doesn't shift the origin.
  private var originOffset: CGPoint?
  private var started: Promise?
  // A snapshot that never gets start() would freeze the screen; drop it.
  private var failsafe: DispatchWorkItem?

  public func definition() -> ModuleDefinition {
    Name("ThemeReveal")

    // The capture path ("view": snapshotView), or nil when no overlay shows.
    AsyncFunction("prepare") { (viewTag: Int?) -> String? in
      self.prepare(viewTag)
    }.runOnQueue(.main)

    AsyncFunction("start") { (transition: TransitionRecord, promise: Promise) in
      self.start(transition, promise)
    }.runOnQueue(.main)

    // prepare only snapshots the key window, so the view tag isn't needed here.
    AsyncFunction("capture") { (_: Int?) -> Bool in
      self.capture()
    }.runOnQueue(.main)

    AsyncFunction("dropCapture") {
      self.dropCapture()
    }.runOnQueue(.main)

    AsyncFunction("cancel") {
      self.dropCapture()
      self.removeOverlay()
    }.runOnQueue(.main)

    // No-op on iOS: snapshotView(afterScreenUpdates:) keeps no thread or bitmap a warm-up could start early. It's
    // here so the JS call resolves on both platforms.
    AsyncFunction("warmUp") {}

    OnAppEntersBackground {
      Self.onMain { self.dropCapture() }
    }

    OnDestroy {
      Self.onMain {
        self.dropCapture()
        self.removeOverlay()
      }
    }
  }

  private static func onMain(_ body: @escaping () -> Void) {
    if Thread.isMainThread {
      body()
    } else {
      DispatchQueue.main.async(execute: body)
    }
  }

  // Takes the snapshot now and keeps it out of the window, so the screen stays live while
  // the finger is down.
  private func capture() -> Bool {
    dropCapture()
    let active = UIApplication.shared.applicationState == .active
    // An overlay on screen would end up in the snapshot, so the switch snapshots after it instead.
    guard overlay == nil,
          Motion.canAnimate(active: active, reduceMotion: UIAccessibility.isReduceMotionEnabled),
          let window = Self.keyWindow(),
          let view = RevealOverlay.snapshot(of: window) else {
      return false
    }
    held = HeldCapture(
      view: view, window: window, presented: Self.topPresented(in: window), takenAt: CACurrentMediaTime())
    let item = DispatchWorkItem { [weak self] in self?.dropCapture() }
    captureExpiry = item
    DispatchQueue.main.asyncAfter(deadline: .now() + Motion.captureTimeoutSeconds, execute: item)
    return true
  }

  private func dropCapture() {
    captureExpiry?.cancel()
    captureExpiry = nil
    held = nil
  }

  private func prepare(_ viewTag: Int?) -> String? {
    removeOverlay()
    let kept = held
    dropCapture()
    let active = UIApplication.shared.applicationState == .active
    guard Motion.canAnimate(active: active, reduceMotion: UIAccessibility.isReduceMotionEnabled),
          let window = Self.keyWindow(),
          let next = Self.overlay(on: window, kept: kept) else {
      return nil
    }
    overlay = next
    originOffset = viewTag.flatMap { findView($0) }.flatMap(Self.reactRoot(of:)).flatMap { root in
      root.window == nil ? nil : root.convert(CGPoint.zero, to: next.view)
    }
    let item = DispatchWorkItem { [weak self] in self?.removeOverlay() }
    failsafe = item
    DispatchQueue.main.asyncAfter(deadline: .now() + Self.failsafeSeconds, execute: item)
    return "view"
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
    guard let window = current.view.window, let duration = Motion.durationSeconds(ms: transition.durationMs),
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
    originOffset = nil
    current?.remove()
    pending?.resolve()
  }

  // Origin is relative to the tapped view's React root, or in window points if there's none.
  private func toOverlay(_ point: CGPoint, _ current: RevealOverlay) -> CGPoint {
    if let offset = originOffset {
      return CGPoint(x: point.x + offset.x, y: point.y + offset.y)
    }
    return current.view.convert(point, from: nil)
  }

  // The held capture if it still shows this window at its size, otherwise a snapshot taken
  // now.
  private static func overlay(on window: UIWindow, kept: HeldCapture?) -> RevealOverlay? {
    if let kept, Motion.canUseCapture(
      sameWindow: kept.window === window, samePresentation: kept.presented === topPresented(in: window),
      captured: kept.view.frame.size, current: window.bounds.size,
      ageSeconds: CACurrentMediaTime() - kept.takenAt) {
      return RevealOverlay(snapshot: kept.view, window: window)
    }
    return RevealOverlay(window: window)
  }

  // A React Native <Modal> presents a view controller in the same window.
  private static func topPresented(in window: UIWindow) -> UIViewController? {
    var top = window.rootViewController
    while let next = top?.presentedViewController {
      top = next
    }
    return top
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
    guard let shape = RevealShape.parse(
      path: record.path,
      anchor: CGPoint(x: record.anchor.x, y: record.anchor.y),
      inscribedRadius: CGFloat(record.inscribedRadius)) else {
      log.warn("ThemeReveal: cannot parse the shape path \"\(record.path)\", using the circle")
      return .circle
    }
    return shape
  }

  private static let failsafeSeconds = 1.0
}
