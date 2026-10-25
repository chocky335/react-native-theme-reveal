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

- Same APK on both phones, including the two `perf(android)` fixes below. One build per platform covers all three
  libraries. The `-benchRuns` launch argument (iOS only) came after the Android runs and doesn't change the
  Android code path.
- Every round runs each library once, and the starting library rotates, so each one starts two rounds.
- Every value is a median over the valid runs. Raw dumps and per-run JSON files are in `results/`.

## Android

```
python3 benchmark/report.py benchmark/results/*.json
```

| Device | Library | Runs | Overlay frame (ms) | Snapshot, JS (ms) | Frame p50 (ms) | Frame p90 (ms) | Janky (%) | Late frames | Missed vsyncs | JS max gap (ms) | Frame interval (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M2101K9AG | ours | 6/6 | 90.8 | 73.7 | 16.7 | 23.9 | 46 | 0 | 4 | 40.5 | 16.6 |
| M2101K9AG | theme-switch-animation | 6/6 | 81.0 | 71.6 | 17.9 | 21.9 | 68 | 1 | 3 | 42.0 | 16.8 |
| M2101K9AG | skia | 6/6 | 420.6 | 399.2 | 18.6 | 24.5 | 58 | 0 | 24.5 | 382.4 | 16.6 |
| Pixel-8-Pro | ours | 6/6 | 68.3 | 56.8 | 5.5 | 7.6 | 0 | 0 | 3.5 | 31.1 | 16.4 |
| Pixel-8-Pro | theme-switch-animation | 6/6 | 62.2 | 64.0 | 8.5 | 10.1 | 3 | 0.5 | 2.5 | 25.3 | 16.6 |
| Pixel-8-Pro | skia | 6/6 | 335.0 | 291.8 | 6.8 | 9.5 | 0 | 0 | 9.5 | 270.6 | 32.9 |

On React Native 0.86 (Fabric), `theme-switch-animation` shows no visible circle. Its second capture runs before
React has mounted the new theme, so it reveals the old screen over the old screen and the new theme appears all at
once at the end. Its overlay and frame numbers are still real, so it stays in the table and still counts for
pass/fail.

## Pass/fail

The rule: for every metric on each Android device, our median has to be no more than 5% above the best
alternative's median. Lower is better for all metrics.

| Device | Metric | ours | Best alternative | Limit (+5%) | Result |
|---|---|---|---|---|---|
| Mi 11 Lite | Overlay frame (ms) | 90.8 | 81.0 (theme-switch-animation) | 85.1 | FAIL |
| Mi 11 Lite | Snapshot, JS (ms) | 73.7 | 71.6 (theme-switch-animation) | 75.2 | PASS |
| Mi 11 Lite | Frame p50 (ms) | 16.7 | 17.9 (theme-switch-animation) | 18.8 | PASS |
| Mi 11 Lite | Frame p90 (ms) | 23.9 | 21.9 (theme-switch-animation) | 23.0 | FAIL |
| Mi 11 Lite | Janky (%) | 46 | 58 (skia) | 61 | PASS |
| Mi 11 Lite | Late frames | 0 | 0 (skia) | 0 | PASS |
| Mi 11 Lite | Missed vsyncs | 4 | 3 (theme-switch-animation) | 3.2 | FAIL |
| Mi 11 Lite | JS max gap (ms) | 40.5 | 42.0 (theme-switch-animation) | 44.1 | PASS |
| Pixel 8 Pro | Overlay frame (ms) | 68.3 | 62.2 (theme-switch-animation) | 65.3 | FAIL |
| Pixel 8 Pro | Snapshot, JS (ms) | 56.8 | 64.0 (theme-switch-animation) | 67.2 | PASS |
| Pixel 8 Pro | Frame p50 (ms) | 5.5 | 6.8 (skia) | 7.2 | PASS |
| Pixel 8 Pro | Frame p90 (ms) | 7.6 | 9.5 (skia) | 10.0 | PASS |
| Pixel 8 Pro | Janky (%) | 0 | 0 (skia) | 0 | PASS |
| Pixel 8 Pro | Late frames | 0 | 0 (skia) | 0 | PASS |
| Pixel 8 Pro | Missed vsyncs | 3.5 | 2.5 (theme-switch-animation) | 2.6 | FAIL |
| Pixel 8 Pro | JS max gap (ms) | 31.1 | 25.3 (theme-switch-animation) | 26.6 | FAIL |

Ours loses to `theme-switch-animation` on time to the first overlay frame on both phones (Mi +12%, Pixel +10%) and
on the metrics that follow from it (missed vsyncs: one frame interval). It also loses on frame p90 on the Mi (+9%)
and on JS max gap on the Pixel (+23%), and wins or ties everything else.

### Cause of the remaining loss

Mi 11 Lite (system trace, atrace, frames matched by `FrameTimelineVsyncId`):
- Ours reads the window back from the GPU with `PixelCopy`. That readback (`copySurfaceInto` on the RenderThread)
  takes 25-47 ms for a 1080 x 2400 window. A `simpleperf` cycle profile only finds about 3 ms of CPU cycles in it,
  so most of the time is spent waiting on the GPU.
- The overlay can't draw until the readback is done, and the readback often ends just after a vsync, so the
  overlay waits for the next one (up to 16.7 ms).
- `theme-switch-animation` draws the view tree in software into a bitmap on the React Native native-modules
  thread, off the main thread, and then attaches an `ImageView`. On the Mi that path gets to its first overlay
  frame about 10 ms sooner. Drawing in software from a background thread isn't thread-safe, though, and it misses
  hardware-only content (shadows that only a `RenderNode` draws, for example). Ours sticks with the pixel-exact
  GPU readback.

Pixel 8 Pro: Android 17 gives `atrace` no trace folder, so I couldn't take a system trace there. According to
`gfxinfo`, both libraries draw the overlay frame in about 5 ms, and our first overlay frame starts about one frame
later. `PixelCopy` is already asynchronous on API 34 and later, so the likely cause is the same readback latency.
Our JS max gap is one `requestAnimationFrame` interval longer (31 ms vs 25 ms, at 60 Hz). I haven't traced its
cause.

### Fixes in this pass (Mi 11 Lite, 6 rounds each)

| Pass | Overlay frame: ours / theme-switch-animation (ms) | Snapshot, JS: ours / theme-switch-animation (ms) | Missed vsyncs: ours / theme-switch-animation | JS max gap: ours / theme-switch-animation (ms) |
|---|---|---|---|---|
| Before the fixes | 118.3 / 90.0 (+31%) | 76.8 / 71.6 (+7%) | 5 / 4 | 50.5 / 51.0 |
| `perf(android): take the window snapshot off the main thread` | 109.3 / 90.6 (+21%) | 74.8 / 80.7 | 4.5 / 3.5 | 47.8 / 50.7 |
| `perf(android): upload the snapshot before the overlay's first frame` | 90.8 / 81.0 (+12%) | 73.7 / 71.6 | 4 / 3 | 40.5 / 42.0 |

1. On API 26-33, `PixelCopy.request` blocks the calling thread for the whole readback. Ours used to call it on the
   main thread, blocking it for 41-47 ms. After the readback, the main thread ran a late frame first and attached
   the overlay after the next vsync deadline. The request now runs on a background `HandlerThread` on API 26-33,
   while the listener and every failure still run on the main thread.
2. The overlay's first frame used to upload the 1080 x 2400 snapshot texture (3-7 ms on the RenderThread). Ours
   now calls `Bitmap.prepareToDraw()` before attaching the overlay, so the upload happens before that frame.

The theme mount (React Native mounting the new colours, 15-45 ms on the main thread on the Mi) is the same for all
three libraries, so it's not something the package changes.

## First switch in a fresh process

A real user's first switch doesn't get a warm-up, so each run below force-stops the app, opens the benchmark
screen and switches once. Median of 3 runs per library.

| Device | Library | Overlay frame (ms) | Snapshot, JS (ms) | JS max gap (ms) |
|---|---|---|---|---|
| Mi 11 Lite | ours | 123.2 | 100.4 | 113.1 |
| Mi 11 Lite | theme-switch-animation | 78.6 | 60.4 | 30.9 |
| Mi 11 Lite | skia | 464.8 | 428.8 | 382.5 |
| Pixel 8 Pro | ours | 116.0 | 100.4 | 33.0 |
| Pixel 8 Pro | theme-switch-animation | 88.8 | 67.3 | 34.8 |
| Pixel 8 Pro | skia | 404.1 | 332.5 | 292.7 |

The first switch costs ours 32 ms (Mi) and 48 ms (Pixel) more than later ones. For `theme-switch-animation` the
difference is -2 ms (Mi) and +27 ms (Pixel). Raw runs are in `results/cold/`.

## iOS simulator

These are from the iOS 26.5 simulator on a Mac, not a phone. The harness can't tap on the simulator, so the launch
argument `-benchRuns '<id> <id> ...'` opens a fresh benchmark screen per id and runs it from the Run button's
centre. That means `snapshotMs` starts at the run call (`t0=onPress`) rather than at a touch.

| Library | Snapshot, JS (ms), 6 runs | JS max gap (ms), 6 runs | Snapshot, JS (ms), first run in a fresh process (3 runs) |
|---|---|---|---|
| ours | 8.3 | 16.8 | 10.0 |
| theme-switch-animation | 11.8 | 16.9 | 14.7 |
| skia | 44.0 | 18.0 | 44.6 |

All three libraries build and run on iOS. There are no frame metrics: `xctrace` reports "Hitches is not supported
on this platform" for the simulator. The BENCH lines are in `results/ios-simulator/`.

## Notes on the metrics

- Overlay frame: the first frame after the press. A system trace on the Mi showed that this is the first frame
  with the overlay for every library. The earlier rule (first frame at or after the moment JS learns of the
  overlay) picked the next drawn frame of `theme-switch-animation`, about 90 ms late, because it attaches its
  overlay natively before JS hears about it. The README describes the rule.
- With the threshold at the frame interval, Janky (%) is high for every library on the Mi (46-68%). HWUI pipelines
  frames, so a frame that takes longer than one interval isn't necessarily dropped. Late frames and missed vsyncs
  are a better signal for real drops.
- The janky threshold is the interval each library drew at. On the Pixel, Skia drew at 30 Hz (32.9 ms), so its
  threshold doubled and its frame times cover half as many frames. That means its frame p50, p90 and janky values
  on the Pixel can't be compared with the other two.
- `theme-switch-animation` doesn't report an end to JS, so its run ends a fixed 700 ms after
  `switchThemeFunction`.
