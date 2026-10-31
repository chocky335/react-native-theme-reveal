# Benchmark

This compares three ways to animate a theme switch, all in one release build of the example app. Results are in
`RESULTS.md`.

| id | Library | Switch |
|---|---|---|
| `ours` | `react-native-theme-reveal` (this package) | `switchTo(target, { transition: 'reveal' })`, origin from the press |
| `theme-switch-animation` | `react-native-theme-switch-animation` 0.8.0 | `switchTheme` with `type: 'circular'`, `startingPoint: { cx, cy }` from the press |
| `skia` | The Skia technique: `@shopify/react-native-skia` + `react-native-reanimated` | `makeImageFromView` of the screen root, snapshot drawn on a `Canvas` over the screen, new theme applied underneath, circular hole grown with `withTiming` |

All three libraries build and run.

## Fairness

- One build, one screen (`example/src/BenchmarkScreen.tsx`), the same themed rows for every library.
- One Run button and a library picker, so the press point and the circle origin are the same for every library.
- Every library gets the same duration (450 ms, `example/src/bench/shared.ts`), but the circle curves differ:
  - `ours` uses its default reveal curve: the scale grows as `from * (to / from) ^ (g ^ 1.4)` from a 24 dp
    starting height, with `g` linear in time.
  - `skia` grows the radius linearly from 0 (`Easing.linear`).
  - `react-native-theme-switch-animation` uses the Android `createCircularReveal` default interpolator and has no
    option to change it.
- Each run reopens the screen from a link (with a new React key), so every run switches light to dark on a fresh
  screen. Only the selected library's host is mounted. The Skia canvas mounts with the screen, 1.5 s before the
  press, so creating it isn't part of the run's cost.
- Runs are interleaved: every round runs each library once, and the starting library rotates from round to round.
  Each pass begins with one discarded warm-up run per library. The number of rounds in a pass is a multiple of the
  library count (6 for three libraries), so every library starts the same number of rounds. The table uses the
  median of all valid runs, with at least 5 per library.
- All animation scales (`animator_duration_scale`, `transition_animation_scale`, `window_animation_scale`) have to
  be 1. Any other scale affects the Android animators (ours, theme-switch-animation) but not Reanimated (Skia).

## Metrics

Every run logs a single line via `console.error` (which reaches logcat in release builds):

```
BENCH lib=<id> snapshotMs=<n> jsMaxGapMs=<n> totalMs=<n> durationMs=450 pressAt=<n> t0=<event|onPress>
      overlayAt=<n> endAt=<n> target=<theme> <transition=<type>|overlay=<0|1>>
BENCH lib=<id> pressAt=<n> t0=<event|onPress> error=<message>
```

Clock: `pressAt`, `overlayAt` and `endAt` are absolute times in ms.
- `pressAt` is the touch event's `nativeEvent.timestamp` (`t0=event`). On Android that's
  `MotionEvent.getEventTime()` on `CLOCK_MONOTONIC`, the same clock as the gfxinfo frame columns. The screen only
  uses it when `0 <= performance.now() - timestamp < 1000`, which proves `performance.now()` is on the same clock.
  If not, `pressAt` falls back to `performance.now()` at the start of `onPress` (`t0=onPress`) and the run doesn't
  count.
- `overlayAt`: the moment JS learns that the library's overlay is in the view tree. Same rule, three different
  hooks:
  - `ours`: when the package calls the adapter's `setTheme`, which happens after native `prepare` resolves true.
    `prepare` only resolves once it has added the snapshot view to the window (`example/src/bench/ours.ts`).
  - `theme-switch-animation`: when the library calls `switchThemeFunction`, which it does after `freezeScreen` has
    added the screenshot `ImageView` to the decor view and sent `FINISHED_FREEZING_SCREEN`
    (`example/src/bench/themeSwitchAnimation.ts`).
  - `skia`: when React has committed the canvas with the snapshot (a layout effect after `makeImageFromView`
    resolves, `example/src/bench/skia.tsx`). The adapter then waits two JS frames before setting the theme so the
    canvas gets to show the snapshot first. Setting the theme in the same commit made the new theme flash for one
    frame. The wait comes after `overlayAt`, so no metric includes it.
