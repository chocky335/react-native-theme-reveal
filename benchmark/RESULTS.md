# Benchmark results

Measured on 2026-11-08 with the RenderThread reveal build. I ran two full passes on it:
- Pass A: 64 of 64 rows pass.
- Pass B: 63 of 64 rows pass. The one that fails is the Mi 11 Lite JS max gap in the warm hold form, see "The row
  that fails" below.

The files in `results/` are from pass B, and the tables right below come from them. Pass A's files aren't in this
repo, but its tables are further down. `report.py` made every table from each pass's files. See
`benchmark/README.md` for what each metric means and the run rules.

Since these passes, the code has only changed in the README, in comments, in the text of debug log lines and in
the module's destroy. The debug log was off in every run and no run destroyed the module, so no run took a path
whose behaviour changed. The previous build and the passes before it are under "History".

## Setup

| | Mi 11 Lite | Pixel 8 Pro |
|---|---|---|
| Model (`ro.product.model`) | `M2101K9AG` | `Pixel 8 Pro` (tables: `Pixel-8-Pro`) |
| OS | Android 13 (API 33), MIUI | Android 17 (API 37) |
| Screen | 1080 x 2400 | 1008 x 2244 |
| Display modes | 60 and 90 Hz, default 60 Hz | 60 and 120 Hz |
| Rate the app drew at | 60 Hz (16.49-16.90 ms) for every library | 60 Hz (16.52-16.66 ms), Skia 30 Hz (32.76-33.00 ms) |
| Animation scales | 1.0 / 1.0 / 1.0 | 1.0 / 1.0 / 1.0 |

- Build: release, `arm64-v8a`. I reran the JS bundle task for it (`:app:createBundleReleaseJsAndAssets --rerun`)
  so the APK can't carry an old bundle. The same APK ran in both passes on both phones, and it holds all three
  libraries.
- `android.sh` compiles the whole app with `cmd package compile -f -m speed` before each folder, and stops if
  `dumpsys package` doesn't show `speed` afterwards, so every library ran ahead-of-time code. This doesn't measure
  what our Baseline Profile does for a Play install.
- Before each pass, `pm list packages expo.modules.themereveal` showed only the example package on both phones.
  The same check showed animation scales 1.0 / 1.0 / 1.0, the debug log tag empty and the screen unlocked.
- Warm passes ran one discarded warm-up run per library, then 6 interleaved rounds, with the starting library
  rotating so each one starts two rounds. Cold passes ran 3 rounds: each run force-stops the app, waits 1 s, opens
  the benchmark screen and switches once. The package's own `warmUp` still runs, because the screen mounts
  `useStart` about 1.5 s before the tap.
- Every library got 6/6 valid warm runs and 3/3 valid cold runs in every folder of both passes.
- There are two tap forms, the same for every library. `input tap` presses for about 0 ms, which no finger does
  (output in `results/` and `results/cold/`). The hold form presses for 100 ms with `input swipe x y x y 100`
  (`BENCH_HOLD_MS=100`, output in `results/hold/` and `results/cold-hold/`). In both, t0 is the release
  (`onPress`), so the hold isn't in any metric. Ours takes its snapshot on press-in (`pressCapture`), and the
  other libraries do nothing on press-in.
- Each `ours` run writes its capture path (`capture=gpu` or `capture=pixelcopy`) into its BENCH line and its JSON.
  All 36 `ours` runs of each pass have `capture=gpu`: 12 warm tap, 12 warm hold, 6 cold tap and 6 cold hold.
- Every `ours` run is a `reveal` with the default circle, on API 29 and up, from a GPU snapshot, so it takes the
  RenderThread reveal unless it falls back to the UI-thread one. The JSON files don't record a fallback, and the
  debug log was off in these passes. I had it on in three debug passes on the phones beforehand, each with 12
  switches plus the warm-ups: one on the Mi 11 Lite with this build, and one on each phone with an earlier build of
  the same design. All three got 14 of 14 second snapshots and 0 fallbacks. In the two on the earlier build, a
  pixel probe showed every second snapshot differed from the first, so it had the new theme.
- Between switches the package keeps one capture target the size of the activity window: a `HardwareRenderer`,
  its surface and one allocated GPU buffer, about 10 MB at 1080 x 2400 (4 bytes per pixel). I haven't measured it
  with `dumpsys meminfo`. The benchmark only measures time.
- Every value is a median over the valid runs. Raw dumps and per-run JSON files of pass B are in `results/`.

