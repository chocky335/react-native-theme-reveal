# Benchmark results

Measured on 2026-10-31 with the warm-up build: the `warmUp` option, bitmap reuse and the new `start` parser (see
"What changed"). The 2026-10-28 numbers are under "Earlier results". See `benchmark/README.md` for what each
metric means and the run rules.

## Setup

| | Mi 11 Lite | Pixel 8 Pro | iOS simulator |
|---|---|---|---|
| Model | `M2101K9AG` | `Pixel 8 Pro` | iPhone 17 Pro simulator on a Mac |
| OS | Android 13 (API 33), MIUI | Android 17 (API 37) | iOS 26.5 |
| Screen | 1080 x 2400 | 1008 x 2244 | - |
| Rate the app drew at | 60 Hz (16.6-16.8 ms) | 60 Hz (16.5-16.6 ms), Skia 30 Hz (33.0 ms); below the 120 Hz panel default, cause unknown | - |
| Build | release, `arm64-v8a`, compiled with `speed` | release, `arm64-v8a`, compiled with `speed` | Release, simulator (2026-10-28) |
| Runs per library | 1 warm-up + 6 interleaved rounds, 6/6 valid; 3 cold rounds, 3/3 valid | 1 warm-up + 6 interleaved rounds, 6/6 valid; 3 cold rounds, 3/3 valid | 1 warm-up + 6 interleaved rounds |
| Animation scales | 1.0 / 1.0 / 1.0 | 1.0 / 1.0 / 1.0 | - |

- Same APK on both phones, and one build per platform covers all three libraries. Two later fixes (`start` input
  checks in JS, keeping the bitmap after a JS reload) don't touch the benchmark path, so I didn't measure them.
- `android.sh` compiled the app with `speed` before each pass, and `dumpsys package dexopt` showed `speed` on both
  phones afterwards.
- Every round runs each library once, and the starting library rotates, so each one starts two rounds.
- Every value is a median over the valid runs, and no run was left out. Raw dumps and per-run JSON files are in
  `results/`.

## Android

```
python3 benchmark/report.py benchmark/results/*.json
```