- `endAt`: end of the run in JS, which is `onSettled` for `ours` and the `withTiming` callback for `skia`.
  `theme-switch-animation` doesn't report an end to JS, so its run ends 700 ms (450 + 250) after
  `switchThemeFunction`. The frame window doesn't rely on `endAt` alone (see below).

Per run (`runresult.py` writes these into the run's JSON, and `report.py` shows the median over valid runs):
- `overlayFrameMs` (the main metric) is the time to the first frame with the overlay. `framestats.py` finds the
  first frame whose `IntendedVsync` is at or after `pressAt` and measures from `pressAt` to that frame's
  `SwapBuffersCompleted` (or its `FrameCompleted` if `SwapBuffersCompleted` is older than `IntendedVsync`).
  Nothing draws between the press and the overlay because the Run button has no pressed state, so that frame is
  the first one with the overlay. On the Mi 11 Lite a system trace (atrace, frames matched by
  `FrameTimelineVsyncId`) confirmed this for each library. Only the Mi was confirmed by trace. On the Pixel it
  rests on gfxinfo sync-phase agreement, since there's no atrace on Android 17. For `ours` and
  `theme-switch-animation` that frame uploads the full-window snapshot texture, and for `skia` it composites the
  canvas buffer with the snapshot. Why not use `overlayAt` directly? The first version of the rule took the first
  frame at or after `overlayAt`, but `theme-switch-animation` attaches its overlay natively about 40 ms before JS
  hears about it, so that rule picked its second drawn frame, about 88 ms late. `snapshotMs` keeps the JS moment
  as a secondary column. The Run button draws no press feedback (it's a `Pressable` with a fixed style: no ripple,
  no opacity, no pressed style), so a feedback frame can't sneak in first. A run whose first frame starts more
  than 150 ms before `overlayAt` doesn't count (`overlayLeadMs`, below), since that frame probably isn't the
  overlay. This check only catches gross errors, though: a first frame one interval too early (16.7 ms at 60 Hz)
  stays far below 150 ms.
- `overlayLeadMs`: how far ahead of `overlayAt` the overlay frame starts (`IntendedVsync`), negative if it starts
  after.
- `snapshotMs`: `overlayAt - pressAt`, the JS side of the same moment.
- `totalMs`: `endAt - pressAt`.
- `jsMaxGapMs`: how long the JS thread was blocked. A `requestAnimationFrame` loop runs from `onPress` to the end
  of the run and records the largest gap between two callbacks (the first gap starts at `onPress`). React Native
  drives `requestAnimationFrame` from the UI thread's Choreographer, so UI thread stalls show up here too.
- From `dumpsys gfxinfo <package> framestats` (`framestats.py`), over the transition window:
  - Rows come from every window of the app. Frames flagged `WindowVisibilityChanged` (1) or `SkippedFrame` (8) are
    skipped. `RTAnimation` frames (2) are kept, because `react-native-theme-switch-animation` animates with
    `createCircularReveal` on the RenderThread and all its animation frames carry that flag.
  - A frame ends at `FrameCompleted`. HWUI sometimes leaves a stale `FrameCompleted` that's older than
    `IntendedVsync`. On the last row of a window that means the frame hadn't finished when the dump ran, so the
    row is skipped. On any other row the frame did finish, so it ends at `SwapBuffersCompleted` instead.
  - Frame time = end - `IntendedVsync`. `p50`, `p90`: linear interpolation between the closest ranks.
  - The window starts at `pressAt` and ends when the frames do: after `endAt`, a frame still belongs to the window
    as long as its `IntendedVsync` is within 2.5 frame intervals of the previous frame (so one missed vsync plus
    jitter stays in).
  - `vsyncMs` (frame interval): the median gap between distinct `IntendedVsync` values in the window, i.e. the
    rate the app actually ran at. That can differ from the display mode: the Mi 11 Lite reports a 90 Hz mode while
    the example draws at 60 Hz (`vsyncMs` 16.6).
  - `janky`, `jankyShare`: frames longer than `vsyncMs` rounded to 0.1 ms, as a count and as a share of the
    window's frames.
  - `missedVsyncs`: whole frame intervals from `pressAt` to the `Vsync` (the actual start) of the first frame
    after the press.
  - `lateFrames`: frames whose `Vsync` lands half a frame interval or more after their `IntendedVsync`, meaning
    the UI thread was busy when the frame was due.

A run doesn't count (`valid: false`, reasons in `invalid`) if:
- it logged no BENCH line, or an `error=` line;
- an animation scale is not 1;
- the dump has no `---PROFILEDATA---`;
- `t0` is not `event`;
- `totalMs` is less than `durationMs`;
- `ours` settled with a transition other than `reveal` (`prepare` showed no overlay), or `skia` logged
  `overlay=0` (`makeImageFromView` returned null);
- no frame came at or after `overlayAt`;
- the first frame after the press starts more than 150 ms before `overlayAt`.

`report.py` lists the runs that didn't count under the table, along with why.

## Run it (Android)

Build and install the release APK of the example:

```sh
cd example
npm install
node node_modules/@shopify/react-native-skia/scripts/install-libs.js   # only when npm runs with ignore-scripts
npx expo prebuild --platform android --no-install
cd android && ./gradlew assembleRelease -PreactNativeArchitectures=arm64-v8a
adb install -r app/build/outputs/apk/release/app-release.apk
```

Run a pass of 6 rounds, then build the table:

```sh
benchmark/android.sh <serial> 6 ours theme-switch-animation skia
python3 benchmark/report.py benchmark/results/*.json
```

- `android.sh` writes each run's gfxinfo dump to `results/raw/<device>-<lib>-<round>.txt` and the run itself to
  `results/<device>-<lib>-<round>.json`, including its round, its position and the round's order. `<device>` is
  `ro.product.model`.
- Before the runs it compiles all of the app's code ahead of time with `cmd package compile -f -m speed`, and
  stops if the app isn't `speed` afterwards. All three libraries live in the one APK, so they all get the same
  compile. That means the bench doesn't measure what our Baseline Profile does. A Play install compiles only the
  code that profiles list, and this package is the only one here that ships a profile (there's none from
  `react-native-theme-switch-animation`, Skia, Reanimated, React Native or Expo).
- Older passes ran on whatever `adb install` left behind, which depends on a per-phone setting
  (`pm.dexopt.install`). On the Mi 11 Lite that's `speed-profile` with no profile, so the app ran as `verify` (no
  ahead-of-time code). On the Pixel 8 Pro it's `speed`, with all code compiled.
