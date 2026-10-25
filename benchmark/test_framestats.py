import json
import os
import subprocess
import sys
import unittest

import framestats

HERE = os.path.dirname(os.path.abspath(__file__))
# Expected values come from an awk pass over the fixtures, not from framestats.py.

# Mi 11 Lite, one circle reveal in the example app: 30 rows, the last one not completed yet
# (FrameCompleted older than IntendedVsync).
REVEAL = os.path.join(HERE, 'fixtures', 'gfxinfo-reveal.txt')
# Mi 11 Lite, a reveal while a modal opens: two windows (two PROFILEDATA sections), 58 rows, 2 with Flags=1,
# 1 stale FrameCompleted in the middle of the second section (row 34 of 50).
TWO_WINDOWS = os.path.join(HERE, 'fixtures', 'gfxinfo-two-windows.txt')
# Mi 11 Lite, benchmark smoke run of ours: 27 rows. The first row is the frame after the press: it started
# 77.8 ms late (Vsync - IntendedVsync) and has a stale FrameCompleted, but it did swap.
OURS = os.path.join(HERE, 'fixtures', 'gfxinfo-ours-stall.txt')
OURS_PRESS = 821549545.0
OURS_OVERLAY = OURS_PRESS + 93.3
OURS_END = OURS_PRESS + 652.7
# Mi 11 Lite, benchmark smoke run of theme-switch-animation: 29 rows, 25 of them RTAnimation (Flags=2).
RENDER_THREAD = os.path.join(HERE, 'fixtures', 'gfxinfo-render-thread.txt')
RT_PRESS = 821591665.0
RT_OVERLAY = RT_PRESS + 97.9


def read(path):
    with open(path) as file:
        return file.read()


def frames_of(path):
    return framestats.parse_frames(read(path))


class ParseTest(unittest.TestCase):
    def test_skips_a_stale_last_row(self):
        self.assertEqual(len(frames_of(REVEAL)), 29)

    def test_reads_every_window_skips_flag_1_and_keeps_a_stale_middle_row(self):
        self.assertEqual(len(frames_of(TWO_WINDOWS)), 56)

    def test_a_stale_middle_row_ends_at_swap_buffers_completed(self):
        first = min(frames_of(OURS), key=lambda frame: frame.intended_vsync_ns)
        self.assertEqual(first.intended_vsync_ns, 821549652649258)
        self.assertAlmostEqual(framestats.frame_ms(first), 117.223645, places=6)

    def test_frame_time_is_frame_completed_minus_intended_vsync(self):
        first = min(frames_of(REVEAL), key=lambda frame: frame.intended_vsync_ns)
        self.assertEqual(first.intended_vsync_ns, 817482692343622)
        self.assertAlmostEqual(framestats.frame_ms(first), 27.952864, places=6)

    def test_keeps_render_thread_animation_frames(self):
        self.assertEqual(len(frames_of(RENDER_THREAD)), 29)

    def test_skips_skipped_frames(self):
        text = read(REVEAL).replace('\n0,173953468,', '\n8,173953468,')
        self.assertEqual(len(framestats.parse_frames(text)), 28)

    def test_a_dump_without_profile_data(self):
        text = 'Applications Graphics Acceleration Info:\nNo process found for: expo.modules.themereveal.example\n'
        self.assertFalse(framestats.has_profile_data(text))
        self.assertEqual(framestats.parse_frames(text), [])
        self.assertTrue(framestats.has_profile_data(read(REVEAL)))