| Device | Library | Runs | Overlay frame (ms) | Snapshot, JS (ms) | Frame p50 (ms) | Frame p90 (ms) | Janky (%) | Late frames | Missed vsyncs | JS max gap (ms) | Frame interval (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M2101K9AG | ours | 6/6 | 81.5 | 64.8 | 16.2 | 21.1 | 43 | 0 | 4 | 45.5 | 16.6 |
| M2101K9AG | theme-switch-animation | 6/6 | 81.2 | 62.8 | 17.0 | 22.7 | 62 | 0.5 | 3 | 41.5 | 16.6 |
| M2101K9AG | skia | 6/6 | 436.2 | 412.0 | 19.6 | 23.5 | 69 | 0 | 26 | 387.4 | 16.6 |
| Pixel-8-Pro | ours | 6/6 | 61.3 | 52.9 | 5.8 | 8.0 | 0 | 0 | 3 | 33.5 | 16.5 |
| Pixel-8-Pro | theme-switch-animation | 6/6 | 58.5 | 59.8 | 8.7 | 10.3 | 6 | 1 | 2.5 | 28.1 | 16.6 |
| Pixel-8-Pro | skia | 6/6 | 313.4 | 281.1 | 6.7 | 9.3 | 0 | 0 | 9 | 263.9 | 33.0 |

On React Native 0.86 (Fabric), `theme-switch-animation` shows no visible circle. Its second capture runs before
React has mounted the new theme, so it reveals the old screen over the old screen and the new theme appears all at
once at the end. Its overlay and frame numbers are still real, so it stays in the table and still counts for
pass/fail.

## Pass/fail

The rule: for every metric on each Android device, our median has to be no more than 5% above the best
alternative's median. Lower is better for all metrics.

| Device | Metric | ours | Best alternative | Limit (+5%) | Result |
|---|---|---|---|---|---|
| Mi 11 Lite | Overlay frame (ms) | 81.5 | 81.2 (theme-switch-animation) | 85.3 | PASS |
| Mi 11 Lite | Snapshot, JS (ms) | 64.8 | 62.8 (theme-switch-animation) | 65.9 | PASS |
| Mi 11 Lite | Frame p50 (ms) | 16.2 | 17.0 (theme-switch-animation) | 17.9 | PASS |
| Mi 11 Lite | Frame p90 (ms) | 21.1 | 22.7 (theme-switch-animation) | 23.8 | PASS |
| Mi 11 Lite | Janky (%) | 43 | 62 (theme-switch-animation) | 65 | PASS |
| Mi 11 Lite | Late frames | 0 | 0 (skia) | 0 | PASS |
| Mi 11 Lite | Missed vsyncs | 4 | 3 (theme-switch-animation) | 3.15 | FAIL |
| Mi 11 Lite | JS max gap (ms) | 45.5 | 41.5 (theme-switch-animation) | 43.6 | FAIL |
| Pixel 8 Pro | Overlay frame (ms) (1) | 61.3 | 58.5 (theme-switch-animation) | 61.4 | PASS |
| Pixel 8 Pro | Snapshot, JS (ms) | 52.9 | 59.8 (theme-switch-animation) | 62.7 | PASS |
| Pixel 8 Pro | Frame p50 (ms) (2) | 5.8 | 8.7 (theme-switch-animation) | 9.2 | PASS |
| Pixel 8 Pro | Frame p90 (ms) (2) | 8.0 | 10.3 (theme-switch-animation) | 10.8 | PASS |
| Pixel 8 Pro | Janky (%) (2) | 0 | 6 (theme-switch-animation) | 7 | PASS |
| Pixel 8 Pro | Late frames (2) | 0 | 1 (theme-switch-animation) | 1.05 | PASS |
| Pixel 8 Pro | Missed vsyncs (2) | 3 | 2.5 (theme-switch-animation) | 2.62 | FAIL |
| Pixel 8 Pro | JS max gap (ms) | 33.5 | 28.1 (theme-switch-animation) | 29.5 | FAIL |

(1) Rule confirmed by trace on the Mi only, and on the Pixel by gfxinfo sync-phase agreement (no atrace on Android
17). See "Notes on the metrics". (2) Skia is left out of these Pixel rows because it drew at 30 Hz there (33.0 ms),
so its frame times, its janky and late thresholds and its missed vsyncs can't be compared.

The overlay frame now passes on both phones: 81.5 vs 81.2 ms on the Mi, 61.3 vs 58.5 ms on the Pixel. The Pixel
one is close, since the limit there is 61.4 ms.

Ours still fails 4 of the 16 rows, all against `theme-switch-animation`, and I haven't traced the cause of any of
them yet:
- Mi 11 Lite, missed vsyncs: 4 vs 3 (one frame interval).
- Mi 11 Lite, JS max gap: 45.5 vs 41.5 ms (+10%). Our 6 runs spread from 37.0 to 61.7 ms.
- Pixel 8 Pro, missed vsyncs: 3 vs 2.5.
- Pixel 8 Pro, JS max gap: 33.5 vs 28.1 ms (+19%).

## First switch in a fresh process

A real user's first switch doesn't get a benchmark warm-up run, so here each run force-stops the app, waits 1 s,
opens the benchmark screen and switches once. The package's own `warmUp` does run, because the screen mounts
`useStart` about 1.5 s before the tap. Each phone ran 3 rounds with the starting library rotating each round, and
every value is the median of 3 runs.

```
BENCH_COLD=1 benchmark/android.sh <serial> 3 ours theme-switch-animation skia
python3 benchmark/report.py benchmark/results/cold/*.json
```

| Device | Library | Runs | Overlay frame (ms) | Snapshot, JS (ms) | Frame p50 (ms) | Frame p90 (ms) | Janky (%) | Late frames | Missed vsyncs | JS max gap (ms) | Frame interval (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M2101K9AG | ours | 3/3 | 100.0 | 84.5 | 16.4 | 22.3 | 48 | 0 | 4 | 42.0 | 16.7 |
| M2101K9AG | theme-switch-animation | 3/3 | 93.2 | 63.9 | 17.5 | 21.9 | 70 | 1 | 3 | 37.9 | 16.8 |
| M2101K9AG | skia | 3/3 | 456.0 | 397.1 | 18.9 | 23.5 | 79 | 0 | 26 | 363.8 | 16.7 |
| Pixel-8-Pro | ours | 3/3 | 89.3 | 58.4 | 6.2 | 9.6 | 7 | 0 | 3 | 33.1 | 16.6 |
| Pixel-8-Pro | theme-switch-animation | 3/3 | 92.4 | 68.9 | 8.8 | 16.1 | 10 | 0 | 4 | 32.1 | 16.6 |
| Pixel-8-Pro | skia | 3/3 | 349.0 | 281.8 | 7.4 | 10.1 | 0 | 0 | 10 | 261.7 | 33.1 |

| Device | Metric | ours | Best alternative | Limit (+5%) | Result |
|---|---|---|---|---|---|
| Mi 11 Lite | Overlay frame (ms) | 100.0 | 93.2 (theme-switch-animation) | 97.9 | FAIL |
| Mi 11 Lite | Snapshot, JS (ms) | 84.5 | 63.9 (theme-switch-animation) | 67.1 | FAIL |
| Mi 11 Lite | Frame p50 (ms) | 16.4 | 17.5 (theme-switch-animation) | 18.4 | PASS |
| Mi 11 Lite | Frame p90 (ms) | 22.3 | 21.9 (theme-switch-animation) | 23.0 | PASS |
| Mi 11 Lite | Janky (%) | 48 | 70 (theme-switch-animation) | 74 | PASS |
| Mi 11 Lite | Late frames | 0 | 0 (skia) | 0 | PASS |
| Mi 11 Lite | Missed vsyncs | 4 | 3 (theme-switch-animation) | 3.15 | FAIL |
| Mi 11 Lite | JS max gap (ms) | 42.0 | 37.9 (theme-switch-animation) | 39.8 | FAIL |
| Pixel 8 Pro | Overlay frame (ms) | 89.3 | 92.4 (theme-switch-animation) | 97.0 | PASS |
| Pixel 8 Pro | Snapshot, JS (ms) | 58.4 | 68.9 (theme-switch-animation) | 72.3 | PASS |
| Pixel 8 Pro | Frame p50 (ms) (2) | 6.2 | 8.8 (theme-switch-animation) | 9.3 | PASS |
| Pixel 8 Pro | Frame p90 (ms) (2) | 9.6 | 16.1 (theme-switch-animation) | 17.0 | PASS |
| Pixel 8 Pro | Janky (%) (2) | 7 | 10 (theme-switch-animation) | 11 | PASS |
| Pixel 8 Pro | Late frames (2) | 0 | 0 (theme-switch-animation) | 0 | PASS |
| Pixel 8 Pro | Missed vsyncs (2) | 3 | 4 (theme-switch-animation) | 4.20 | PASS |
| Pixel 8 Pro | JS max gap (ms) | 33.1 | 32.1 (theme-switch-animation) | 33.7 | PASS |

(2) Same as above, Skia drew at 30 Hz on the Pixel here too.

The Pixel 8 Pro passes every cold row. On the Mi 11 Lite ours fails 4, all against `theme-switch-animation`, and
the cause isn't traced yet:
- Overlay frame: 100.0 vs 93.2 ms (+7%).
- Snapshot, JS: 84.5 vs 63.9 ms (+32%).
- Missed vsyncs: 4 vs 3.
- JS max gap: 42.0 vs 37.9 ms (+11%).

Three runs make for noisy medians, and the Mi runs spread a lot for both libraries: our overlay frame was 71.4,
100.0 and 105.5 ms, `theme-switch-animation` 77.9, 93.2 and 114.4 ms. Our first-switch JS max gap on the Mi went
from 93.9 ms to 42.0 ms, which is the parser fix below doing its job. Our Mi overlay frame went the other way, from
89.0 to 100.0 ms, and snapshot JS from 68.8 to 84.5 ms. I don't know why yet.

## What changed

In the package (Android):
- Warm-up: `useStart()` calls the native `warmUp` once per instance when it mounts (the `warmUp` option, on by
  default). It starts the snapshot copy thread, allocates one window-size bitmap off the main thread, copies the
  window into it once and keeps it.
- Bitmap reuse: each snapshot copies into the kept bitmap instead of a new one, and gets it back when the overlay
  goes away. In 3-run probes with a logging build, the copy into a reused bitmap took 19-23 ms on the Pixel 8 Pro,
  against 39-43 ms into a new one.
- Parser: `start` no longer converts its argument through an Expo Record. That conversion ran Kotlin reflection on
  the main thread on the first `start` in a process: 85-102 ms on the Mi 11 Lite and 18-27 ms on the Pixel 8 Pro,
  measured from the JS call to the native function in a logging build. It was behind the 93.9 ms first-switch JS
  max gap on the Mi. `start` now takes a plain object and reads it itself.

In the benchmark, `android.sh` now compiles the whole app with `cmd package compile -f -m speed` before each pass,
so every library runs ahead-of-time code. Older passes ran on whatever `adb install` left, which was `verify` (no
ahead-of-time code) on the Mi 11 Lite and `speed` on the Pixel 8 Pro. So on the Mi every library moved this time,
including `theme-switch-animation`, which didn't change at all:
- Mi cold overlay frame: 123.1 to 93.2 ms. Mi cold snapshot JS: 98.1 to 63.9 ms.
- Mi warm overlay frame: 91.4 to 81.2 ms. Mi warm snapshot JS: 80.4 to 62.8 ms.

The compile is the only difference I know of for it on the Mi, but I didn't measure it on its own. On the Pixel
both passes ran `speed`, and `theme-switch-animation` moved less (warm overlay 62.8 to 58.5 ms, cold 96.7 to 92.4
ms). None of this measures our Baseline Profile. A Play install compiles only the code that profiles list, and
this package is the only one here that ships one.

## iOS simulator

These are from the iOS 26.5 simulator on a Mac, not a phone. The harness can't tap on the simulator, so the launch
argument `-benchRuns '<id> <id> ...'` opens a fresh benchmark screen per id and runs it from the Run button's
centre. That means `snapshotMs` starts at the run call (`t0=onPress`) rather than at a touch. These numbers are
from 2026-10-28. The warm-up build only adds a `warmUp` that does nothing on iOS, so I didn't measure iOS again.

| Library | Snapshot, JS (ms), 6 runs | JS max gap (ms), 6 runs | Snapshot, JS (ms), first run in a fresh process (3 runs) |
|---|---|---|---|
| ours | 8.3 | 16.8 | 10.0 |
| theme-switch-animation | 11.8 | 16.9 | 14.7 |
| skia | 44.0 | 18.0 | 44.6 |

All three libraries build and run on iOS. There are no frame metrics: `xctrace` reports "Hitches is not supported
on this platform" for the simulator, and the `CADisplayLink` frame probe I wanted for the simulator didn't get
built (see `benchmark/README.md`). The BENCH lines are in `results/ios-simulator/`.

## Notes on the metrics

- Overlay frame: the first frame after the press. On the Mi a system trace (atrace, frames matched by
  `FrameTimelineVsyncId`) showed that this is the first frame with the overlay for every library: ours and
  `theme-switch-animation` upload the full-window snapshot texture in it, and Skia composites its canvas buffer.
  The earlier rule (first frame at or after the JS overlay time) picked the second drawn frame of
  `theme-switch-animation`, about 88 ms late.
- Pixel 8 Pro: the rule is confirmed by trace on the Mi only, and on the Pixel by gfxinfo sync-phase agreement (no
  atrace on Android 17). No system trace was possible. `atrace` reports "Did not find trace folder", and a
  Perfetto session got no ftrace events. The `gfxinfo` data agrees with the Mi. For ours and Skia the first frame
  starts 5-36 ms after the JS overlay time on every run, so both rules pick the same frame. For
  `theme-switch-animation` it starts 1-3 ms before it, and its sync phase is 1.4-2.6 ms vs 0.2-0.5 ms for the
  other two, which fits the upload of its bitmap.
- No run had its first frame more than 150 ms before the JS overlay time (`overlayLeadMs`, largest 17.3 ms in these
  runs and 19.2 ms in the earlier ones). That check only catches gross errors, though: a first frame one interval
  too early (16.7 ms at 60 Hz) stays under it.
- The Pixel 8 Pro drew at 60 Hz in these passes, below its 120 Hz panel default (an earlier run measured 120 Hz on
  the same phone). I haven't found out why. Ours and theme-switch-animation drew at the same rate, so the
  comparison holds, but one frame costs 16.7 ms here instead of 8.3 ms.
- With the threshold at the frame interval, Janky (%) is high for every library on the Mi (47-68%). HWUI pipelines
  frames, so a frame that takes longer than one interval isn't necessarily dropped. Late frames and missed vsyncs
  are a better signal for real drops.
- The janky threshold is the interval each library drew at. On the Pixel, Skia drew at 30 Hz (32.9 ms), so its
  threshold doubled and its frame times cover half as many frames. That means its frame p50, p90 and janky values
  on the Pixel can't be compared with the other two. Its missed vsyncs are counted in 32.9 ms intervals, so 9 is
  about 18 at 60 Hz.
- `theme-switch-animation` doesn't report an end to JS, so its run ends a fixed 700 ms after
  `switchThemeFunction`.

## Earlier results

### 2026-10-28, before the warm-up

This pass ran on whatever `adb install` left: no ahead-of-time code on the Mi 11 Lite (`verify`), all code compiled
on the Pixel 8 Pro (`speed`). Its raw runs were replaced in `results/` and are still in the git history of that
folder.

| Device | Library | Runs | Overlay frame (ms) | Snapshot, JS (ms) | Frame p50 (ms) | Frame p90 (ms) | Janky (%) | Late frames | Missed vsyncs | JS max gap (ms) | Frame interval (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M2101K9AG | ours | 6/6 | 84.8 | 72.6 | 16.5 | 23.2 | 47 | 1 | 4 | 39.0 | 16.6 |
| M2101K9AG | theme-switch-animation | 6/6 | 91.4 | 80.4 | 17.2 | 22.6 | 61 | 1 | 4 | 37.0 | 16.8 |
| M2101K9AG | skia | 6/6 | 432.9 | 400.5 | 18.4 | 25.0 | 68 | 1 | 25 | 374.9 | 16.6 |
| Pixel-8-Pro | ours | 6/6 | 72.3 | 60.1 | 5.6 | 8.0 | 0 | 0 | 4 | 28.0 | 16.4 |
| Pixel-8-Pro | theme-switch-animation | 6/6 | 62.8 | 58.1 | 8.3 | 9.6 | 5 | 1 | 3 | 25.1 | 16.6 |
| Pixel-8-Pro | skia | 6/6 | 313.4 | 285.4 | 7.1 | 9.7 | 0 | 0 | 9 | 265.7 | 32.9 |

Ours failed 4 rows then, all against `theme-switch-animation`: the Mi JS max gap (39.0 vs 37.0 ms, +5.4% against a
38.9 ms limit), and on the Pixel the overlay frame (72.3 vs 62.8 ms, +15%), missed vsyncs (4 vs 3) and JS max gap
(28.0 vs 25.1 ms, +12%).

The overlay frame loss came from how the snapshot is taken. Ours reads the window back from the GPU with
`PixelCopy`, which is pixel-exact. On the Mi that readback (`copySurfaceInto` on the RenderThread) takes 25-47 ms
for a 1080 x 2400 window, and a `simpleperf` cycle profile finds only about 3 ms of CPU cycles in it, so it's
mostly waiting on the GPU. `theme-switch-animation` draws the view tree into a software `Canvas` instead
(`rootView.draw(canvas)`, `ThemeSwitchAnimationModule.java:101-105` in its 0.8.0 Android source), on the React
Native native-modules thread (`mqt_v_native`). I couldn't trace the Pixel, but `gfxinfo` shows both libraries
drawing the overlay frame in about 5 ms, with ours starting about one frame later. The likely cause there is the
same readback latency up against a fast software draw on the Tensor G3.

On the first switch in a fresh process (3 runs, no warm-up of any kind), ours lost in seven places. The big ones on
the Pixel were the overlay frame (121.2 vs 96.7 ms), snapshot JS (100.5 vs 71.7 ms) and JS max gap (36.1 vs 29.2
ms). On the Mi our JS max gap was 93.9 ms against 41.2 ms, 2.4 times our own warm value, while ours won the
overlay frame (89.0 vs 123.1 ms).

### Fixes before that pass

Three Android fixes brought our Mi overlay frame from 118.3 ms to 109.3, 90.8 and finally 84.8 ms, and the JS max
gap from 50.5 to 39.0 ms:
1. On API 26-33, `PixelCopy.request` blocks the calling thread for the whole readback. Ours used to call it on the
   main thread, blocking it for 41-47 ms. The request now runs on a background `HandlerThread`.
2. The overlay's first frame used to upload the 1080 x 2400 snapshot texture (3-7 ms on the RenderThread). Ours
   now calls `Bitmap.prepareToDraw()` before attaching the overlay, so the upload happens before that frame.
3. The readback often finishes right as a vsync arrives, so the overlay used to wait an extra frame. On API 26-33
   the listener now posts the result to the front of the main queue (`postAtFrontOfQueue`), and the overlay makes
   it into the pending frame. It didn't help on API 34+, so it stays limited to API 26-33.

The theme mount (React Native mounting the new colours, 15-45 ms on the main thread on the Mi) costs the same for
all three libraries, so the package can't do anything about it.