- It reads the three animation scales once per pass, records them in each run, and stops if any of them isn't 1.
- It gets the Run button position from the `BENCH-READY` log line, checks that the example has focus before
  tapping, and never presses BACK. Every adb call runs under a 120 s watchdog (`ADB_WATCHDOG`).
- By default it talks to the standard adb server. For a remote one, set `ADB_SERVER_SOCKET` plus a lease: either
  `BENCH_LEASE` (a helper that runs `<helper> <serial> <max-wait-s> -- <command>`) or `BENCH_LEASED=1` (you
  already hold the lease). If the file named by `BENCH_PAUSED_FILE` exists, the pass stops.
- `report.py` prints a row per device and library. `Runs` shows valid / all, every metric column is the median
  over valid runs, and `Overlay frame` is the column that decides pass/fail.

To measure the first switch in a fresh process (`results/cold/`), set `BENCH_COLD=1`. The pass then skips the
warm-up run and force-stops the app 1 s before each run.

```sh
BENCH_COLD=1 benchmark/android.sh <serial> 3 ours theme-switch-animation skia
python3 benchmark/report.py benchmark/results/cold/*.json
```

## Run it (iOS simulator)

Scripts can't tap on the simulator, so a launch argument drives the benchmark instead. Each id opens a fresh
benchmark screen 4 s after the previous one, and the screen starts its own run 1.5 s after layout, from the Run
button's centre (`t0=onPress`):

```sh
xcrun simctl launch <udid> expo.modules.themereveal.example -benchRuns 'ours theme-switch-animation skia'
xcrun simctl spawn <udid> log stream --style compact --predicate 'eventMessage CONTAINS "BENCH"'
```

The iOS pass only has the JS columns. I meant to add a `CADisplayLink` frame probe for the simulator but didn't
get to it. `xctrace` doesn't support hitches on the simulator, and without a physical iPhone the frames come from
the Mac GPU anyway, so they wouldn't reflect a phone's frame cost.

## Tests

```sh
python3 -I -m unittest discover -s benchmark -v
```

The tests use four dumps recorded on a Mi 11 Lite, stored in `fixtures/`.
