import json
import os
import subprocess
import sys
import tempfile
import unittest

import report

HERE = os.path.dirname(os.path.abspath(__file__))
HEADER = (
    '| Device | Library | Runs | Overlay frame (ms) | Snapshot, JS (ms) | Frame p50 (ms) | Frame p90 (ms)'
    ' | Janky (%) | Late frames | Missed vsyncs | JS max gap (ms) | Frame interval (ms) |'
)


def run(
    device, lib, overlay, snapshot=10.0, p50=16.0, p90=20.0, share=0.25, late=1, missed=5, gap=30.0, valid=True, vsync=16.6
):
    return {
        'device': device,
        'lib': lib,
        'overlayFrameMs': overlay,
        'snapshotMs': snapshot,
        'p50': p50,
        'p90': p90,
        'jankyShare': share,
        'lateFrames': late,
        'missedVsyncs': missed,
        'jsMaxGapMs': gap,
        'vsyncMs': vsync,
        'valid': valid,
    }


class ReportTest(unittest.TestCase):
    def test_median_of_the_valid_runs(self):
        runs = [
            run('M2101K9AG', 'ours', 200.0, late=0),
            run('M2101K9AG', 'ours', 100.0, late=2),
            run('M2101K9AG', 'ours', 150.0, late=1),
            run('M2101K9AG', 'ours', 10.0, valid=False),
        ]
        lines = report.table(runs).splitlines()
        self.assertEqual(lines[0], HEADER)
        self.assertEqual(lines[1], '|' + '---|' * 12)
        self.assertEqual(lines[2], '| M2101K9AG | ours | 3/4 | 150.0 | 10.0 | 16.0 | 20.0 | 25 | 1 | 5 | 30.0 | 16.6 |')

    def test_no_valid_run(self):
        line = report.table([run('M2101K9AG', 'skia', 400.0, valid=False)]).splitlines()[2]
        self.assertEqual(line, '| M2101K9AG | skia | 0/1 | - | - | - | - | - | - | - | - | - |')

    def test_even_count_takes_the_mean_of_the_middle_two(self):
        runs = [run('M2101K9AG', 'ours', 100.0, late=1), run('M2101K9AG', 'ours', 200.0, late=2)]
        self.assertIn('| 2/2 | 150.0 |', report.table(runs))
        self.assertIn('| 1.5 |', report.table(runs))

    def test_libraries_keep_the_benchmark_order(self):
        runs = [run('Pixel-8-Pro', 'skia', 1.0), run('Pixel-8-Pro', 'theme-switch-animation', 1.0), run('Pixel-8-Pro', 'ours', 1.0)]
        libs = [line.split(' | ')[1] for line in report.table(runs).splitlines()[2:]]
        self.assertEqual(libs, ['ours', 'theme-switch-animation', 'skia'])

    def test_lists_the_runs_that_do_not_count(self):
        flagged = dict(run('M2101K9AG', 'ours', 10.0, valid=False), round=3, invalid=['no overlay', 'no BENCH line'])
        self.assertEqual(
            report.excluded([run('M2101K9AG', 'ours', 100.0), flagged]),
            'Runs that do not count:\n- M2101K9AG ours round 3: no overlay; no BENCH line',
        )
        self.assertEqual(report.excluded([run('M2101K9AG', 'ours', 100.0)]), '')

    def test_cli_reads_result_files(self):
        with tempfile.TemporaryDirectory() as folder:
            path = os.path.join(folder, 'M2101K9AG-ours-1.json')
            with open(path, 'w') as file:
                json.dump(run('M2101K9AG', 'ours', 224.9), file)
            output = subprocess.run(
                [sys.executable, '-I', os.path.join(HERE, 'report.py'), path], check=True, capture_output=True, text=True
            ).stdout
        self.assertIn('| M2101K9AG | ours | 1/1 | 224.9 |', output)


