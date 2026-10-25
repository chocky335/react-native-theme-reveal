# Benchmark results

Measured on 2026-10-25. See `benchmark/README.md` for what each metric means and the run rules.

## Setup

| | Mi 11 Lite | Pixel 8 Pro | iOS simulator |
|---|---|---|---|
| Model | `M2101K9AG` | `Pixel 8 Pro` | iPhone 17 Pro simulator on a Mac |
| OS | Android 13 (API 33), MIUI | Android 17 (API 37) | iOS 26.5 |
| Screen | 1080 x 2400 | 1008 x 2244 | - |
| Rate the app drew at | 60 Hz (16.6 ms) | 60 Hz (16.4-16.6 ms), Skia 30 Hz | - |
| Build | release, `arm64-v8a` | release, `arm64-v8a` | Release, simulator |
| Runs per library | 1 warm-up + 6 interleaved rounds, 6/6 valid | 1 warm-up + 6 interleaved rounds, 6/6 valid | 1 warm-up + 6 interleaved rounds |
| Animation scales | 1.0 / 1.0 / 1.0 | 1.0 / 1.0 / 1.0 | - |

- Same APK on both phones, including the three `perf(android)` fixes below. One build per platform covers all
  three libraries.
- Every round runs each library once, and the starting library rotates, so each one starts two rounds.
- Every value is a median over the valid runs, and no run was left out. Raw dumps and per-run JSON files are in
  `results/`.

## Android

```
python3 benchmark/report.py benchmark/results/*.json
```