class SummaryTest(unittest.TestCase):
    def test_percentiles_vsync_and_janky_over_the_measured_interval(self):
        summary = framestats.summarize(frames_of(REVEAL))
        self.assertEqual(summary['frames'], 29)
        self.assertEqual(summary['p50'], 14.67)
        self.assertEqual(summary['p90'], 18.51)
        self.assertEqual(summary['vsyncMs'], 16.5)
        self.assertEqual(summary['jankyOverMs'], 16.5)
        self.assertEqual(summary['janky'], 6)
        self.assertEqual(summary['jankyShare'], 0.207)

    def test_two_windows_count_a_shared_vsync_once_for_the_interval(self):
        summary = framestats.summarize(frames_of(TWO_WINDOWS))
        self.assertEqual(summary['frames'], 56)
        self.assertEqual(summary['p50'], 18.35)
        self.assertEqual(summary['p90'], 49.7)
        self.assertEqual(summary['vsyncMs'], 16.41)
        self.assertEqual(summary['janky'], 33)

    def test_the_stall_frame_counts(self):
        summary = framestats.summarize(frames_of(OURS), OURS_PRESS, OURS_OVERLAY, OURS_END)
        self.assertEqual(summary['frames'], 27)
        self.assertEqual(summary['p50'], 16.59)
        self.assertEqual(summary['p90'], 25.08)
        self.assertEqual(summary['vsyncMs'], 16.61)
        self.assertEqual(summary['janky'], 13)

    def test_overlay_frame_ends_at_swap_buffers_completed_of_the_first_frame_after_the_press(self):
        ours = framestats.summarize(frames_of(OURS), OURS_PRESS, OURS_OVERLAY, OURS_END)
        self.assertEqual(ours['overlayFrameMs'], 224.9)
        render_thread = framestats.summarize(frames_of(RENDER_THREAD), RT_PRESS, RT_OVERLAY, RT_PRESS + 656.9)
        # Its first frame after the press (+84.4 ms) comes before its overlayAt (+97.9 ms).
        self.assertEqual(render_thread['overlayFrameMs'], 123.4)

    def test_overlay_frame_is_the_first_frame_after_the_press_even_before_overlay_at(self):
        # theme-switch-animation adds its overlay natively before JS hears about it, so the frame at +20 ms
        # shows it.
        press = 1000.0
        frames = [framestats.Frame((press + s) * 1e6, (press + s) * 1e6, (press + s + 9) * 1e6, (press + s + 8) * 1e6)
                  for s in (-16.7, 20.0, 36.7, 53.4)]
        summary = framestats.summarize(frames, press, press + 30, press + 60)
        self.assertEqual(summary['overlayFrameMs'], 28.0)

    def test_overlay_lead_is_how_far_the_first_frame_starts_before_overlay_at(self):
        # Oracle (awk over the fixtures): first frame after the press at +84.4 ms (theme-switch-animation,
        # overlayAt +97.9) and at +107.6 ms (ours, overlayAt +93.3).
        render_thread = framestats.summarize(frames_of(RENDER_THREAD), RT_PRESS, RT_OVERLAY, RT_PRESS + 656.9)
        self.assertEqual(render_thread['overlayLeadMs'], 13.5)
        ours = framestats.summarize(frames_of(OURS), OURS_PRESS, OURS_OVERLAY, OURS_END)
        self.assertEqual(ours['overlayLeadMs'], -14.3)

    def test_missed_vsyncs_and_late_frames(self):
        ours = framestats.summarize(frames_of(OURS), OURS_PRESS, OURS_OVERLAY, OURS_END)
        self.assertEqual(ours['missedVsyncs'], 11)
        self.assertEqual(ours['lateFrames'], 1)
        render_thread = framestats.summarize(frames_of(RENDER_THREAD), RT_PRESS, RT_OVERLAY, RT_PRESS + 656.9)
        self.assertEqual(render_thread['missedVsyncs'], 5)
        self.assertEqual(render_thread['lateFrames'], 0)

    def test_the_window_follows_the_frames_past_the_js_end(self):
        # JS run ends 100 ms before the last animation frame, and the window still picks up every
        # frame.
        summary = framestats.summarize(frames_of(RENDER_THREAD), RT_PRESS, RT_OVERLAY, RT_PRESS + 552.6)
        self.assertEqual(summary['frames'], 29)

    def test_the_window_stops_at_a_gap_of_more_than_two_and_a_half_intervals(self):
        # End before the first frame (press +107.6 ms): that frame is taken, the next one comes 108
        # ms later.
        summary = framestats.summarize(frames_of(OURS), OURS_PRESS, OURS_OVERLAY, OURS_PRESS + 100)
        self.assertEqual(summary['frames'], 1)

    def gap_window(self, gap_intervals):
        # 16.7 ms frames from the press to the JS end, then one frame gap_intervals later.
        interval = 16.7
        starts = [1000 + interval * n for n in range(10)]
        starts.append(starts[-1] + gap_intervals * interval)
        frames = [framestats.Frame(s * 1e6, s * 1e6, (s + 8) * 1e6, (s + 8) * 1e6) for s in starts]
        return framestats.window(frames, 1000, starts[9], interval)

    def test_the_window_takes_a_frame_just_under_two_and_a_half_intervals_later(self):
        # One missed vsync (2 intervals) plus jitter stays in the window.
        self.assertEqual(len(self.gap_window(2.49)), 11)

    def test_the_window_stops_at_a_frame_just_over_two_and_a_half_intervals_later(self):
        self.assertEqual(len(self.gap_window(2.51)), 10)

    def test_the_window_starts_at_the_press(self):
        summary = framestats.summarize(frames_of(REVEAL), 817482745.0, None, 817483200.0)
        self.assertEqual(summary['frames'], 28)
        self.assertIsNone(summary['overlayFrameMs'])

    def test_empty(self):
        self.assertEqual(
            framestats.summarize([]),
            {
                'frames': 0,
                'p50': None,
                'p90': None,
                'vsyncMs': None,
                'janky': None,
                'jankyShare': None,
                'jankyOverMs': None,
                'overlayFrameMs': None,
                'overlayLeadMs': None,
                'missedVsyncs': None,
                'lateFrames': None,
            },
        )

    def test_cli_prints_json(self):
        output = subprocess.run(
            [
                sys.executable,
                '-I',
                os.path.join(HERE, 'framestats.py'),
                OURS,
                '--press-ms',
                str(OURS_PRESS),
                '--overlay-ms',
                str(OURS_OVERLAY),
                '--end-ms',
                str(OURS_END),
            ],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        summary = json.loads(output)
        self.assertEqual((summary['frames'], summary['overlayFrameMs']), (27, 224.9))


if __name__ == '__main__':
    unittest.main()
