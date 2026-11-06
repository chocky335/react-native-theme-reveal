# Benchmark results

Measured on 2026-11-06 with the build that has everything so far: the warm-up, the JS gap fix, the snapshot on
press-in and the GPU snapshot. Older numbers are under "History". See `benchmark/README.md` for what each metric
means and the run rules.

Since this pass the code has changed in the README, the benchmark scripts and one Android path: a GPU snapshot that
waits behind a warm-up that never ends. No run here took that path. Every `ours` run reports `capture=gpu`, and a
GPU snapshot that times out would report `pixelcopy`. The change adds one check to each capture, and I haven't
measured it.

## Setup

| | Mi 11 Lite | Pixel 8 Pro |
|---|---|---|
| Model (`ro.product.model`) | `M2101K9AG` | `Pixel 8 Pro` (tables: `Pixel-8-Pro`) |
| OS | Android 13 (API 33), MIUI | Android 17 (API 37) |
| Screen | 1080 x 2400 | 1008 x 2244 |
| Display modes | 60 and 90 Hz, default 60 Hz | 60 and 120 Hz |
| Rate the app drew at | 60 Hz (16.5-16.7 ms) for every library | 60 Hz (16.4-16.6 ms), Skia 30 Hz (32.9-33.0 ms) |
| Animation scales | 1.0 / 1.0 / 1.0 | 1.0 / 1.0 / 1.0 |

- Build: release, `arm64-v8a`. I reran the JS bundle task for it (`:app:createBundleReleaseJsAndAssets --rerun`)
  so the APK can't carry an old bundle. The same APK ran on both phones, and it holds all three libraries.
- `android.sh` compiled the whole app with `cmd package compile -f -m speed` before each pass, and
  `dumpsys package` showed `speed` afterwards, so every library ran ahead-of-time code. This doesn't measure what
  our Baseline Profile does for a Play install.
- Before each pass the benchmark force-stopped every other example package. This time there were none on either
  phone.
- Warm passes ran one discarded warm-up run per library, then 6 interleaved rounds, with the starting library
  rotating so each one starts two rounds. Cold passes ran 3 rounds: each run force-stops the app, waits 1 s, opens
  the benchmark screen and switches once. The package's own `warmUp` still runs, because the screen mounts
  `useStart` about 1.5 s before the tap.
- Every library got 6/6 valid warm runs and 3/3 valid cold runs in every folder, on the first try.
- There are two tap forms, the same for every library. `input tap` presses for about 0 ms, which no finger does
  (output in `results/` and `results/cold/`). The hold form presses for 100 ms with `input swipe x y x y 100`
  (`BENCH_HOLD_MS=100`, output in `results/hold/` and `results/cold-hold/`). In both, t0 is the release
  (`onPress`), so the hold isn't in any metric. Ours takes its snapshot on press-in (`pressCapture`), and the
  other libraries do nothing on press-in.
- Each `ours` run writes its capture path (`capture=gpu` or `capture=pixelcopy`) into its BENCH line and its JSON.
  All 36 `ours` runs here have `capture=gpu`: 12 warm tap, 12 warm hold, 6 cold tap and 6 cold hold.
- Every value is a median over the valid runs. Raw dumps and per-run JSON files are in `results/`.

On React Native 0.86 (Fabric), `theme-switch-animation` shows no visible circle. Its second capture runs before
React has mounted the new theme, so it reveals the old screen over the old screen and the new theme appears all at
once at the end. Its overlay and frame numbers are still real, so it stays in the tables and counts for pass/fail.

## Pass/fail rule

For every metric on each phone, our median must be no more than 5% above the best alternative's median. Lower is
better for every metric. An alternative that drew at a frame interval more than 10% off ours is left out of the
frame metrics (p50, p90, janky, late frames, missed vsyncs), which here means Skia on the Pixel 8 Pro at 30 Hz.
Four pass/fail tables of 16 rows make 64 rows. With Skia in the Pixel frame rows, one more row would fail: cold tap
janky, 7% against 0% at 30 Hz.

