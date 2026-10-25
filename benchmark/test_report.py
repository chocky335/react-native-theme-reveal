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


def run(device, lib, overlay, snapshot=10.0, p50=16.0, p90=20.0, share=0.25, late=1, missed=5, gap=30.0, valid=True):
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
        'vsyncMs': 16.6,
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


if __name__ == '__main__':
    unittest.main()