class GateTest(unittest.TestCase):
    def rows(self, runs):
        lines, fails = report.gate(runs)
        return {line.split(' | ')[1]: line for line in lines[2:]}, fails

    def test_passes_up_to_five_percent_over_the_best_alternative(self):
        runs = [
            run('M2101K9AG', 'ours', 105.0),
            run('M2101K9AG', 'theme-switch-animation', 100.0),
            run('M2101K9AG', 'skia', 400.0),
        ]
        rows, fails = self.rows(runs)
        self.assertEqual(
            rows['Overlay frame (ms)'],
            '| M2101K9AG | Overlay frame (ms) | 105.0 | 100.0 (theme-switch-animation) | 105.0 | PASS |',
        )
        self.assertEqual(fails, 0)
        self.assertNotIn('Frame interval (ms)', rows)

    def test_fails_over_the_limit_and_counts_the_rows(self):
        runs = [run('M2101K9AG', 'ours', 106.0, missed=4), run('M2101K9AG', 'theme-switch-animation', 100.0, missed=3)]
        rows, fails = self.rows(runs)
        self.assertTrue(rows['Overlay frame (ms)'].endswith('| 105.0 | FAIL |'))
        self.assertEqual(rows['Missed vsyncs'], '| M2101K9AG | Missed vsyncs | 4 | 3 (theme-switch-animation) | 3.15 | FAIL |')
        self.assertEqual(fails, 2)

    def test_janky_share_shows_as_a_percent(self):
        runs = [run('Pixel-8-Pro', 'ours', 50.0, share=0.10), run('Pixel-8-Pro', 'theme-switch-animation', 60.0, share=0.06)]
        rows, _ = self.rows(runs)
        self.assertEqual(rows['Janky (%)'], '| Pixel-8-Pro | Janky (%) | 10 | 6 (theme-switch-animation) | 6 | FAIL |')

    def test_an_alternative_at_another_frame_rate_is_left_out_of_the_frame_metrics(self):
        runs = [
            run('Pixel-8-Pro', 'ours', 50.0, p90=8.0, vsync=16.4),
            run('Pixel-8-Pro', 'theme-switch-animation', 60.0, p90=9.5, vsync=16.6),
            run('Pixel-8-Pro', 'skia', 300.0, snapshot=5.0, p90=4.0, vsync=32.9),
        ]
        rows, _ = self.rows(runs)
        self.assertIn('| 9.5 (theme-switch-animation) |', rows['Frame p90 (ms)'])
        self.assertIn('| 5.0 (skia) |', rows['Snapshot, JS (ms)'])

    def test_invalid_runs_do_not_count(self):
        runs = [
            run('M2101K9AG', 'ours', 100.0),
            run('M2101K9AG', 'theme-switch-animation', 120.0),
            run('M2101K9AG', 'theme-switch-animation', 10.0, valid=False),
        ]
        rows, _ = self.rows(runs)
        self.assertIn('| 120.0 (theme-switch-animation) |', rows['Overlay frame (ms)'])

    def test_no_alternative_gives_n_a(self):
        rows, fails = self.rows([run('M2101K9AG', 'ours', 100.0)])
        self.assertEqual(rows['Overlay frame (ms)'], '| M2101K9AG | Overlay frame (ms) | 100.0 | - | - | n/a |')
        self.assertEqual(fails, 0)

    def test_cli_gate_option(self):
        with tempfile.TemporaryDirectory() as folder:
            paths = []
            for lib, overlay in (('ours', 90.0), ('theme-switch-animation', 100.0)):
                path = os.path.join(folder, f'M2101K9AG-{lib}-1.json')
                with open(path, 'w') as file:
                    json.dump(run('M2101K9AG', lib, overlay), file)
                paths.append(path)
            output = subprocess.run(
                [sys.executable, '-I', os.path.join(HERE, 'report.py'), '--gate', *paths],
                check=True,
                capture_output=True,
                text=True,
            ).stdout
        self.assertIn('| M2101K9AG | Overlay frame (ms) | 90.0 | 100.0 (theme-switch-animation) | 105.0 | PASS |', output)
        self.assertTrue(output.endswith('\nFAIL rows: 0\n'))


if __name__ == '__main__':
    unittest.main()