Ours fails 8 of the 64: 3 warm tap, 3 warm hold, 1 cold tap and 1 cold hold. The overlay frame and snapshot JS
rows pass in all four tables on both phones. "Rows that fail" below goes through each one.

## Warm, `input tap`

```
python3 benchmark/report.py benchmark/results/*.json
python3 benchmark/report.py --gate benchmark/results/*.json
```

| Device | Library | Runs | Overlay frame (ms) | Snapshot, JS (ms) | Frame p50 (ms) | Frame p90 (ms) | Janky (%) | Late frames | Missed vsyncs | JS max gap (ms) | Frame interval (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M2101K9AG | ours | 6/6 | 73.0 | 60.0 | 16.6 | 21.9 | 52 | 0.5 | 3 | 31.7 | 16.6 |
| M2101K9AG | theme-switch-animation | 6/6 | 70.7 | 69.3 | 17.4 | 21.3 | 63 | 1 | 2.5 | 39.2 | 16.7 |
| M2101K9AG | skia | 6/6 | 425.6 | 401.6 | 18.7 | 23.5 | 62 | 0 | 25 | 383.6 | 16.6 |
| Pixel-8-Pro | ours | 6/6 | 46.2 | 43.3 | 5.5 | 7.6 | 0 | 0 | 2 | 27.0 | 16.4 |
| Pixel-8-Pro | theme-switch-animation | 6/6 | 56.2 | 51.2 | 8.2 | 9.5 | 0 | 0 | 2.5 | 25.4 | 16.6 |
| Pixel-8-Pro | skia | 6/6 | 329.9 | 296.6 | 7.2 | 9.2 | 0 | 0 | 9 | 273.5 | 32.9 |

| Device | Metric | ours | Best alternative | Limit (+5%) | Result |
|---|---|---|---|---|---|
| M2101K9AG | Overlay frame (ms) | 73.0 | 70.7 (theme-switch-animation) | 74.2 | PASS |
| M2101K9AG | Snapshot, JS (ms) | 60.0 | 69.3 (theme-switch-animation) | 72.8 | PASS |
| M2101K9AG | Frame p50 (ms) | 16.6 | 17.4 (theme-switch-animation) | 18.3 | PASS |
| M2101K9AG | Frame p90 (ms) | 21.9 | 21.3 (theme-switch-animation) | 22.3 | PASS |
| M2101K9AG | Janky (%) | 52 | 62 (skia) | 66 | PASS |
| M2101K9AG | Late frames | 0.5 | 0 (skia) | 0.00 | FAIL |
| M2101K9AG | Missed vsyncs | 3 | 2.5 (theme-switch-animation) | 2.62 | FAIL |
| M2101K9AG | JS max gap (ms) | 31.7 | 39.2 (theme-switch-animation) | 41.2 | PASS |
| Pixel-8-Pro | Overlay frame (ms) | 46.2 | 56.2 (theme-switch-animation) | 59.1 | PASS |
| Pixel-8-Pro | Snapshot, JS (ms) | 43.3 | 51.2 (theme-switch-animation) | 53.8 | PASS |
| Pixel-8-Pro | Frame p50 (ms) | 5.5 | 8.2 (theme-switch-animation) | 8.6 | PASS |
| Pixel-8-Pro | Frame p90 (ms) | 7.6 | 9.5 (theme-switch-animation) | 10.0 | PASS |
| Pixel-8-Pro | Janky (%) | 0 | 0 (theme-switch-animation) | 0 | PASS |
| Pixel-8-Pro | Late frames | 0 | 0 (theme-switch-animation) | 0.00 | PASS |
| Pixel-8-Pro | Missed vsyncs | 2 | 2.5 (theme-switch-animation) | 2.62 | PASS |
| Pixel-8-Pro | JS max gap (ms) | 27.0 | 25.4 (theme-switch-animation) | 26.7 | FAIL |

## Warm, 100 ms hold

```
python3 benchmark/report.py benchmark/results/hold/*.json
python3 benchmark/report.py --gate benchmark/results/hold/*.json
```

| Device | Library | Runs | Overlay frame (ms) | Snapshot, JS (ms) | Frame p50 (ms) | Frame p90 (ms) | Janky (%) | Late frames | Missed vsyncs | JS max gap (ms) | Frame interval (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M2101K9AG | ours | 6/6 | 45.3 | 33.5 | 15.9 | 24.8 | 43 | 0.5 | 1 | 39.9 | 16.6 |
| M2101K9AG | theme-switch-animation | 6/6 | 98.9 | 81.7 | 17.3 | 21.9 | 67 | 0.5 | 4 | 40.1 | 16.7 |
| M2101K9AG | skia | 6/6 | 439.9 | 418.6 | 19.5 | 23.9 | 67 | 0 | 25.5 | 401.1 | 16.6 |
| Pixel-8-Pro | ours | 6/6 | 40.5 | 31.6 | 5.7 | 8.7 | 3 | 0 | 1 | 30.4 | 16.4 |
| Pixel-8-Pro | theme-switch-animation | 6/6 | 58.4 | 47.3 | 8.3 | 9.6 | 3 | 0 | 2 | 30.6 | 16.6 |
| Pixel-8-Pro | skia | 6/6 | 324.8 | 291.2 | 7.2 | 9.7 | 0 | 0 | 9 | 271.2 | 32.9 |

| Device | Metric | ours | Best alternative | Limit (+5%) | Result |
|---|---|---|---|---|---|
| M2101K9AG | Overlay frame (ms) | 45.3 | 98.9 (theme-switch-animation) | 103.9 | PASS |
| M2101K9AG | Snapshot, JS (ms) | 33.5 | 81.7 (theme-switch-animation) | 85.7 | PASS |
| M2101K9AG | Frame p50 (ms) | 15.9 | 17.3 (theme-switch-animation) | 18.2 | PASS |
| M2101K9AG | Frame p90 (ms) | 24.8 | 21.9 (theme-switch-animation) | 23.0 | FAIL |
| M2101K9AG | Janky (%) | 43 | 67 (skia) | 71 | PASS |
| M2101K9AG | Late frames | 0.5 | 0 (skia) | 0.00 | FAIL |
| M2101K9AG | Missed vsyncs | 1 | 4 (theme-switch-animation) | 4.20 | PASS |
| M2101K9AG | JS max gap (ms) | 39.9 | 40.1 (theme-switch-animation) | 42.2 | PASS |
| Pixel-8-Pro | Overlay frame (ms) | 40.5 | 58.4 (theme-switch-animation) | 61.3 | PASS |
| Pixel-8-Pro | Snapshot, JS (ms) | 31.6 | 47.3 (theme-switch-animation) | 49.7 | PASS |
| Pixel-8-Pro | Frame p50 (ms) | 5.7 | 8.3 (theme-switch-animation) | 8.7 | PASS |
| Pixel-8-Pro | Frame p90 (ms) | 8.7 | 9.6 (theme-switch-animation) | 10.0 | PASS |
| Pixel-8-Pro | Janky (%) | 3 | 3 (theme-switch-animation) | 3 | FAIL |
| Pixel-8-Pro | Late frames | 0 | 0 (theme-switch-animation) | 0.00 | PASS |
| Pixel-8-Pro | Missed vsyncs | 1 | 2 (theme-switch-animation) | 2.10 | PASS |
| Pixel-8-Pro | JS max gap (ms) | 30.4 | 30.6 (theme-switch-animation) | 32.1 | PASS |

The Pixel janky row shows 3 against 3 because the table rounds. The unrounded values are 3.4% and 3.2%, against a
3.39% limit.

## First switch in a fresh process, `input tap`

```
python3 benchmark/report.py benchmark/results/cold/*.json
python3 benchmark/report.py --gate benchmark/results/cold/*.json
```

| Device | Library | Runs | Overlay frame (ms) | Snapshot, JS (ms) | Frame p50 (ms) | Frame p90 (ms) | Janky (%) | Late frames | Missed vsyncs | JS max gap (ms) | Frame interval (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M2101K9AG | ours | 3/3 | 68.0 | 59.6 | 16.2 | 23.3 | 39 | 0 | 3 | 27.2 | 16.6 |
| M2101K9AG | theme-switch-animation | 3/3 | 119.5 | 96.4 | 17.2 | 21.0 | 64 | 1 | 5 | 42.2 | 16.7 |
| M2101K9AG | skia | 3/3 | 459.4 | 406.8 | 19.4 | 25.4 | 77 | 0 | 27 | 371.7 | 16.6 |
| Pixel-8-Pro | ours | 3/3 | 68.7 | 64.3 | 6.1 | 8.5 | 7 | 0 | 3 | 24.4 | 16.5 |
| Pixel-8-Pro | theme-switch-animation | 3/3 | 85.9 | 61.9 | 9.0 | 16.2 | 10 | 0 | 3 | 33.2 | 16.6 |
| Pixel-8-Pro | skia | 3/3 | 384.7 | 318.4 | 6.9 | 10.1 | 0 | 0 | 11 | 286.7 | 33.0 |

| Device | Metric | ours | Best alternative | Limit (+5%) | Result |
|---|---|---|---|---|---|
| M2101K9AG | Overlay frame (ms) | 68.0 | 119.5 (theme-switch-animation) | 125.5 | PASS |
| M2101K9AG | Snapshot, JS (ms) | 59.6 | 96.4 (theme-switch-animation) | 101.2 | PASS |
| M2101K9AG | Frame p50 (ms) | 16.2 | 17.2 (theme-switch-animation) | 18.0 | PASS |
| M2101K9AG | Frame p90 (ms) | 23.3 | 21.0 (theme-switch-animation) | 22.1 | FAIL |
| M2101K9AG | Janky (%) | 39 | 64 (theme-switch-animation) | 68 | PASS |
| M2101K9AG | Late frames | 0 | 0 (skia) | 0.00 | PASS |
| M2101K9AG | Missed vsyncs | 3 | 5 (theme-switch-animation) | 5.25 | PASS |
| M2101K9AG | JS max gap (ms) | 27.2 | 42.2 (theme-switch-animation) | 44.3 | PASS |
| Pixel-8-Pro | Overlay frame (ms) | 68.7 | 85.9 (theme-switch-animation) | 90.2 | PASS |
| Pixel-8-Pro | Snapshot, JS (ms) | 64.3 | 61.9 (theme-switch-animation) | 65.0 | PASS |
| Pixel-8-Pro | Frame p50 (ms) | 6.1 | 9.0 (theme-switch-animation) | 9.4 | PASS |
| Pixel-8-Pro | Frame p90 (ms) | 8.5 | 16.2 (theme-switch-animation) | 17.0 | PASS |
| Pixel-8-Pro | Janky (%) | 7 | 10 (theme-switch-animation) | 10 | PASS |
| Pixel-8-Pro | Late frames | 0 | 0 (theme-switch-animation) | 0.00 | PASS |
| Pixel-8-Pro | Missed vsyncs | 3 | 3 (theme-switch-animation) | 3.15 | PASS |
| Pixel-8-Pro | JS max gap (ms) | 24.4 | 33.2 (theme-switch-animation) | 34.9 | PASS |

## First switch in a fresh process, 100 ms hold

```
python3 benchmark/report.py benchmark/results/cold-hold/*.json
python3 benchmark/report.py --gate benchmark/results/cold-hold/*.json
```

| Device | Library | Runs | Overlay frame (ms) | Snapshot, JS (ms) | Frame p50 (ms) | Frame p90 (ms) | Janky (%) | Late frames | Missed vsyncs | JS max gap (ms) | Frame interval (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| M2101K9AG | ours | 3/3 | 42.3 | 33.6 | 15.7 | 20.8 | 32 | 0 | 1 | 43.0 | 16.5 |
| M2101K9AG | theme-switch-animation | 3/3 | 104.5 | 81.0 | 17.6 | 20.7 | 61 | 1 | 4 | 48.0 | 16.7 |
| M2101K9AG | skia | 3/3 | 504.6 | 445.0 | 19.0 | 27.4 | 59 | 1 | 30 | 424.6 | 16.6 |
| Pixel-8-Pro | ours | 3/3 | 49.7 | 33.4 | 6.0 | 8.8 | 10 | 0 | 1 | 29.2 | 16.5 |
| Pixel-8-Pro | theme-switch-animation | 3/3 | 104.2 | 65.9 | 8.6 | 12.0 | 6 | 1 | 3 | 45.7 | 16.6 |
| Pixel-8-Pro | skia | 3/3 | 370.9 | 300.4 | 7.3 | 10.8 | 0 | 0 | 11 | 280.3 | 32.9 |

| Device | Metric | ours | Best alternative | Limit (+5%) | Result |
|---|---|---|---|---|---|
| M2101K9AG | Overlay frame (ms) | 42.3 | 104.5 (theme-switch-animation) | 109.7 | PASS |
| M2101K9AG | Snapshot, JS (ms) | 33.6 | 81.0 (theme-switch-animation) | 85.0 | PASS |
| M2101K9AG | Frame p50 (ms) | 15.7 | 17.6 (theme-switch-animation) | 18.4 | PASS |
| M2101K9AG | Frame p90 (ms) | 20.8 | 20.7 (theme-switch-animation) | 21.7 | PASS |
| M2101K9AG | Janky (%) | 32 | 59 (skia) | 62 | PASS |
| M2101K9AG | Late frames | 0 | 1 (skia) | 1.05 | PASS |
| M2101K9AG | Missed vsyncs | 1 | 4 (theme-switch-animation) | 4.20 | PASS |
| M2101K9AG | JS max gap (ms) | 43.0 | 48.0 (theme-switch-animation) | 50.4 | PASS |
| Pixel-8-Pro | Overlay frame (ms) | 49.7 | 104.2 (theme-switch-animation) | 109.4 | PASS |
| Pixel-8-Pro | Snapshot, JS (ms) | 33.4 | 65.9 (theme-switch-animation) | 69.2 | PASS |
| Pixel-8-Pro | Frame p50 (ms) | 6.0 | 8.6 (theme-switch-animation) | 9.1 | PASS |
| Pixel-8-Pro | Frame p90 (ms) | 8.8 | 12.0 (theme-switch-animation) | 12.6 | PASS |
| Pixel-8-Pro | Janky (%) | 10 | 6 (theme-switch-animation) | 7 | FAIL |
| Pixel-8-Pro | Late frames | 0 | 1 (theme-switch-animation) | 1.05 | PASS |
| Pixel-8-Pro | Missed vsyncs | 1 | 3 (theme-switch-animation) | 3.15 | PASS |
| Pixel-8-Pro | JS max gap (ms) | 29.2 | 45.7 (theme-switch-animation) | 48.0 | PASS |

## Rows that fail

I ran this build twice: once before the overlay moved into the window root's `ViewOverlay` (so its attach and
removal no longer lay out the window), and once after, which is the pass above. The other changes in between only
fixed paths that no run took. The earlier run failed 3 of 64 rows, this one fails 8. Nothing that failed before
passes now. "tsa" below is `theme-switch-animation`.

### Mi 11 Lite, frame p90 (warm hold and cold tap)

This failed in both runs: 25.4 vs 22.0 ms and 22.7 vs 21.6 ms before, 24.8 vs 21.9 ms and 23.3 vs 21.0 ms now.
Per run, warm hold: ours 27.1, 22.4, 24.6, 25.0, 29.1 and 19.8 ms, tsa 20.4-23.9 ms. Cold tap: ours 24.8, 21.7 and
23.3 ms, tsa 20.2, 21.0 and 23.8 ms.

I measured the cause with `gfxinfo` phases and a system trace on the Mi:
- Ours animates the reveal from the UI thread, and each frame draws the full-window hardware bitmap through a clip
  path. tsa runs `createCircularReveal`, a RenderThread animator. While the UI thread is busy, the RenderThread
  animator skips vsyncs instead of drawing a slow frame, and ours draws the slow frame. In the warm hold runs ours
  had 4 to 6 frames over 23 ms per run, tsa had 3.
- In the hold form the GPU idles after the press-in capture. In all 3 traced hold runs of ours, the Adreno 618 went
  to slumber between the capture and the overlay frame, and the overlay frame woke it. From the overlay frame to
  +160 to +360 ms the GPU ran at its lowest clock (180 MHz), for both libraries in both tap forms. At that clock a
  frame of ours costs about 10 ms of GPU and a tsa frame about 7.5 ms, so the overlay frame and the ones after it
  cost 2-3 times more than in the tap form.
- In the cold tap runs over 24 ms, a 33-35 ms GPU backlog frame comes right after the theme mount frame.

The `ViewOverlay` change removed a window layout from the overlay frame and the last frame (3.5-5.5 ms of binder
calls in each). In short runs that brought the Mi hold p90 from 28.3 to 21.6 ms against 24.1 ms for tsa, a pass,
but this full run fails it again. A reveal on the RenderThread is the only lever left. It's an architecture change
and I haven't tried it. Keeping the GPU busy during the hold would cost power and only move work around to beat
the metric, so I'm not doing that.

### Pixel 8 Pro, janky share (warm hold)

Also failed in both runs: 5% vs 3% before, 3.4% vs 3.2% now. Both medians are 1 janky frame. Ours draws 29 frames
in the window and tsa 31, so one frame is a bigger share for ours. Per run, ours had 1, 1, 1, 0, 0 and 2 janky
frames.

From `gfxinfo` in the earlier run (there's no system trace on Android 17): our overlay frame was janky in every
hold run. It's the first frame after the press-in capture, and its RenderThread draw and GPU work grow with the
hold (100 ms hold: 20.9 ms frame, 300 ms hold: 21.5-25.7 ms, 30 ms hold: 7.9-12.0 ms). A second janky frame shows
up when `relayoutWindow` (the example app's status bar style) lands in the theme mount frame. tsa has 1 janky
frame, its overlay frame. I only traced the GPU idle state on the Mi.

### Mi 11 Lite, late frames (warm tap and warm hold)

These passed before the `ViewOverlay` change and fail now: ours 0.5 vs 0 for Skia in both forms.
- Warm tap, ours per run: 0, 0, 2, 0, 0, 0 before, 0, 2, 1, 0, 0, 2 now. tsa had 1 late frame in 6 of 6 runs
  before and in 5 of 6 now.
- Warm hold, ours per run: 0 in every run before, 0, 1, 0, 2, 1, 0 now. tsa had 1 late frame in 6 of 6 runs before
  and in 3 of 6 now (median 0.5).

So ours went from a late frame in 1 of 12 runs (2 late frames) to 6 of 12 runs (9 late frames). The only other run
after the change is a short one, Mi warm hold with 6 runs, where ours had a median of 0. There's no tap run after
the change apart from this pass. No trace shows the cause, and I can't rule out a regression from the
`ViewOverlay` change.

### Rows that look like noise

Three rows flipped where ours didn't move and tsa did. The two runs disagree on them, so I read them as run-to-run
noise, but no trace shows a cause.
- Mi 11 Lite, warm tap, missed vsyncs: ours 3 both times (all 6 runs 3 now), tsa from 3.5 to 2.5 (2-4 per run).
  This metric counts the frames from the press to the overlay frame, so it follows the capture time.
- Pixel 8 Pro, warm tap, JS max gap: ours 27.2 then 27.0 ms, tsa from 26.3 to 25.4 ms. Per run now, ours
  21.7-35.7 ms and tsa 23.5-31.9 ms.
- Pixel 8 Pro, cold hold, janky share: ours 10% both times (2 or 3 janky frames per run), tsa from 10% (2 to 5
  janky frames) to 6% (2 in each run). Only 3 runs each.

## iOS simulator (2026-10-28)

These are from the iOS 26.5 simulator on a Mac, not a phone. The harness can't tap on the simulator, so the launch
argument `-benchRuns '<id> <id> ...'` opens a fresh benchmark screen per id and runs it from the Run button's
centre. That means `snapshotMs` starts at the run call (`t0=onPress`) rather than at a touch. The package's iOS
code has changed since (the press-in capture and the `prepare` change), and I haven't measured iOS again.

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
- Pixel 8 Pro: the rule is confirmed by trace on the Mi only, and on the Pixel by gfxinfo sync-phase agreement.
  No system trace was possible on Android 17. `atrace` reports "Did not find trace folder", and a Perfetto session
  got no ftrace events. In the 2026-10-28 pass, for ours and Skia the first frame started 5-36 ms after the JS
  overlay time on every run, so both rules pick the same frame. For `theme-switch-animation` it started 1-3 ms
  before it, and its sync phase was 1.4-2.6 ms vs 0.2-0.5 ms for the other two, which fits the upload of its
  bitmap.
- No run had its first frame more than 150 ms before the JS overlay time (`overlayLeadMs`). In the current pass
  the largest lead is 50.0 ms (`theme-switch-animation`, Pixel cold hold), and ours is 0.8-8.6 ms. That check only
  catches gross errors, though: a first frame one interval too early (16.7 ms at 60 Hz) stays under it.
- The Pixel 8 Pro drew at 60 Hz in every pass, below its 120 Hz panel default (an earlier run measured 120 Hz on
  the same phone). I haven't found out why. Ours and theme-switch-animation drew at the same rate, so the
  comparison holds, but one frame costs 16.7 ms here instead of 8.3 ms.
- With the threshold at the frame interval, Janky (%) is high for every library on the Mi: 32-77% across the four
  current tables. HWUI pipelines frames, so a frame that takes longer than one interval isn't necessarily dropped.
  Late frames and missed vsyncs are a better signal for real drops.
- The janky threshold is the interval each library drew at. On the Pixel, Skia drew at 30 Hz (32.9-33.0 ms now),
  so its threshold doubled and its frame times cover half as many frames. That means its frame p50, p90 and janky
  values on the Pixel can't be compared with the other two. Its missed vsyncs are counted in 33 ms intervals, so
  its 9-11 now are about 18-22 at 60 Hz.
- A janky share is a share of the frames in the window. Ours draws 29 frames there on the Pixel and
  `theme-switch-animation` 31, so one janky frame is 3.4% for ours and 3.2% for it.
- `theme-switch-animation` doesn't report an end to JS, so its run ends a fixed 700 ms after
  `switchThemeFunction`.

## History

How many of the 16 rows ours failed in each form, build by build. These counts include Skia in the Pixel frame
rows.

| Build | Warm tap | Warm hold | Cold tap | Cold hold |
|---|---|---|---|---|
| Before any of this (2026-10-28) | 4 of 16 | - | 7 of 16 | - |
| Warm-up (2026-10-31) | 4 of 16 | - | 4 of 16 | - |
| Press-in capture alone, no warm-up | 3 of 16 | 1 of 16 | 9 of 16 | 1 of 16 |
| GPU capture alone, no warm-up | 4 of 16 | - | 8 of 16 | - |
| Everything, before the `ViewOverlay` change | 0 of 16 | 2 of 16 | 1 of 16 | 0 of 16 |
| Everything (2026-11-06, the current pass) | 3 of 16 | 3 of 16 | 1 of 16 | 1 of 16 |

The press-in and GPU capture builds each started from the same code as the first row, so each one measures that
change alone. Without the warm-up, their cold tap rows fail more. The GPU capture build only ran the tap form,
since it had no press-in capture. The first row ran without the `speed` compile, so the Mi 11 Lite ran `verify`
there (no ahead-of-time code). Every later pass compiled with `speed`.

### Warm-up (2026-10-31)

The warm-up, the kept bitmap and the `start` parser. Values are ours / tsa, before and after:
- Mi cold JS max gap: 93.9 / 41.2 to 42.0 / 37.9 ms. The old `start` conversion ran Kotlin reflection on the main
  thread on the first `start` (85-102 ms on the Mi, 18-27 ms on the Pixel), and that was the whole problem.
- Pixel cold overlay frame: 121.2 / 96.7 to 89.3 / 92.4 ms. Pixel warm: 72.3 / 62.8 to 61.3 / 58.5 ms.
- Mi cold overlay frame: 89.0 / 123.1 to 100.0 / 93.2 ms, so ours got worse there.
- In logging-build probes, the copy into a kept bitmap took 19-23 ms on the Pixel 8 Pro, against 39-43 ms into a
  new one.

The `speed` compile started with this pass, and on the Mi it moved `theme-switch-animation` a lot (warm overlay
frame 91.4 to 81.2 ms, cold 123.1 to 93.2 ms). Skia barely moved (warm 432.9 to 436.2 ms, cold 451.2 to 456.0 ms).
I didn't measure the compile on its own.

### JS gap fix

`prepare` used to resolve just before a frame's JS timers ran, so the theme render started about one frame after
the last `requestAnimationFrame` tick and held back the tick that was due. tsa applies its theme from a
`setTimeout`, so its render starts right after a tick. I found this with JS marks on both phones and a Mi trace.
Now `prepare` resolves from a `Choreographer` frame callback, which runs after React Native's timers for that
frame, or from a 100 ms main-thread timeout, whichever comes first. In short runs the Pixel JS max gap went from
37.0 / 27.9 to 27.7 / 29.5 ms. The cost is that snapshot JS waits for the next frame's timers: +7.3 ms median on
the Pixel. On the Mi that row still passed in both runs, once by 0.4 ms.

### Press-in capture alone

With a 100 ms hold the snapshot is ready at release, so the overlay frame drops to about half: warm, 76.4 to 41.5
ms on the Mi (tsa 94.4 ms) and 72.7 to 39.5 ms on the Pixel (tsa 63.6 ms). The tap form gains almost nothing,
since `input tap` holds for about 0 ms. This is where the Mi frame p90 hold row first failed (23.0 vs 21.6 ms warm,
27.7 vs 21.0 ms cold).

### GPU capture alone

Warm overlay frame: 76.2 / 89.5 ms on the Mi, 68.5 / 64.7 ms on the Pixel. All 4 warm failures were on the Pixel,
including the JS max gap (32.2 / 27.1 ms). An early prototype had measured a Mi overlay frame of 52.0 ms against
82.6 ms (4 runs each). The runs of the full pass didn't log the capture path, so I never found out why it came in
at 76.2 ms. Every run logs its capture path since then.

### Everything, before the `ViewOverlay` change

Overlay frame, ours / tsa: Mi warm tap 74.1 / 90.6 ms, Pixel warm tap 52.8 / 71.3 ms, Pixel cold tap 59.9 / 105.2
ms. On API 29 and up the warm-up now prepares the GPU snapshot, which cut the first switch's main-thread time on
the Pixel 8 Pro from 18-19 ms to 3-6 ms. tsa moves a lot between runs without any change to it: its Mi warm tap
overlay frame was 90.6 ms here and 70.7 ms in the current pass, where ours passes that row by 1.2 ms. The full
tables of this run aren't in the repo, only its failing rows (see "Rows that fail").

### Before any of this (2026-10-28)

This pass ran on whatever `adb install` left: no ahead-of-time code on the Mi 11 Lite (`verify`), all code compiled
on the Pixel 8 Pro (`speed`). Its raw runs were replaced in `results/` and are still in the git history of that
folder. Warm overlay frame, ours / tsa: 84.8 / 91.4 ms on the Mi and 72.3 / 62.8 ms on the Pixel.

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