| Device | Library | Runs | Overlay frame (ms) | Snapshot, JS (ms) | Frame p50 (ms) | Frame p90 (ms) | Janky (%) | Late frames | Missed vsyncs | JS max gap (ms) | Frame interval (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M2101K9AG | ours | 6/6 | 84.8 | 72.6 | 16.5 | 23.2 | 47 | 1 | 4 | 39.0 | 16.6 |
| M2101K9AG | theme-switch-animation | 6/6 | 91.4 | 80.4 | 17.2 | 22.6 | 61 | 1 | 4 | 37.0 | 16.8 |
| M2101K9AG | skia | 6/6 | 432.9 | 400.5 | 18.4 | 25.0 | 68 | 1 | 25 | 374.9 | 16.6 |
| Pixel-8-Pro | ours | 6/6 | 72.3 | 60.1 | 5.6 | 8.0 | 0 | 0 | 4 | 28.0 | 16.4 |
| Pixel-8-Pro | theme-switch-animation | 6/6 | 62.8 | 58.1 | 8.3 | 9.6 | 5 | 1 | 3 | 25.1 | 16.6 |
| Pixel-8-Pro | skia | 6/6 | 313.4 | 285.4 | 7.1 | 9.7 | 0 | 0 | 9 | 265.7 | 32.9 |

On React Native 0.86 (Fabric), `theme-switch-animation` shows no visible circle. Its second capture runs before
React has mounted the new theme, so it reveals the old screen over the old screen and the new theme appears all at
once at the end. Its overlay and frame numbers are still real, so it stays in the table and still counts for
pass/fail.

## Pass/fail

The rule: for every metric on each Android device, our median has to be no more than 5% above the best
alternative's median. Lower is better for all metrics.

| Device | Metric | ours | Best alternative | Limit (+5%) | Result |
|---|---|---|---|---|---|
| Mi 11 Lite | Overlay frame (ms) | 84.8 | 91.4 (theme-switch-animation) | 96.0 | PASS |
| Mi 11 Lite | Snapshot, JS (ms) | 72.6 | 80.4 (theme-switch-animation) | 84.4 | PASS |
| Mi 11 Lite | Frame p50 (ms) | 16.5 | 17.2 (theme-switch-animation) | 18.1 | PASS |
| Mi 11 Lite | Frame p90 (ms) | 23.2 | 22.6 (theme-switch-animation) | 23.7 | PASS |
| Mi 11 Lite | Janky (%) | 47 | 61 (theme-switch-animation) | 64 | PASS |
| Mi 11 Lite | Late frames | 1 | 1 (skia) | 1.1 | PASS |
| Mi 11 Lite | Missed vsyncs | 4 | 4 (theme-switch-animation) | 4.2 | PASS |
| Mi 11 Lite | JS max gap (ms) | 39.0 | 37.0 (theme-switch-animation) | 38.9 | FAIL |
| Pixel 8 Pro | Overlay frame (ms) | 72.3 | 62.8 (theme-switch-animation) | 66.0 | FAIL |
| Pixel 8 Pro | Snapshot, JS (ms) | 60.1 | 58.1 (theme-switch-animation) | 61.1 | PASS |
| Pixel 8 Pro | Frame p50 (ms) | 5.6 | 7.1 (skia) | 7.5 | PASS |
| Pixel 8 Pro | Frame p90 (ms) | 8.0 | 9.6 (theme-switch-animation) | 10.1 | PASS |
| Pixel 8 Pro | Janky (%) | 0 | 0 (skia) | 0 | PASS |
| Pixel 8 Pro | Late frames | 0 | 0 (skia) | 0 | PASS |
| Pixel 8 Pro | Missed vsyncs | 4 | 3 (theme-switch-animation) | 3.2 | FAIL |
| Pixel 8 Pro | JS max gap (ms) | 28.0 | 25.1 (theme-switch-animation) | 26.4 | FAIL |

- Mi 11 Lite: ours wins the overlay frame by 7%. It fails one metric, the JS max gap: 39.0 ms against a limit of
  38.9 ms (+5.4%). Between passes this metric moved by more than that (ours 40.5 / 45.2 / 39.0 ms,
  theme-switch-animation 42.0 / 44.1 / 37.0 ms).
- Pixel 8 Pro: ours loses to `theme-switch-animation` on time to the first overlay frame (+15%), missed vsyncs
  (one frame interval) and JS max gap (+12%), and wins or ties everything else.

### Cause of the remaining loss

- Ours reads the window back from the GPU with `PixelCopy`. On the Mi that readback (`copySurfaceInto` on the
  RenderThread) takes 25-47 ms for a 1080 x 2400 window. A `simpleperf` cycle profile only finds about 3 ms of CPU
  cycles in it, so most of the time is spent waiting on the GPU.
- `theme-switch-animation` draws the view tree in software into a bitmap on the React Native native-modules
  thread, off the main thread, and then attaches an `ImageView`. That draw isn't thread-safe, though, and it
  misses hardware-only content (shadows that only a `RenderNode` draws, for example). Ours sticks with the
  pixel-exact GPU readback.
- Pixel 8 Pro: I couldn't take a system trace (see below). According to `gfxinfo`, both libraries draw the overlay
  frame in about 5 ms, and our first overlay frame starts about one frame later. `PixelCopy` is already
  asynchronous on API 34 and later, so the likely cause is the same readback latency, up against a fast software
  draw on the Tensor G3. I haven't traced the cause of the longer JS max gap.

### Fixes in this pass

Mi 11 Lite, 6 rounds each (ours / theme-switch-animation):

| Pass | Overlay frame (ms) | Snapshot, JS (ms) | Missed vsyncs | JS max gap (ms) |
|---|---|---|---|---|
| Before the fixes | 118.3 / 90.0 | 76.8 / 71.6 | 5 / 4 | 50.5 / 51.0 |
| 1. `perf(android): take the window snapshot off the main thread` | 109.3 / 90.6 | 74.8 / 80.7 | 4.5 / 3.5 | 47.8 / 50.7 |
| 2. `perf(android): upload the snapshot before the overlay's first frame` | 90.8 / 81.0 | 73.7 / 71.6 | 4 / 3 | 40.5 / 42.0 |
| 3. Trial build: listener at the front of the main queue on every API level | 77.7 / 90.2 | 64.1 / 70.4 | 3 / 4 | 45.2 / 44.1 |
| 3. `perf(android): attach the overlay before a pending frame on API 26-33` (final) | 84.8 / 91.4 | 72.6 / 80.4 | 4 / 4 | 39.0 / 37.0 |

Pixel 8 Pro, overlay frame (ours / theme-switch-animation): 68.3 / 62.2 ms after fix 2, 70.5 / 61.4 ms with the
trial build, and 72.3 / 62.8 ms final. Fix 3 didn't help on API 34+, so it's limited to API 26-33.

1. On API 26-33, `PixelCopy.request` blocks the calling thread for the whole readback. Ours used to call it on the
   main thread, blocking it for 41-47 ms. The request now runs on a background `HandlerThread`.
2. The overlay's first frame used to upload the 1080 x 2400 snapshot texture (3-7 ms on the RenderThread). Ours
   now calls `Bitmap.prepareToDraw()` before attaching the overlay, so the upload happens before that frame. This
   one came from measurement: the traces after fix 1 still showed the upload in that frame.
3. The readback often finishes right as a vsync arrives. The main thread used to run that vsync's frame first and
   attach the overlay afterwards, so the overlay waited an extra frame. On API 26-33 the listener now runs on the
   background thread and posts the result to the front of the main queue (`postAtFrontOfQueue`), so the overlay
   makes it into the pending frame. The result itself still runs on the main thread.

The theme mount (React Native mounting the new colours, 15-45 ms on the main thread on the Mi) is the same for all
three libraries, so it's not something the package changes.

## First switch in a fresh process

A real user's first switch doesn't get a warm-up, so each run below force-stops the app, opens the benchmark
screen and switches once. Median of 3 runs per library.

| Device | Library | Overlay frame (ms) | Snapshot, JS (ms) | JS max gap (ms) |
|---|---|---|---|---|
| Mi 11 Lite | ours | 89.0 | 68.8 | 93.9 |
| Mi 11 Lite | theme-switch-animation | 123.1 | 98.1 | 41.2 |
| Mi 11 Lite | skia | 451.2 | 401.0 | 363.9 |
| Pixel 8 Pro | ours | 121.2 | 100.5 | 36.1 |
| Pixel 8 Pro | theme-switch-animation | 96.7 | 71.7 | 29.2 |
| Pixel 8 Pro | skia | 367.8 | 305.8 | 282.8 |

With only 3 runs these numbers are noisy: before fix 3, the same measurement gave theme-switch-animation 78.6 ms
on the Mi. On the first switch, our JS max gap on the Mi is 2.4 times its warm value. Raw runs are in
`results/cold/`.

## iOS simulator

These are from the iOS 26.5 simulator on a Mac, not a phone. The harness can't tap on the simulator, so the launch
argument `-benchRuns '<id> <id> ...'` opens a fresh benchmark screen per id and runs it from the Run button's
centre. That means `snapshotMs` starts at the run call (`t0=onPress`) rather than at a touch. The package's iOS
code didn't change in this pass.

| Library | Snapshot, JS (ms), 6 runs | JS max gap (ms), 6 runs | Snapshot, JS (ms), first run in a fresh process (3 runs) |
|---|---|---|---|
| ours | 8.3 | 16.8 | 10.0 |
| theme-switch-animation | 11.8 | 16.9 | 14.7 |
| skia | 44.0 | 18.0 | 44.6 |

All three libraries build and run on iOS. There are no frame metrics: `xctrace` reports "Hitches is not supported
on this platform" for the simulator. The BENCH lines are in `results/ios-simulator/`.

## Notes on the metrics

- Overlay frame: the first frame after the press. On the Mi a system trace (atrace, frames matched by
  `FrameTimelineVsyncId`) showed that this is the first frame with the overlay for every library: ours and
  `theme-switch-animation` upload the full-window snapshot texture in it, and Skia composites its canvas buffer.
  The earlier rule (first frame at or after the JS overlay time) picked the second drawn frame of
  `theme-switch-animation`, about 88 ms late.
- Pixel 8 Pro: No system trace was possible. `atrace` reports "Did not find trace folder", and a Perfetto session
  got no ftrace events. The `gfxinfo` data agrees with the Mi. For ours and Skia the first frame starts 5-36 ms
  after the JS overlay time on every run, so both rules pick the same frame. For `theme-switch-animation` it
  starts 1-3 ms before it, and its sync phase is 1.4-2.6 ms vs 0.2-0.5 ms for the other two, which fits the upload
  of its bitmap.
- No run had its first frame more than 150 ms before the JS overlay time (`overlayLeadMs`, largest 19.2 ms).
- With the threshold at the frame interval, Janky (%) is high for every library on the Mi (47-68%). HWUI pipelines
  frames, so a frame that takes longer than one interval isn't necessarily dropped. Late frames and missed vsyncs
  are a better signal for real drops.
- The janky threshold is the interval each library drew at. On the Pixel, Skia drew at 30 Hz (32.9 ms), so its
  threshold doubled and its frame times cover half as many frames. That means its frame p50, p90 and janky values
  on the Pixel can't be compared with the other two.
- `theme-switch-animation` doesn't report an end to JS, so its run ends a fixed 700 ms after
  `switchThemeFunction`.