On React Native 0.86 (Fabric), `theme-switch-animation` shows no visible circle. A system trace on the Mi saw its
second capture run before its theme mount (that's from the trace order only, I didn't check it on screen). Its
overlay and frame numbers are still real, so it stays in the tables and counts for pass/fail.

## Pass/fail rule

For every metric on each phone, our median must be no more than 5% above the best alternative's median. Lower is
better for every metric. An alternative that drew at a frame interval more than 10% off ours is left out of the
frame metrics (p50, p90, janky, late frames, missed vsyncs), which here means Skia on the Pixel 8 Pro at 30 Hz.
Four forms of 16 rows make 64 rows per pass. With Skia in the Pixel frame rows, 7 more rows would fail in each
pass:
- Pass B: frame p50 in all four forms, frame p90 in cold tap, and janky share in cold tap and cold hold.
- Pass A: frame p50 in all four forms, frame p90 in cold hold, and janky share in cold tap and cold hold.

## What changed since the previous build

The previous build failed 8 of 64 rows (2026-11-06, see "History"). Since then:
- On API 29 and up, the default circle `reveal` runs on the RenderThread with
  `ViewAnimationUtils.createCircularReveal`. Before, a UI-thread animator drew the overlay again in every frame.
- The circle grows on a second GPU snapshot. The package takes it one frame after the theme's mount frame is
  committed, into an output it prepares while JS renders.
- During a press-in hold, the package imports the held GPU snapshot as a GPU texture, so the overlay's first frame
  doesn't have to.
- `inverseReveal` shrinks a circular clip on the old snapshot on the RenderThread, with no second snapshot.
- One capture target stays prepared between switches, and the package releases each capture's renderer on the
  copy thread (see "Earlier runs of this design").
- The UI-thread reveal still runs for a custom shape, `wipe`, `crossfade`, API below 29, a `PixelCopy` snapshot,
  and a switch whose second snapshot fails.
- What you see is different: inside the circle the new screen is a still image for the reveal's duration, where
  before it was the live app.

Our Pixel 8 Pro frame p50 is higher than in the previous build: warm tap 7.7 ms against 5.5 ms. It still passes
against `theme-switch-animation` (8.2 ms), and no trace shows the cause.

One known limit is force dark (a light theme in night mode). The code has a fix for it, but I haven't checked it on
a device. The example app is DayNight, so force dark never applies there.

## Pass B

These are the files in `results/`. Each folder gets the same two commands, the second one printing the pass/fail
rows:

```
python3 benchmark/report.py benchmark/results/*.json
python3 benchmark/report.py --gate benchmark/results/*.json
python3 benchmark/report.py benchmark/results/hold/*.json
python3 benchmark/report.py --gate benchmark/results/hold/*.json
python3 benchmark/report.py benchmark/results/cold/*.json
python3 benchmark/report.py --gate benchmark/results/cold/*.json
python3 benchmark/report.py benchmark/results/cold-hold/*.json
python3 benchmark/report.py --gate benchmark/results/cold-hold/*.json
```

