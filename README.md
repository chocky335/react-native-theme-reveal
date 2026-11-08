# react-native-theme-reveal

[![npm](https://img.shields.io/npm/v/react-native-theme-reveal)](https://www.npmjs.com/package/react-native-theme-reveal)
[![CI](https://github.com/chocky335/react-native-theme-reveal/actions/workflows/ci.yml/badge.svg)](https://github.com/chocky335/react-native-theme-reveal/actions/workflows/ci.yml)
[![license](https://img.shields.io/npm/l/react-native-theme-reveal)](LICENSE)

Animated theme switches for React Native, done with a native snapshot of the window: a reveal from the touch
point, an inverse reveal, a wipe or a crossfade.

<p align="center">
  <a href="docs/media/hero-star-reveal.mp4"><img src="docs/media/hero-star-reveal.gif" width="300" alt="A star-shaped reveal grows from the touch point and shows the light theme over the dark one"></a>
</p>

Right before your app applies the new theme, the package snapshots the window and lays the snapshot over your app.
The theme changes underneath, then the snapshot animates away. Your components render the new theme once, and
nothing has to interpolate colours per component.

- Expo module, New Architecture only. Bare React Native apps can use it through `install-expo-modules`, but I
  haven't verified that setup.
- Android 8.0+ (API 26) and iOS 16.4+.
- No runtime JS dependencies besides React, React Native and Expo.

## Install

Requirements:
- Expo SDK 57 or newer (React Native 0.86 or newer), New Architecture.
- iOS: deployment target 16.4 or higher.
- Android: installs on API 24 and up, but the animation needs API 26. On API 24 and 25 the theme switches without
  animating.

In an Expo project:

```sh
npx expo install react-native-theme-reveal
npx expo prebuild
```

There's native code in here, so it won't run in Expo Go. Use a development build (`npx expo run:android`,
`npx expo run:ios`).

In a bare React Native project, set up Expo modules first (I haven't verified this setup):

```sh
npx install-expo-modules@latest
npm install react-native-theme-reveal
cd ios && pod install
```

## Quick start

```tsx
import { useRef, useSyncExternalStore } from 'react'
import { Pressable, Text, View } from 'react-native'
import type { GestureResponderEvent } from 'react-native'
import { createThemeReveal, originFromPress } from 'react-native-theme-reveal'

type Theme = 'light' | 'dark'

// getTheme has to read the theme synchronously, hence a small store outside React.
let theme: Theme = 'light'
const listeners = new Set<() => void>()
const themeStore = {
  get: () => theme,
  set: (next: Theme) => {
    theme = next
    listeners.forEach((listener) => listener())
  },
  subscribe: (listener: () => void) => {
    listeners.add(listener)
    return () => {
      listeners.delete(listener)
    }
  },
}

// One instance per app, at module scope.
const reveal = createThemeReveal<Theme>({ getTheme: themeStore.get, setTheme: themeStore.set })

export default function App() {
  const current = useSyncExternalStore(themeStore.subscribe, themeStore.get)
  reveal.useStart() // once, near the root
  const buttonRef = useRef<View>(null)

  const onPress = async (event: GestureResponderEvent) => {
    const origin = await originFromPress(event, buttonRef.current) // the ref is the fallback
    if (origin) reveal.setOrigin(origin.point, origin.viewTag)
    await reveal.switchTo(themeStore.get() === 'light' ? 'dark' : 'light')
  }

  return (
    <View style={{ flex: 1, backgroundColor: current === 'light' ? '#fff' : '#111' }}>
      <Pressable ref={buttonRef} onPress={onPress}>
        <Text style={{ color: current === 'light' ? '#111' : '#fff' }}>Switch theme</Text>
      </Pressable>
    </View>
  )
}
```

- `getTheme` has to return the current theme synchronously. React state lags behind, so keep the theme somewhere
  you can read right away, like the store above.
- `setTheme` has to update what React renders in the same tick, which the store above does.
- Create the instance at module scope, like above. Creating it during render trips the React hooks lint rules and
  the React Compiler.
- `originFromPress` reads the touch point from the press event. Presses from a keyboard, switch control or screen
  reader don't have one, so it measures the view you pass and uses its centre instead. That fallback is covered by
  unit tests, but I haven't checked a TalkBack or VoiceOver activation on a real device.
- The default transition is `reveal` when there's an origin and `crossfade` when there isn't.

The example app in `example/` has every transition, a modal, the keyboard case and the benchmark screen.

## Snapshot on press-in

By default `switchTo` takes the snapshot when the press ends. With `reveal.pressCapture` the package takes it when
the press starts instead, so the snapshot is ready by the release and the animation starts sooner. Spread it on the
`Pressable` whose `onPress` calls `switchTo`:

```tsx
<Pressable ref={buttonRef} {...reveal.pressCapture} onPress={onPress}>
```

- `onPressIn` takes the snapshot and keeps it off screen, so the screen stays live while the finger is down.
- `onPress` calls `switchTo`, which puts the held snapshot on screen right away.
- A press that ends without `onPress` (the finger slid off the button, or a scroll took over) only gets
  `onPressOut`, which frees the snapshot. It does that in a later macrotask, because on release React Native calls
  `onPressOut` either before `onPress` in the same dispatch or, for a short press, up to 130 ms after it. A late
  `onPressOut` from an earlier press leaves the next press's snapshot alone.
- In `onPress`, call `switchTo` before any `await` that waits on native work. Otherwise the deferred `onPressOut`
  can free the snapshot first, and the switch takes a new one at release as if you weren't using `pressCapture`.
  `await originFromPress(event)` is fine when the event has a touch point.
- The press-in point becomes the origin for the next switch, like `setOrigin`. A later `setOrigin` call wins.
- Every press-in costs a full-window snapshot, even when the touch turns into a scroll, so don't use
  `pressCapture` on buttons inside a scrolling list. On Android the press-in snapshot takes the same path as a
  snapshot at release (see [How it works on Android](#how-it-works-on-android)):
  - API 29 and up, the GPU snapshot: a window-size hardware bitmap (about 10 MB of GPU memory at 1080 x 2400) for
    as long as the snapshot is held, and 2-8 ms on the main thread. It's ready 10-30 ms after the capture starts on
    a Mi 11 Lite and a Pixel 8 Pro.
  - API 26 to 28, `PixelCopy`: a window-size bitmap (about 10 MB) and 25-47 ms of GPU readback on a Mi 11 Lite.
    The bitmap is the one the warm-up keeps, and it goes back there if no switch uses the snapshot.

The catch is that the snapshot shows the screen at press-in, not at release. In pixels, that means:
- The button shows up the way it looked just before the press, with no press feedback. React Native draws the
  pressed style and the ripple in a frame after `onPressIn`, so the snapshot doesn't have them. At release the
  feedback goes away at once, hidden under the snapshot.
- Without `pressCapture`, the snapshot at release shows the pressed look, so the old screen keeps the press
  feedback until the animation uncovers it.
- Anything that moved during the press (a clock, a running animation, a progress bar) shows as it was at press-in.
  The snapshot is one press old.

I checked this on a Mi 11 Lite and a Pixel 8 Pro with a button that has a pressed background colour and
`android_ripple`, using both the `PixelCopy` snapshot and the GPU snapshot. The first overlay frame matched the idle
button with `pressCapture` and the pressed button without it, and outside the reveal hole the press-in snapshot
matched the screen at press-in pixel for pixel. The screen stays live during the hold: with a 300 ms hold, the
pressed colour and the ripple stayed visible until the release on both phones. iOS takes its snapshot the same way
(`afterScreenUpdates: false`), but I couldn't check the look there because the simulator can't hold a press.

If you need the exact release frame, skip `pressCapture`.

The switch falls back to a new snapshot at release when:
- no switch used the held snapshot within 3 s. The package then frees it, or hands it back as the kept bitmap (see
  [Warm-up](#warm-up)), so a long press doesn't hold on to a second full-window bitmap (about 10 MB at 1080 x 2400
  on Android). The press-in point stays the origin.
- the window changed size, or the switch comes from another window (on Android, another modal).
- the app went to the background, `switchTo` got the current theme, or a newer press-in replaced the snapshot.
- another switch started while the press-in snapshot was being taken, so the snapshot could show the screen from
  before that switch. That holds even when the other switch ended without an animation.
- on iOS, a modal was presented during the press, or finished closing during it.
- a switch animation was on screen at press-in. The package takes no snapshot then, since it would capture that
  animation's current frame.

On Android, if the release comes before the press-in snapshot is ready, the switch waits for it and shows it. It
doesn't take a second one.

iOS has the same API. There the package takes the key window's `snapshotView(afterScreenUpdates: false)` at
press-in and adds it to the window at release. That snapshot is cheap on iOS anyway, so the gain is small. iOS
ignores the view tag for the press-in snapshot, because it always snapshots the key window. Modals open in that
same window, so the package also compares the window's top presented view controller. If a modal was presented
during the press, or finished closing during it, the switch takes a new snapshot. A modal that's still closing at
release counts as presented, so the switch uses the press-in snapshot. That snapshot shows the modal where it was
at press-in, even though it's partway through its closing animation by then.

## Transitions

| | |
|---|---|
| <a href="docs/media/reveal.mp4"><img src="docs/media/reveal.gif" width="220" alt="Reveal"></a> | `reveal`: a hole opens in the old screen at the origin and grows to show the new theme. It's a circle, or a shape you supply. Default 450 ms. |
| <a href="docs/media/inverse-reveal.mp4"><img src="docs/media/inverse-reveal.gif" width="220" alt="Inverse reveal"></a> | `inverseReveal`: the old screen stays visible only inside the shape, and the shape shrinks into the origin. This clip uses the star shape. Default 450 ms. |
| <a href="docs/media/wipe.mp4"><img src="docs/media/wipe.gif" width="220" alt="Wipe up"></a> | `wipe`: a straight edge sweeps across the screen and uncovers the new theme. `direction` is the way the edge travels, so with `up` the edge moves up and the new theme comes in from the bottom. Defaults to `up` and 400 ms. |
| <a href="docs/media/crossfade.mp4"><img src="docs/media/crossfade.gif" width="220" alt="Crossfade"></a> | `crossfade`: the old screen fades out. Default 300 ms. |

The clips are screen recordings of the example app (release build) on a Pixel 8 Pro, cut to 30 fps with the status
bar cropped out. Click one to get the MP4.

Motion:
- `reveal` grows the shape from 24 pt tall to whatever scale covers the farthest window corner, following
  `scale(g) = from * (to / from) ^ (g ^ 1.4)` with `g` linear in time. `inverseReveal` runs the same curve
  backwards.
- `wipe` and `crossfade` use a cubic ease in-out (`0.65, 0, 0.35, 1`).

## API

```ts
import {
  createThemeReveal,
  originFromPress,
  resolveTransition,
  ThemeRevealModule,
} from 'react-native-theme-reveal'
```

### `createThemeReveal<T>(options): ThemeReveal<T>`

Create one instance per app, at module scope (outside render).

| Option | Type | Default | |
|---|---|---|---|
| `getTheme` | `() => T` | required | Returns the current theme. Must be synchronous. |
| `setTheme` | `(theme: T) => void` | required | Applies the theme. Called while the snapshot is on screen, and has to update what React renders in the same tick (see below). |
| `transition` | `'reveal' \| 'inverseReveal' \| 'wipe' \| 'crossfade'` | `reveal` with an origin, else `crossfade` | App-wide default. |
| `shape` | `Shape` | a circle | Default shape for `reveal` and `inverseReveal`. |
| `wipeDirection` | `'up' \| 'down' \| 'left' \| 'right'` | `'up'` | Default direction for `wipe`. |
| `durations` | `Partial<Record<TransitionType, number>>` | 450 / 450 / 400 / 300 ms | Per transition type, in ms. Anything non-finite, 0 or below, or over 10 000 falls back to the default. |
| `onError` | `(error: unknown) => void` | `console.warn` | Receives native errors. The theme still applies if the snapshot fails. |
| `onSettled` | `(info: SettledInfo) => void` | none | Called when a switch finishes, with `{ transition, capture, prepareMs, totalMs }`. |
| `warmUp` | `boolean` | `true` | Prepares the native snapshot path when `useStart` mounts. On API 29 and up that's the GPU snapshot, below that it keeps one window-size bitmap. See [Warm-up](#warm-up). |

- `setTheme` has to update what React renders in the same tick. Don't `await` anything before the update and don't
  wrap it in `startTransition`. If the update is deferred, the animation runs over the old theme and the new one
  appears all at once afterwards.
- If `setTheme` throws, the package removes the snapshot and releases the held theme, and `switchTo` rejects with
  that error. That switch gets no `onSettled` call.
- In `onSettled`, `transition` is `null` when no snapshot was taken (the theme switched without animating). If a
  snapshot was taken, `transition` is the type, even when native ended up showing no animation (say, Android's
  animator duration scale is 0).
- `capture` says how the snapshot was taken: `'gpu'` (API 29 and up, see
  [How it works on Android](#how-it-works-on-android)), `'pixelcopy'` (Android readback) or `'view'` (iOS
  `snapshotView`). It's `null` when no snapshot was taken.
- If starting the animation fails, `onError` runs first, then `onSettled`.
- Same-theme calls get no `onSettled`. A call that a newer call replaced before its snapshot was taken gets no
  `onSettled` either, but one replaced mid-animation still does.
- If `onError` or `onSettled` throws, the error goes to `console.warn` and the theme still applies.

### `reveal.setOrigin(point, viewTag?)`

Sets the origin for the next switch only. `point` is in `pageX` / `pageY` space and `viewTag` is the tapped view.
`originFromPress` returns both. Call it from the press handler, before `switchTo`.
- Android: `viewTag` tells the package which window to snapshot (see [Modals](#modals)).
- iOS: `viewTag` identifies the React root that `point` is measured in. Inside a `pageSheet` or `formSheet` modal
  that root isn't at the top of the window, so pass the tag there. Without a tag, `point` is treated as window
  points.

### `reveal.switchTo(theme, options?): Promise<void>`

Switches to `theme` with an animation.

| Option | Type | |
|---|---|---|
| `transition` | `TransitionType` | This switch only. |
| `shape` | `Shape` | This switch only. |
| `wipeDirection` | `WipeDirection` | This switch only. |

- The most specific choice wins: the `switchTo` option, then the factory option, then the default.
- `reveal` and `inverseReveal` need an origin, and fall back to `crossfade` without one.
- If `theme` is already current, `switchTo` removes any snapshot that's on screen, frees a press-in snapshot and
  returns.
- The newest `switchTo` call wins. An older call still waiting for its snapshot does nothing.
- If the snapshot fails, `setTheme` still runs without animation. There's one case on Android API 29 to 33 where
  it waits instead, see [How it works on Android](#how-it-works-on-android).
- The promise resolves once the theme is set, before the animation ends. Use `onSettled` if you need the end.
- It only rejects if `getTheme` or `setTheme` throws.

### `reveal.pressCapture`

`{ onPressIn(event), onPressOut() }` for a `Pressable`. See [Snapshot on press-in](#snapshot-on-press-in).
- `onPressIn` calls `capture` with the touch point (`pageX`, `pageY`) and `event.nativeEvent.target`. If the event
  has no finite touch point, it does nothing and the switch takes its snapshot at release.
- `onPressOut` frees the press-in snapshot in a later macrotask, if no `switchTo` has used it by then. Pass it the
  press event (`Pressable` does). An `onPressOut` whose touch is older than the snapshot's press-in is ignored.

### `reveal.capture(point, viewTag?)`

Takes the snapshot now and keeps it off screen for the next `switchTo`. It also sets `point` and `viewTag` as the
origin for the next switch, like `setOrigin`.
- A second `capture` replaces the first.
- If no switch uses the snapshot within 3 s, the package frees it. The point stays the origin until the press ends.
- If the native capture fails, the error goes to `onError` and the next switch takes its snapshot as usual.

### `reveal.dropCapture()`

Frees the press-in snapshot. It also clears the origin that `capture` set, unless `setOrigin` set a newer one.

### `reveal.useStart()`

Hook that starts the animation once React has committed the new theme. Mount it once, in a component that
re-renders when the theme changes (the root is a good spot). The first time it mounts, it also warms up the native
side (see [Warm-up](#warm-up)).

### `reveal.useHeldTheme(): T | null`

Hook that returns the previous theme while an animation is running, and `null` otherwise. Use it for anything
outside the snapshot that needs to keep the old look until the animation ends, like the system bar style:

```tsx
const held = reveal.useHeldTheme()
const barTheme = held ?? theme

return <StatusBar barStyle={barTheme === 'light' ? 'dark-content' : 'light-content'} />
```

The system bars stay live above the snapshot, so if you switch their style right away, they change before the
animation has shown the new theme.

### `originFromPress(event, fallbackView?): Promise<PressOrigin | null>`

Returns `{ point, viewTag }` for a press event, where `point` is the touch point (`pageX`, `pageY`) and `viewTag`
is `event.nativeEvent.target`. If the event has no finite touch point, it measures `fallbackView` with
`measureInWindow` and returns its centre, or `null` if you didn't pass a `fallbackView`.

Call it synchronously in the press handler, before any `await`, and pass a view that's mounted. React Native never
calls back `measureInWindow` for an unmounted view, so the promise would never settle.

### `resolveTransition(input): Transition`

Pure function that picks the transition, shape, direction and duration for a switch. `switchTo` uses it
internally, and it's exported for tests and custom flows.

### `ThemeRevealModule`

The native module, with `prepare(viewTag?)`, `start(transition)`, `cancel()`, `warmUp()`, `capture(viewTag?)` and
`dropCapture()`. `createThemeReveal` calls it for you, so normally you won't need it. `prepare` resolves to the
`CapturePath`, or `null` when it shows no snapshot.

### Types

```ts
type Point = { x: number; y: number }
type Shape = { path: string; anchor: Point; inscribedRadius: number }
type WipeDirection = 'up' | 'down' | 'left' | 'right'
type Transition =
  | { type: 'reveal'; origin: Point; durationMs: number; shape?: Shape }
  | { type: 'inverseReveal'; origin: Point; durationMs: number; shape?: Shape }
  | { type: 'wipe'; direction: WipeDirection; durationMs: number }
  | { type: 'crossfade'; durationMs: number }
type TransitionType = Transition['type']
type CapturePath = 'gpu' | 'pixelcopy' | 'view'
type SettledInfo = {
  transition: TransitionType | null
  capture: CapturePath | null
  prepareMs: number
  totalMs: number
}
type SwitchOptions = { transition?: TransitionType; shape?: Shape; wipeDirection?: WipeDirection }
type PressOrigin = { point: Point; viewTag?: number }
type PressCapture = { onPressIn(event: GestureResponderEvent): void; onPressOut(event?: GestureResponderEvent): void }
type MeasurableView = {
  measureInWindow(callback: (x: number, y: number, width: number, height: number) => void): void
}
type ResolveTransitionInput = {
  requested?: TransitionType // the switchTo option
  fallback?: TransitionType // the factory option
  origin: Point | null
  shape?: Shape
  durations?: Partial<Record<TransitionType, number>>
  wipeDirection?: WipeDirection
  requestedShape?: Shape
  requestedWipeDirection?: WipeDirection
}
```

`ThemeRevealOptions<T>` is the options object for `createThemeReveal` (the table above) and `ThemeReveal<T>` is
what it returns. All of these types are exported.

## Shape format

A shape is SVG path data plus two numbers that position and scale it, all in path units.

```ts
const STAR: Shape = {
  path:
    'M50 0 L62.93 32.2 L97.55 34.55 L70.92 56.8 L79.39 90.45 L50 72 ' +
    'L20.61 90.45 L29.08 56.8 L2.45 34.55 L37.07 32.2 Z',
  anchor: { x: 50, y: 50 },
  inscribedRadius: 21.99,
}

const onPress = async (event: GestureResponderEvent) => {
  const origin = await originFromPress(event)
  if (origin) reveal.setOrigin(origin.point, origin.viewTag) // a reveal with no origin falls back to crossfade
  await reveal.switchTo('dark', { transition: 'reveal', shape: STAR })
}
```

- `path`: SVG path data, filled with the nonzero rule on both platforms.
- `anchor`: the point in the shape that lands on the origin.
- `inscribedRadius`: distance from the anchor to the nearest edge of the shape. The package uses it to work out
  the scale at which the shape covers the whole window, so use a value at or below the real distance. Too large a
  value makes the final scale too small, and whatever the shape missed snaps to the new theme at the end.
- Separate the arc flags (`a9 9 0 0 1 18 0`, not `a9 9 0 0118 0`). Android's parser can't split compact flags,
  although iOS reads them fine.
- If the path doesn't parse, the package falls back to the circle and logs a warning naming the path. On Android,
  a path with compact arc flags ends up as the circle.

## How it works on Android

How the package takes the snapshot depends on the API level.

On API 29 and up it draws the window's views again, on the GPU, into a hardware bitmap. No layout and no JS run for
it, and elevation shadows use the same light as the window. No pixels make the trip from the GPU to the CPU and
back.

On API 26 to 28 it reads the window back from the GPU with `PixelCopy` and then uploads it again. API 29 and up
falls back to `PixelCopy` too when:
- a window uses wide color mode, since the GPU snapshot is sRGB,
- a window has no hardware acceleration,
- the theme has no light values for shadows (`lightY`, `lightZ`, `lightRadius`, `ambientShadowAlpha`,
  `spotShadowAlpha`),
- a view change is waiting for its frame while force dark can apply: a light theme in night mode, with
  `forceDarkAllowed` or, from API 36, the system's color inversion,
- the GPU snapshot fails. If no snapshot comes within 250 ms, or the GPU snapshot throws, the app sticks with
  `PixelCopy` until its process ends. The exception is a slow warm-up: if the 250 ms run out while the warm-up
  still holds the copy thread, only that switch uses `PixelCopy` and the next one tries the GPU snapshot again. If
  the warm-up is still holding the thread at a later switch, that switch goes straight to `PixelCopy` without
  waiting the 250 ms.

A warm-up that never ends is a problem on API 29 to 33, because there the `PixelCopy` request runs on the copy
thread too. The `PixelCopy` snapshot then waits behind the warm-up, and so do the switch and `setTheme`. I haven't
measured this, since no test made the warm-up hang. On API 34 and up the `PixelCopy` request runs on the main
thread and doesn't wait. The package also frees each GPU snapshot's renderer on the copy thread, so if that thread
hangs, those renderers stay in memory until the process ends.

I compared the GPU snapshot with `PixelCopy` on a Mi 11 Lite (API 33) and a Pixel 8 Pro (API 37). On the home
screen, the benchmark screen, a screen with elevation shadows and a full-screen modal, not a single pixel differs.
With a transparent modal and a dim, 0.6% to 0.7% of the pixels differ by 1 or 2 out of 255 in a channel, at
translucent edges like text under the dim or the edge of the card's shadow. The GPU blends the dim and the dialog
in one pass, while `PixelCopy` reads back each window and the CPU blends them, so the rounding comes out
differently. You can't see it.

The GPU snapshot has a few other quirks:
- A view that changed since the window's last frame draws again first, so the snapshot can show the change one
  frame before the screen does. Any window with such a change then redraws in full, so the change can't go
  missing on screen. That's true during a press-in hold too.
- The TalkBack focus rectangle isn't in it, because the window draws that rectangle outside its views.
- A screenshot that draws the window into a software canvas (`View.draw`) while the overlay is up leaves the
  overlay out, because a software canvas can't draw a hardware bitmap.
- I haven't checked a `WebView`, a `TextureView` or a `SurfaceView`, and no test compared the GPU snapshot with
  the screen for them. The GPU snapshot draws the views in its own `HardwareRenderer`. A `WebView` draws through a
  functor in that renderer, and a `SurfaceView` gets that renderer's frame number for its position. If your app
  shows one of these, compare a snapshot with the screen.

The `PixelCopy` snapshot matches the screen exactly for the activity window. Under a transparent modal's dim on a
Pixel 8 Pro, 186 single pixels at text edges were off by up to 5 out of 255 in a channel (2%), from the CPU blend
of the dim and the dialog. The press-in snapshot has its own difference, see
[Snapshot on press-in](#snapshot-on-press-in).

Each switch reports which path it took in `onSettled`, as `capture`: `'gpu'` or `'pixelcopy'`.

The overlay lives in the `ViewOverlay` of the window's root view, in the activity window or, for a switch from a
modal, the dialog window. It draws above every view of that window, including any view the app or the system adds
during the animation. It takes no touches and is hidden from accessibility.

### Circle reveal on the RenderThread

On API 29 and up, the default circle (`reveal` or `inverseReveal` with no `shape`) animates on the RenderThread with
`ViewAnimationUtils.createCircularReveal`, so the UI thread doesn't redraw the overlay every frame.
- `reveal`: the overlay holds still while the next frame draws the new theme. In the frame after that, the package
  takes a second GPU snapshot of the same window or windows, this time without the overlay. That second snapshot
  goes on top of the first, and a circular clip on it grows from the origin.
- `inverseReveal`: a circular clip on the first snapshot shrinks into the origin. There's no second snapshot.
- Both follow the same radius curve as the UI-thread reveal (see [Transitions](#transitions)).

For `reveal`, this changes what you see:
- Inside the circle you get a still image of the new screen for the length of the reveal, where before it showed
  the live app. An animation or a video in your app won't move inside the circle until the reveal ends.
- The circle starts 2 to 3 frames after `start`, once the second snapshot is in. The reveal keeps its duration,
  so it also ends that much later.

The UI-thread reveal still runs:
- on API 26 to 28,
- when the first snapshot came from `PixelCopy` (see the list above),
- when the transition has a custom `shape`,
- for one switch, when the second snapshot doesn't come: no frame commit, no result within 200 ms of `start`, a
  failed GPU capture, or a result for another window, size or position. The UI-thread reveal then starts at that
  point, with the full duration. A failed second snapshot doesn't turn the GPU snapshot off,
- for one switch, when the RenderThread animator doesn't start,
- for every later `reveal` until the app process ends, once 3 `reveal` switches in a row have fallen back while
  the window stayed open. Each fallback can cost a switch up to 200 ms. `inverseReveal` keeps the RenderThread,
  because it takes no second snapshot.

`wipe` and `crossfade` always run on the UI thread.

During a press-in hold on API 29 and up, the package also draws the held GPU snapshot once, scaled down, into a
small offscreen target. That moves the upload of its texture out of the overlay's first frame.

## Modals

A switch from inside a React Native `<Modal>` animates too.

- Android: a modal is its own dialog window. Pass the tapped view's tag (`setOrigin(point, viewTag)`, which
  `originFromPress` gives you) and the package snapshots the activity window, the dim behind the dialog and the
  dialog itself, then puts the overlay in the dialog window. Without a `viewTag` it uses whichever window has
  focus.
- iOS: a modal is in the same window, so the snapshot includes it.

## Reduce Motion and backgrounding

- iOS: with Reduce Motion on, the theme switches without animating.
- Android: with the animator duration scale at 0 (Remove animations), the theme switches without animating.
- The app has to be in the foreground. If it's paused or in the background, a switch applies the theme without
  animating.
- If a snapshot never gets its animation start, it's removed after 1 s, so it can't get stuck on screen.
- A press-in snapshot is freed when the app goes to the background.

## Warm-up

The first switch in a process used to pay for work that later switches skip. To move that out of the way, with
`warmUp: true` (the default) `useStart()` calls the native `warmUp` once per instance, the first time it mounts.
On Android the warm-up starts the snapshot copy thread, off the main thread, and then prepares whichever path the
activity window's first switch will take (see [How it works on Android](#how-it-works-on-android)).

On API 29 and up (the GPU snapshot), the warm-up makes a 1 x 1 GPU snapshot of nothing on the copy thread: an
`ImageReader`, a `HardwareRenderer` and one drawn frame, which it then frees. It draws no view, so the window isn't
touched, and it keeps no bitmap, because each GPU snapshot makes its own hardware bitmap and frees it with the
snapshot. A switch that falls back to `PixelCopy` keeps its bitmap, the same as on API 26 to 28.

Then, 500 ms after the mount and once the main thread is next idle, it makes one capture target the size of the
activity window on the copy thread: an `ImageReader`, a `HardwareRenderer` with its surface, and one allocated
buffer. It holds on to that target until a snapshot of the activity window uses it, so that snapshot skips the
setup. I haven't measured what the kept target gains on its own. The package makes the next target the same way,
500 ms after a switch ends or after a press-in snapshot that no switch used. It waits while an overlay shows, a
snapshot runs or a press-in snapshot is held, and it tries again when the activity comes back to the foreground.
A switch that starts while a target is being made waits for it: its snapshot gets its image after that work ends,
11.5-23.8 ms on a Mi 11 Lite. The warm-up also loads most of the package's classes on its own thread, so the first
switch doesn't load them on the main thread.
- What it gains, on the first switch in a fresh process (3 runs each): on a Pixel 8 Pro (API 37) the main thread
  spent 18.2-19.0 ms on the snapshot without the warm-up and 2.9-5.7 ms with it, and the first `ImageReader` in the
  process accounted for 13.4-14.7 ms of that. The snapshot was ready 23.8-28.2 ms after the switch started without
  the warm-up and 10.3-12.9 ms with it. On a Mi 11 Lite (API 33) the first snapshot was already fast (3.9-7.1 ms
  on the main thread), and I couldn't measure any change from the warm-up.
- What it costs: 30-44 ms on the copy thread at mount on the Pixel 8 Pro and 4 ms on the Mi 11 Lite for the 1 x 1
  snapshot. Each kept target adds 11.5-23.8 ms on the copy thread and 4.4-18.7 ms on the RenderThread on the Mi 11
  Lite (from a system trace).
- The RenderThread draws your app's frames too, so on a slow phone one app frame can come late while the package
  makes a target: about 0.5 s after the mount and about 0.5 s after each switch. That only matters if your app is
  animating right then. The benchmark can't see it, because its screen sits still after a switch.
- Memory: one window-size GPU buffer between switches, with a started `HardwareRenderer` and its surface. The
  buffer is 4 bytes per pixel, so about 10 MB at 1080x2400, and the package keeps one at most. I haven't checked it
  with `dumpsys meminfo`.
- The package frees the kept target when:
  - the app goes to the background (`onTrimMemory` at `TRIM_MEMORY_UI_HIDDEN` or above). Nothing is kept until the
    next snapshot, and the package keeps a new target after that switch.
  - the system is low on memory.
  - the screen size changes.
  - at the next prepare, the activity window has a different size or its snapshot no longer takes the GPU path.
    If the GPU path is on, the package then makes a new target that fits.
  - the activity is destroyed.
  - the GPU snapshot turns itself off.
  - the module is destroyed (a JS reload).
- A snapshot of a window with a different size frees the target and makes its own. The free runs on the copy
  thread before that snapshot's image gets there, so the image can end up waiting for it.

On API 26 to 28 (`PixelCopy`), for an API 29+ activity window that takes `PixelCopy` (see
[How it works on Android](#how-it-works-on-android)), and in a process where the GPU snapshot switched itself off
before the warm-up ran, the warm-up allocates one bitmap the size of the window on the copy thread. Then it copies
the window into that bitmap once and keeps it. The first copy into a fresh bitmap is the slow one, so this way the
first switch gets a fast copy.
- Every `PixelCopy` snapshot copies into the kept bitmap and gets it back when the snapshot goes away, and so does
  a press-in snapshot that no switch used. Inside a modal, the copy of the dialog window still allocates a new
  bitmap each time.
- What it costs: one ARGB bitmap the size of the window, held while the app runs. At 4 bytes per pixel that's
  about 10 MB at 1080x2400. The one copy at mount takes the RenderThread once, for 16-26 ms on a Mi 11 Lite and
  39-60 ms on a Pixel 8 Pro (measured before those phones moved to the GPU snapshot). If your app animates in its
  first frames, one of them can come late. GPU memory didn't grow: on a Pixel 8 Pro, `dumpsys meminfo` showed
  100 MB of Graphics with and without the kept bitmap. I haven't measured other GPUs.
- The bitmap is freed when the app goes to the background (`onTrimMemory` at `TRIM_MEMORY_UI_HIDDEN` or above),
  on low memory, when the screen size changes and when the activity is destroyed. Nothing is kept while the app is
  in the background, and the next switch keeps a new bitmap.

If you have several instances, the warm-up runs as long as any of them has `warmUp: true`. With `warmUp: false`
there's no warm-up at all (no thread start, no GPU snapshot of nothing, no copy, no kept bitmap and no kept capture
target), and every snapshot allocates its own bitmap and its own target.

On iOS `warmUp` does nothing, since the snapshot there has no thread or bitmap to prepare. If the warm-up fails,
the error goes to `onError` and switches keep working.

### Baseline Profile

The Android library ships a Baseline Profile (`android/src/main/baseline-prof.txt`) with all of its classes and
methods in it. The Android Gradle Plugin packs it into the library's AAR and merges it into the app's
`assets/dexopt/baseline.prof`, so a Play install compiles that code ahead of time and the first switch doesn't run
it in the interpreter.

A local `adb install` doesn't apply profiles. To get the same result as Play, add `androidx.profileinstaller` 1.4.0
or later to the app (1.3.x does nothing on Android 15 and later) and run:

```sh
adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE <package>/androidx.profileinstaller.ProfileInstallReceiver
adb shell am force-stop <package>
adb shell cmd package compile -f -m speed-profile <package>
```

The benchmark in [`benchmark/`](benchmark/) compiles all of the app's code ahead of time for every library, so it
doesn't show what this profile does for a Play install.

## Limits

- Android 8.0+ (API 26). Below that the theme still switches, just without animation.
- New Architecture only.
- The keyboard isn't in the snapshot. On iOS it changes theme immediately, and on Android it keeps its own theme.
- Starting a second switch mid-animation ends the first one immediately. Android shows one frame of the end state.
  On iOS the second snapshot captures the first animation's current frame, and the second animation starts from
  that.
- Android modal: tapping while the modal is still sliding in shows about 2 frames of live content on top of it.
- Android modal: with stacked modals, only the target dialog ends up in the snapshot.
- Android modal: if the dialog window is smaller than the screen, the strip outside it stays live.
- Closing a modal in the same handler that switches ("close on select"). Switch once the modal has closed.
  - Android: the switch doesn't animate.
  - iOS: the reveal plays over a snapshot that still shows the modal.
- To follow the system theme, listen to `Appearance` and call `switchTo` without an origin, which gives you a
  `crossfade`. The example app does this.

## Testing

Under `jest-expo` the package's native module mock loads automatically, and `switchTo` applies the theme
immediately without animating.

## Performance

Measured on 2026-11-08 with release builds, in two full passes (A and B). The tables below are from pass B.
Since then the code has only changed in the docs, code comments, debug log text and the module's destroy, and no
benchmark run takes a path whose behaviour those change. The method, raw dumps and scripts live in
[`benchmark/`](benchmark/), and the full tables of both passes, the history and the notes are in
[`benchmark/RESULTS.md`](benchmark/RESULTS.md).

In this build, on API 29 and up, the circle reveal runs on the RenderThread from a second snapshot (see
[Circle reveal on the RenderThread](#circle-reveal-on-the-renderthread)), so the inside of the circle is a still
image of the new screen for the reveal's 450 ms. The UI-thread reveal still runs on API 26 to 28, for a
`PixelCopy` snapshot, for a custom shape, and for one switch when the second snapshot fails.

Everything runs in one build of the example app, on the same screen, with a 450 ms circle reveal:
- `ours`: this package.
- `theme-switch-animation`: `react-native-theme-switch-animation` 0.8.0.
- `skia`: the Skia technique, `@shopify/react-native-skia` `makeImageFromView` with a circle mask animated by
  Reanimated.

| Device | OS | Build | Runs per library |
|---|---|---|---|
| Xiaomi Mi 11 Lite (`M2101K9AG`) | Android 13 (API 33) | release, `arm64-v8a`, compiled with `speed` | warm: 1 warm-up + 6 interleaved rounds. Cold: 3 rounds |
| Pixel 8 Pro | Android 17 (API 37) | release, `arm64-v8a`, compiled with `speed` | warm: 1 warm-up + 6 interleaved rounds. Cold: 3 rounds |

A few things about how these were run:
- Before each pass the benchmark compiles the whole app ahead of time (`cmd package compile -f -m speed`), so all
  three libraries run compiled code. That means it doesn't show what the [Baseline Profile](#baseline-profile)
  does for a Play install.
- Each pass runs with two kinds of tap, the same for every library. `input tap` holds the press for about 0 ms,
  which no finger does, so there's also a hold form that presses for 100 ms (`input swipe x y x y 100`). The clock
  starts at the release (`onPress`) in both. Ours takes its snapshot on press-in
  ([Snapshot on press-in](#snapshot-on-press-in)), so the hold form is closer to what a finger gets.
- Cold means the first switch in a fresh process: the app is force-stopped 1 s before each run. The screen mounts
  about 1.5 s before the tap, so the warm-up has finished by then. If your app's first switch comes sooner, it
  won't look like this.
- Every `ours` run took the GPU snapshot (`capture=gpu`). The result files don't record a fallback to the
  UI-thread reveal, but debug passes with the debug log on saw none: one on the Mi 11 Lite with this build, and
  one on the Pixel 8 Pro with an earlier build of the same design.
- Both phones drew at 60 Hz, except Skia on the Pixel 8 Pro, which drew at 30 Hz.

Warm, `input tap` (median of 6). Lower is better in every column.

| Device | Library | Overlay frame (ms) | Snapshot, JS (ms) | Frame p50 (ms) | Frame p90 (ms) | Janky (%) | Late frames | Missed vsyncs | JS max gap (ms) |
|---|---|---|---|---|---|---|---|---|---|
| Mi 11 Lite | ours | 57.4 | 47.2 | 15.3 | 20.6 | 38 | 0 | 2 | 43.0 |
| Mi 11 Lite | theme-switch-animation | 88.7 | 73.2 | 17.7 | 24.0 | 67 | 0.5 | 3.5 | 45.0 |
| Mi 11 Lite | skia | 431.9 | 414.0 | 18.9 | 22.8 | 67 | 0 | 25 | 391.1 |
| Pixel 8 Pro | ours | 49.4 | 45.9 | 7.7 | 9.3 | 0 | 0 | 2 | 25.4 |
| Pixel 8 Pro | theme-switch-animation | 64.9 | 66.2 | 8.2 | 9.5 | 3 | 0 | 3 | 32.2 |
| Pixel 8 Pro | skia | 330.8 | 291.4 | 6.9 (1) | 8.9 (1) | 0 (1) | 0 (1) | 9.5 (1) | 267.9 |

Overlay frame and snapshot JS in all four forms, ours against `theme-switch-animation` (ms):

| Device | Form | Overlay frame: ours | Overlay frame: theme-switch-animation | Snapshot, JS: ours | Snapshot, JS: theme-switch-animation |
|---|---|---|---|---|---|
| Mi 11 Lite | Warm tap | 57.4 | 88.7 | 47.2 | 73.2 |
| Mi 11 Lite | Warm hold | 41.0 | 94.2 | 31.6 | 80.2 |
| Mi 11 Lite | Cold tap | 65.5 | 88.0 | 58.0 | 84.1 |
| Mi 11 Lite | Cold hold | 43.0 | 107.7 | 33.9 | 67.6 |
| Pixel 8 Pro | Warm tap | 49.4 | 64.9 | 45.9 | 66.2 |
| Pixel 8 Pro | Warm hold | 39.1 | 64.5 | 31.7 | 64.0 |
| Pixel 8 Pro | Cold tap | 53.8 | 89.5 | 47.5 | 87.6 |
| Pixel 8 Pro | Cold hold | 39.1 | 102.2 | 32.2 | 65.3 |

(1) Skia drew at 30 Hz on the Pixel 8 Pro, so its frame times, janky share, late frames and missed vsyncs there
can't be compared with the other two. Its missed vsyncs are counted in 33 ms intervals, so 9.5 is about 19 at
60 Hz. That's why the release rule leaves Skia out of the Pixel 8 Pro frame rows. With Skia in them, 7 more rows
would fail in pass B: frame p50 in all four forms, frame p90 in cold tap, and janky share in cold tap and cold
hold.

- Overlay frame: time from the release to the first frame with the overlay on screen. On the Mi 11 Lite a system
  trace confirmed which frame that is. On the Pixel 8 Pro it rests on `gfxinfo` data alone.
- Snapshot, JS: time from the release until JS knows the overlay is up.
- JS max gap: longest gap between two `requestAnimationFrame` callbacks during the switch.
- On React Native 0.86 (Fabric), `theme-switch-animation` shows no visible circle: it reveals the old screen over
  the old screen, and the new theme appears all at once at the end. Its timings are still real.

### Release rule

On each Android device, each of our medians must be no more than 5% above the best alternative's median. That's
16 rows per form, 64 in all. Pass A passes all 64 and pass B passes 63, and the overlay frame and snapshot JS pass
in every form on both phones in both passes.

The one row that fails is the Mi 11 Lite JS max gap in pass B's warm hold: 44.1 ms vs 39.7 ms for
`theme-switch-animation` (limit 41.7 ms). A system trace of 6 runs on the same build shows this package caused
none of the max gaps. On the Mi, our JS max gap per run is either fast (25.4-31.8 ms) or slow (41.8-51.2 ms) in 22
of 24 runs. In a slow run the system kept the app on the little CPU cores, and the app's own JS theme render took
46-53 ms there. `theme-switch-animation` renders about 50 ms later, after its software capture has pushed the CPU
clock up. Across the four full passes of this design, our median in this row was 35.0, 34.8, 37.8 and 44.1 ms,
against 42.2, 39.4, 39.8 and 39.7 ms. The only levers are a CPU boost and a later theme render. A boost games the
metric and a later render makes the switch slower, so the package does neither.

An earlier build of this design failed 5 cold tap rows in one of its two passes. This build keeps a capture target
between switches, and the cold tap rows pass in both passes. [`benchmark/RESULTS.md`](benchmark/RESULTS.md) has
the details.

### iOS (simulator)

These numbers are from the iOS 26.5 simulator on a Mac, not a phone, measured on 2026-10-28 before the press-in
snapshot existed. I haven't measured iOS again since. There are no frame metrics, because `xctrace` doesn't support
hitches on the simulator and this pass has no `CADisplayLink` frame probe.

| Library | Snapshot, JS (ms) | JS max gap (ms) |
|---|---|---|
| ours | 8.3 | 16.8 |
| theme-switch-animation | 11.8 | 16.9 |
| skia | 44.0 | 18.0 |

The benchmark script can't tap on the simulator, so on iOS "Snapshot, JS" is measured from the run call instead of
a touch.

## License

MIT