| Form | Device | Library | Runs | Overlay frame (ms) | Snapshot, JS (ms) | Frame p50 (ms) | Frame p90 (ms) | Janky (%) | Late frames | Missed vsyncs | JS max gap (ms) | Frame interval (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Warm tap | M2101K9AG | ours | 6/6 | 57.4 | 47.2 | 15.3 | 20.6 | 38 | 0 | 2 | 43.0 | 16.7 |
| Warm tap | M2101K9AG | theme-switch-animation | 6/6 | 88.7 | 73.2 | 17.7 | 24.0 | 67 | 0.5 | 3.5 | 45.0 | 16.7 |
| Warm tap | M2101K9AG | skia | 6/6 | 431.9 | 414.0 | 18.9 | 22.8 | 67 | 0 | 25 | 391.1 | 16.6 |
| Warm tap | Pixel-8-Pro | ours | 6/6 | 49.4 | 45.9 | 7.7 | 9.3 | 0 | 0 | 2 | 25.4 | 16.6 |
| Warm tap | Pixel-8-Pro | theme-switch-animation | 6/6 | 64.9 | 66.2 | 8.2 | 9.5 | 3 | 0 | 3 | 32.2 | 16.6 |
| Warm tap | Pixel-8-Pro | skia | 6/6 | 330.8 | 291.4 | 6.9 | 8.9 | 0 | 0 | 9.5 | 267.9 | 32.9 |
| Warm hold | M2101K9AG | ours | 6/6 | 41.0 | 31.6 | 16.2 | 21.8 | 44 | 0 | 1 | 44.1 | 16.8 |
| Warm hold | M2101K9AG | theme-switch-animation | 6/6 | 94.2 | 80.2 | 17.6 | 24.3 | 69 | 0.5 | 4 | 39.7 | 16.7 |
| Warm hold | M2101K9AG | skia | 6/6 | 448.8 | 422.7 | 18.9 | 24.0 | 67 | 0.5 | 26 | 405.2 | 16.7 |
| Warm hold | Pixel-8-Pro | ours | 6/6 | 39.1 | 31.7 | 8.0 | 9.5 | 0 | 0 | 1 | 28.2 | 16.6 |
| Warm hold | Pixel-8-Pro | theme-switch-animation | 6/6 | 64.5 | 64.0 | 8.5 | 9.5 | 6 | 0 | 2 | 28.9 | 16.6 |
| Warm hold | Pixel-8-Pro | skia | 6/6 | 322.9 | 283.2 | 7.0 | 9.3 | 0 | 0 | 9 | 268.4 | 32.9 |
| Cold tap | M2101K9AG | ours | 3/3 | 65.5 | 58.0 | 15.9 | 19.4 | 41 | 0 | 3 | 27.2 | 16.6 |
| Cold tap | M2101K9AG | theme-switch-animation | 3/3 | 88.0 | 84.1 | 17.1 | 22.2 | 57 | 1 | 3 | 36.5 | 16.6 |
| Cold tap | M2101K9AG | skia | 3/3 | 423.9 | 387.1 | 19.2 | 24.9 | 69 | 0 | 25 | 362.4 | 16.6 |
| Cold tap | Pixel-8-Pro | ours | 3/3 | 53.8 | 47.5 | 8.5 | 11.3 | 3 | 0 | 2 | 27.7 | 16.6 |
| Cold tap | Pixel-8-Pro | theme-switch-animation | 3/3 | 89.5 | 87.6 | 8.8 | 14.6 | 10 | 0 | 3 | 28.8 | 16.6 |
| Cold tap | Pixel-8-Pro | skia | 3/3 | 374.7 | 312.9 | 6.8 | 10.5 | 0 | 0 | 11 | 290.7 | 32.8 |
| Cold hold | M2101K9AG | ours | 3/3 | 43.0 | 33.9 | 16.1 | 19.5 | 32 | 0 | 1 | 41.1 | 16.7 |
| Cold hold | M2101K9AG | theme-switch-animation | 3/3 | 107.7 | 67.6 | 17.0 | 22.1 | 57 | 1 | 3 | 55.4 | 16.8 |
| Cold hold | M2101K9AG | skia | 3/3 | 500.4 | 419.0 | 18.0 | 22.5 | 66 | 0 | 28 | 399.1 | 16.5 |
| Cold hold | Pixel-8-Pro | ours | 3/3 | 39.1 | 32.2 | 8.3 | 11.0 | 6 | 0 | 1 | 24.6 | 16.6 |
| Cold hold | Pixel-8-Pro | theme-switch-animation | 3/3 | 102.2 | 65.3 | 8.9 | 17.6 | 13 | 1 | 3 | 46.3 | 16.6 |
| Cold hold | Pixel-8-Pro | skia | 3/3 | 365.8 | 310.4 | 6.9 | 11.0 | 0 | 0 | 10 | 289.5 | 32.9 |

### Pass/fail table

Each cell is ours / the best alternative (the limit) and the result. "tsa" is `theme-switch-animation`, here and
below.

| Device | Metric | Warm tap | Warm hold | Cold tap | Cold hold |
|---|---|---|---|---|---|
| Mi 11 Lite | Overlay frame (ms) | 57.4 / 88.7 tsa (93.1) PASS | 41.0 / 94.2 tsa (99.0) PASS | 65.5 / 88.0 tsa (92.4) PASS | 43.0 / 107.7 tsa (113.1) PASS |
| Mi 11 Lite | Snapshot, JS (ms) | 47.2 / 73.2 tsa (76.8) PASS | 31.6 / 80.2 tsa (84.3) PASS | 58.0 / 84.1 tsa (88.3) PASS | 33.9 / 67.6 tsa (71.0) PASS |
| Mi 11 Lite | Frame p50 (ms) | 15.3 / 17.7 tsa (18.6) PASS | 16.2 / 17.6 tsa (18.5) PASS | 15.9 / 17.1 tsa (17.9) PASS | 16.1 / 17.0 tsa (17.9) PASS |
| Mi 11 Lite | Frame p90 (ms) | 20.6 / 22.8 skia (24.0) PASS | 21.8 / 24.0 skia (25.2) PASS | 19.4 / 22.2 tsa (23.3) PASS | 19.5 / 22.1 tsa (23.2) PASS |
| Mi 11 Lite | Janky (%) | 38 / 67 skia (71) PASS | 44 / 67 skia (71) PASS | 41 / 57 tsa (60) PASS | 32 / 57 tsa (60) PASS |
| Mi 11 Lite | Late frames | 0 / 0 skia (0.00) PASS | 0 / 0.5 skia (0.53) PASS | 0 / 0 skia (0.00) PASS | 0 / 0 skia (0.00) PASS |
| Mi 11 Lite | Missed vsyncs | 2 / 3.5 tsa (3.68) PASS | 1 / 4 tsa (4.20) PASS | 3 / 3 tsa (3.15) PASS | 1 / 3 tsa (3.15) PASS |
| Mi 11 Lite | JS max gap (ms) | 43.0 / 45.0 tsa (47.3) PASS | 44.1 / 39.7 tsa (41.7) FAIL | 27.2 / 36.5 tsa (38.3) PASS | 41.1 / 55.4 tsa (58.2) PASS |
| Pixel 8 Pro | Overlay frame (ms) | 49.4 / 64.9 tsa (68.2) PASS | 39.1 / 64.5 tsa (67.7) PASS | 53.8 / 89.5 tsa (94.0) PASS | 39.1 / 102.2 tsa (107.3) PASS |
| Pixel 8 Pro | Snapshot, JS (ms) | 45.9 / 66.2 tsa (69.6) PASS | 31.7 / 64.0 tsa (67.1) PASS | 47.5 / 87.6 tsa (92.0) PASS | 32.2 / 65.3 tsa (68.6) PASS |
| Pixel 8 Pro | Frame p50 (ms) | 7.7 / 8.2 tsa (8.6) PASS | 8.0 / 8.5 tsa (8.9) PASS | 8.5 / 8.8 tsa (9.3) PASS | 8.3 / 8.9 tsa (9.4) PASS |
| Pixel 8 Pro | Frame p90 (ms) | 9.3 / 9.5 tsa (10.0) PASS | 9.5 / 9.5 tsa (9.9) PASS | 11.3 / 14.6 tsa (15.3) PASS | 11.0 / 17.6 tsa (18.5) PASS |
| Pixel 8 Pro | Janky (%) | 0 / 3 tsa (3) PASS | 0 / 6 tsa (7) PASS | 3 / 10 tsa (10) PASS | 6 / 13 tsa (14) PASS |
| Pixel 8 Pro | Late frames | 0 / 0 tsa (0.00) PASS | 0 / 0 tsa (0.00) PASS | 0 / 0 tsa (0.00) PASS | 0 / 1 tsa (1.05) PASS |
| Pixel 8 Pro | Missed vsyncs | 2 / 3 tsa (3.15) PASS | 1 / 2 tsa (2.10) PASS | 2 / 3 tsa (3.15) PASS | 1 / 3 tsa (3.15) PASS |
| Pixel 8 Pro | JS max gap (ms) | 25.4 / 32.2 tsa (33.8) PASS | 28.2 / 28.9 tsa (30.4) PASS | 27.7 / 28.8 tsa (30.2) PASS | 24.6 / 46.3 tsa (48.6) PASS |

## Pass A

Pass A's files aren't in this repo, but they have the same folder layout as `results/` (warm tap, `hold/`,
`cold/` and `cold-hold/`), and the same commands ran on them. All 64 rows pass, so I've left out its pass/fail
tables.

| Form | Device | Library | Runs | Overlay frame (ms) | Snapshot, JS (ms) | Frame p50 (ms) | Frame p90 (ms) | Janky (%) | Late frames | Missed vsyncs | JS max gap (ms) | Frame interval (ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Warm tap | M2101K9AG | ours | 6/6 | 64.0 | 51.0 | 16.0 | 19.7 | 39 | 0 | 2 | 35.1 | 16.7 |
| Warm tap | M2101K9AG | theme-switch-animation | 6/6 | 92.8 | 70.4 | 17.5 | 24.8 | 66 | 1 | 3.5 | 47.5 | 16.7 |
| Warm tap | M2101K9AG | skia | 6/6 | 428.6 | 404.7 | 19.4 | 23.6 | 75 | 0.5 | 25 | 381.0 | 16.6 |
| Warm tap | Pixel-8-Pro | ours | 6/6 | 46.4 | 44.0 | 7.8 | 9.0 | 0 | 0 | 2 | 24.3 | 16.6 |
| Warm tap | Pixel-8-Pro | theme-switch-animation | 6/6 | 75.7 | 70.0 | 8.3 | 9.9 | 2 | 0 | 4 | 31.1 | 16.6 |
| Warm tap | Pixel-8-Pro | skia | 6/6 | 332.6 | 304.5 | 6.9 | 8.7 | 0 | 0 | 9.5 | 274.6 | 32.9 |
| Warm hold | M2101K9AG | ours | 6/6 | 41.1 | 33.0 | 15.8 | 21.0 | 38 | 0 | 1 | 37.8 | 16.7 |
| Warm hold | M2101K9AG | theme-switch-animation | 6/6 | 95.4 | 80.0 | 21.0 | 25.4 | 79 | 1 | 4 | 39.8 | 16.7 |
| Warm hold | M2101K9AG | skia | 6/6 | 466.1 | 430.9 | 18.6 | 23.9 | 69 | 0.5 | 27 | 411.4 | 16.6 |
| Warm hold | Pixel-8-Pro | ours | 6/6 | 38.0 | 31.4 | 8.1 | 9.3 | 0 | 0 | 1 | 25.8 | 16.6 |
| Warm hold | Pixel-8-Pro | theme-switch-animation | 6/6 | 61.2 | 55.7 | 8.4 | 9.8 | 6 | 0 | 2 | 30.0 | 16.6 |
| Warm hold | Pixel-8-Pro | skia | 6/6 | 317.6 | 288.5 | 6.8 | 9.9 | 0 | 0 | 9 | 268.9 | 32.9 |
| Cold tap | M2101K9AG | ours | 3/3 | 54.3 | 55.1 | 15.5 | 19.6 | 36 | 0 | 2 | 29.5 | 16.7 |
| Cold tap | M2101K9AG | theme-switch-animation | 3/3 | 105.5 | 84.1 | 21.6 | 23.9 | 79 | 1 | 4 | 41.2 | 16.7 |
| Cold tap | M2101K9AG | skia | 3/3 | 447.0 | 399.5 | 19.5 | 23.2 | 74 | 0 | 26 | 362.0 | 16.6 |
| Cold tap | Pixel-8-Pro | ours | 3/3 | 51.3 | 46.1 | 8.6 | 11.3 | 3 | 0 | 2 | 27.2 | 16.6 |
| Cold tap | Pixel-8-Pro | theme-switch-animation | 3/3 | 91.4 | 85.2 | 8.8 | 14.4 | 10 | 0 | 3 | 31.1 | 16.6 |
| Cold tap | Pixel-8-Pro | skia | 3/3 | 370.8 | 314.7 | 7.2 | 12.2 | 0 | 0 | 11 | 288.7 | 32.9 |
| Cold hold | M2101K9AG | ours | 3/3 | 39.5 | 33.0 | 15.3 | 21.4 | 47 | 0 | 1 | 43.4 | 16.7 |
| Cold hold | M2101K9AG | theme-switch-animation | 3/3 | 108.3 | 81.3 | 17.1 | 23.2 | 63 | 1 | 4 | 66.0 | 16.8 |
| Cold hold | M2101K9AG | skia | 3/3 | 480.1 | 419.0 | 18.8 | 24.6 | 72 | 1 | 28 | 398.9 | 16.6 |
| Cold hold | Pixel-8-Pro | ours | 3/3 | 42.6 | 33.4 | 8.5 | 12.4 | 6 | 0 | 1 | 29.9 | 16.6 |
| Cold hold | Pixel-8-Pro | theme-switch-animation | 3/3 | 102.4 | 66.1 | 8.8 | 17.0 | 13 | 1 | 3 | 46.2 | 16.6 |
| Cold hold | Pixel-8-Pro | skia | 3/3 | 381.1 | 310.8 | 7.0 | 9.7 | 0 | 0 | 11 | 290.2 | 32.9 |

## The row that fails

Mi 11 Lite, warm hold, JS max gap in pass B: ours 44.1 ms against 39.7 ms for `theme-switch-animation` (limit
41.7 ms). The same row passes in pass A: 37.8 against 39.8 ms (limit 41.7 ms).

Here's our JS max gap per run in this row across the four full passes of this design (from the JSON files, in
round order). Runs 1 and 2 are on the earlier build, see the next section.

| Pass | ours per run (ms) | ours median | tsa median | Limit | Result |
|---|---|---|---|---|---|
| Run 1, earlier build | 29.8, 28.2, 48.8, 38.1, 31.8, 42.4 | 35.0 | 42.2 | 44.3 | PASS |
| Run 2, earlier build | 49.9, 51.1, 38.3, 25.4, 31.4, 28.4 | 34.8 | 39.4 | 41.4 | PASS |
| Pass A | 26.9, 25.6, 51.2, 47.8, 28.9, 46.8 | 37.8 | 39.8 | 41.7 | PASS |
| Pass B | 43.0, 41.8, 49.2, 47.7, 44.2, 44.0 | 44.1 | 39.7 | 41.7 | FAIL |

The runs fall into two groups. Of the 24, 9 are 25.4-31.8 ms and 13 are 41.8-51.2 ms, with two at 38.1 and 38.3 ms
in between. In pass B all 6 runs landed in the slow group, so the median is slow.

I took a system trace on the same APK after pass B (Mi 11 Lite, warm hold, 6 `ours` runs and 2 tsa runs):
- The package caused none of our max gaps. The main thread didn't wait on any of our RenderThread work inside a
  max-gap interval, and none of our frames fell inside one.
- In the slow runs (3 of 6, 45.6-50.5 ms), the scheduler kept the app on the little cores from the release to
  +90 ms while the big cores sat idle. The app's own JS theme render ran there at 576-1248 MHz and took 46-53 ms.
  That render is the gap.
- In the fast runs (30.6-32.2 ms) and one middle run (40.8 ms), JS rendered on a big core in 13-24 ms, and the gap
  came from the mount frame or a late vsync.
- tsa renders on a little core too, but 45-50 ms later (+77 to +80 ms after the release). Its software capture
  runs first and pushes up the little cores' clock, so its render takes 25-26 ms at 1613-1709 MHz.
- The trace didn't record pass B itself. All 6 of its runs being slow fits a pass where the scheduler kept the app
  on the little cores, but that's my inference.

Our own main-thread cost per switch is the frame of the second snapshot: 10.4-15.6 ms of `doFrame`, of which
5.9-11.1 ms is waiting for the RenderThread. In 6 of 6 traced runs that frame stayed inside one vsync period and
outside the max-gap interval.

The only levers left are a CPU boost and a later theme render. A boost keeps the CPU busy to raise its clock,
which games the metric and costs power. A later render makes the switch slower. I'm not doing either, so no code
change targets this row.

## Earlier runs of this design

I ran two full passes on an earlier build of the same design. Run 1 passed 64 of 64 rows, and run 2 passed 59 of
64, with all 5 failures in the cold tap form:

| Phone | Metric | ours | Best alternative | Limit (+5%) |
|---|---|---|---|---|
| M2101K9AG | Overlay frame (ms) | 89.6 | 84.3 (theme-switch-animation) | 88.5 |
| M2101K9AG | Snapshot, JS (ms) | 82.6 | 66.9 (theme-switch-animation) | 70.2 |
| M2101K9AG | Late frames | 1 | 0 (theme-switch-animation) | 0.00 |
| M2101K9AG | Missed vsyncs | 4 | 3 (theme-switch-animation) | 3.15 |
| Pixel-8-Pro | Missed vsyncs | 4 | 3 (theme-switch-animation) | 3.15 |

A system trace on the Mi recorded the first switch in a fresh process (4 `ours` and 4 tsa launches). The first
capture made a new renderer, surface and buffer. In the slow runs its RenderThread frame also ran on a little core
and took 17.5-19.4 ms instead of 7.1-8.1 ms, so the image came about 12 ms later and the attach ended 4-7 ms after
a vsync. That cost one full vsync (16.7 ms).

The fix keeps one capture target the size of the activity window between switches. The warm-up prepares it, the
package prepares the next one 500 ms after each switch, and the next capture draws into it. A later fix moved the
release of each capture's renderer to the copy thread, after a Mi debug pass measured it at 1.0-19.4 ms on the
main thread. Passes A and B are on the build with both fixes, and the cold tap form passes in both (Mi overlay
frame: ours 54.3 and 65.5 ms, tsa 105.5 and 88.0 ms).

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
- No run had its first frame more than 150 ms before the JS overlay time (`overlayLeadMs`). In the previous
  build's pass the largest lead was 50.0 ms (`theme-switch-animation`, Pixel cold hold), and ours was 0.8-8.6 ms.
  That check only catches gross errors, though: a first frame one interval too early (16.7 ms at 60 Hz) stays
  under it.
- The Pixel 8 Pro drew at 60 Hz in every pass, below its 120 Hz panel default (an earlier run measured 120 Hz on
  the same phone). I haven't found out why. Ours and theme-switch-animation drew at the same rate, so the
  comparison holds, but one frame costs 16.7 ms here instead of 8.3 ms.
- With the threshold at the frame interval, Janky (%) is high for every library on the Mi: 32-77% across the four
  tables of the previous build. HWUI pipelines frames, so a frame that takes longer than one interval isn't
  necessarily dropped. Late frames and missed vsyncs are a better signal for real drops.
- The janky threshold is the interval each library drew at. On the Pixel, Skia drew at 30 Hz (32.9-33.0 ms in the
  previous build's pass), so its threshold doubled and its frame times cover half as many frames. That means its
  frame p50, p90 and janky values on the Pixel can't be compared with the other two. Its missed vsyncs are counted
  in 33 ms intervals, so its 9-11 there are about 18-22 at 60 Hz.
- A janky share is a share of the frames in the window. In the previous build's pass, ours drew 29 frames there on
  the Pixel and `theme-switch-animation` 31, so one janky frame was 3.4% for ours and 3.2% for it.
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
| Everything (2026-11-06, the previous build) | 3 of 16 | 3 of 16 | 1 of 16 | 1 of 16 |
| RenderThread reveal, earlier build, run 1 | 0 of 16 | 0 of 16 | 0 of 16 | 0 of 16 |
| RenderThread reveal, earlier build, run 2 | 0 of 16 | 0 of 16 | 5 of 16 | 0 of 16 |
| RenderThread reveal, pass A | 0 of 16 | 0 of 16 | 0 of 16 | 0 of 16 |
| RenderThread reveal, pass B | 0 of 16 | 1 of 16 | 0 of 16 | 0 of 16 |

The press-in and GPU capture builds each started from the same code as the first row, so each one measures that
change alone. Without the warm-up, their cold tap rows fail more. The GPU capture build only ran the tap form,
since it had no press-in capture. The first row ran without the `speed` compile, so the Mi 11 Lite ran `verify`
there (no ahead-of-time code). Every later pass compiled with `speed`. The RenderThread reveal builds start from
the previous build.

### Previous build (2026-11-06)

The warm-up, the JS gap fix, the press-in capture and the GPU snapshot, with the reveal still on the UI thread. It
failed 8 of 64 rows, while the overlay frame and snapshot JS passed in every form. Overlay frame, ours / tsa:

| Device | Warm tap | Warm hold | Cold tap | Cold hold |
|---|---|---|---|---|
| Mi 11 Lite | 73.0 / 70.7 | 45.3 / 98.9 | 68.0 / 119.5 | 42.3 / 104.5 |
| Pixel 8 Pro | 46.2 / 56.2 | 40.5 / 58.4 | 68.7 / 85.9 | 49.7 / 104.2 |

The failing rows, ours / best alternative (limit):
- Mi 11 Lite frame p90, warm hold 24.8 / 21.9 tsa (23.0) and cold tap 23.3 / 21.0 tsa (22.1). Ours animated the
  reveal from the UI thread and drew the full-window snapshot through a clip path in every frame, while tsa's
  RenderThread animator skips a vsync instead of drawing a slow frame. In the hold form the Mi's Adreno 618 also
  went to slumber after the press-in capture and ran at its lowest clock (180 MHz) for the first frames, so those
  cost 2-3 times more than in the tap form. That's what the RenderThread reveal is for.
- Pixel 8 Pro janky share, warm hold: 3.4 / 3.2 tsa (3.39). Both medians are 1 janky frame. The first frame after
  a press-in capture got more expensive the longer the hold (20.9 ms at 100 ms).
- Mi 11 Lite late frames, warm tap and warm hold: 0.5 / 0 skia (0) in both. They passed before the overlay moved
  into the window root's `ViewOverlay`, and I couldn't rule out a regression from that change.
- Three rows that looked like noise, since ours didn't move and tsa did: Mi warm tap missed vsyncs 3 / 2.5 tsa
  (2.62), Pixel warm tap JS max gap 27.0 / 25.4 tsa (26.7), Pixel cold hold janky 10 / 6 tsa (7).

### Warm-up (2026-10-31)

The warm-up, the kept bitmap and the `start` parser. Ours / tsa, before and after:
- Mi cold JS max gap: 93.9 / 41.2 to 42.0 / 37.9 ms. The old `start` conversion ran Kotlin reflection on the main
  thread on the first `start` (85-102 ms on the Mi, 18-27 ms on the Pixel).
- Pixel overlay frame: cold 121.2 / 96.7 to 89.3 / 92.4 ms, warm 72.3 / 62.8 to 61.3 / 58.5 ms. Mi cold overlay
  frame got worse: 89.0 / 123.1 to 100.0 / 93.2 ms.
- The copy into a kept bitmap took 19-23 ms on the Pixel 8 Pro, against 39-43 ms into a new one.

The `speed` compile started with this pass and moved `theme-switch-animation` a lot on the Mi (warm overlay frame
91.4 to 81.2 ms, cold 123.1 to 93.2 ms), while Skia barely moved (warm 432.9 to 436.2 ms, cold 451.2 to 456.0 ms).

### JS gap fix

`prepare` used to resolve just before a frame's JS timers ran, so the theme render held back the
`requestAnimationFrame` tick that was due. tsa applies its theme from a `setTimeout`, right after a tick. Now
`prepare` resolves from a `Choreographer` frame callback, after React Native's timers, or from a 100 ms main-thread
timeout. In short runs the Pixel JS max gap went from 37.0 / 27.9 to 27.7 / 29.5 ms, at a cost of +7.3 ms median
snapshot JS on the Pixel. On the Mi that row still passed, once by 0.4 ms.

### Press-in capture and GPU capture, each alone

With a 100 ms hold the press-in snapshot is ready at release, so the warm overlay frame dropped to about half:
76.4 to 41.5 ms on the Mi (tsa 94.4 ms), 72.7 to 39.5 ms on the Pixel (tsa 63.6 ms). The tap form gained almost
nothing. The Mi frame p90 hold row first failed here (23.0 vs 21.6 ms warm, 27.7 vs 21.0 ms cold).

The GPU capture alone gave a warm overlay frame of 76.2 / 89.5 ms on the Mi and 68.5 / 64.7 ms on the Pixel, with
all 4 warm failures on the Pixel (JS max gap 32.2 / 27.1 ms among them). An early prototype had measured 52.0 ms
against 82.6 ms on the Mi (4 runs each). The full pass didn't log the capture path, so I never found out why it
came in at 76.2 ms. Every run has logged its capture path since.

### Everything, before the `ViewOverlay` change

Overlay frame, ours / tsa: Mi warm tap 74.1 / 90.6 ms, Pixel warm tap 52.8 / 71.3 ms, Pixel cold tap 59.9 / 105.2
ms. The warm-up now prepared the GPU snapshot on API 29 and up, which cut the first switch's main-thread time on
the Pixel 8 Pro from 18-19 ms to 3-6 ms. tsa moves a lot between runs without any change to it: its Mi warm tap
overlay frame was 90.6 ms here and 70.7 ms in the previous build's pass, where ours passed that row by 1.2 ms. The
full tables of this run aren't in the repo.

### Before any of this (2026-10-28)

This pass ran on whatever `adb install` left: no ahead-of-time code on the Mi 11 Lite (`verify`), all code compiled
on the Pixel 8 Pro (`speed`). Its raw runs were replaced in `results/` and are still in the git history of that
folder. Warm overlay frame, ours / tsa: 84.8 / 91.4 ms on the Mi and 72.3 / 62.8 ms on the Pixel.

Ours failed 4 rows then, all against `theme-switch-animation`: the Mi JS max gap (39.0 vs 37.0 ms, +5.4% against a
38.9 ms limit), and on the Pixel the overlay frame (72.3 vs 62.8 ms, +15%), missed vsyncs (4 vs 3) and JS max gap
(28.0 vs 25.1 ms, +12%).

The overlay frame loss came from the `PixelCopy` readback, which took 25-47 ms on the Mi for a 1080 x 2400 window,
mostly waiting on the GPU (a `simpleperf` cycle profile found only about 3 ms of CPU in it).
`theme-switch-animation` draws the view tree into a software `Canvas` instead (`rootView.draw(canvas)`,
`ThemeSwitchAnimationModule.java:101-105` in its 0.8.0 Android source), on `mqt_v_native`. On the first switch in
a fresh process ours lost in seven places, including the Pixel overlay frame (121.2 vs 96.7 ms) and the Mi JS max
gap (93.9 vs 41.2 ms).

Three Android fixes before that pass had brought our Mi overlay frame from 118.3 ms down to 84.8 ms: the
`PixelCopy` request moved off the main thread to a `HandlerThread` (it blocked the main thread for 41-47 ms on API
26-33), `Bitmap.prepareToDraw()` moved the 3-7 ms texture upload out of the overlay's first frame, and on API 26-33
the result posts to the front of the main queue (`postAtFrontOfQueue`) so the overlay makes the pending frame. The
theme mount (15-45 ms on the main thread on the Mi) costs the same for all three libraries.
