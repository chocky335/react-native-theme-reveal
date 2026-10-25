import json
import os
import subprocess
import sys
import tempfile
import unittest

import runresult

HERE = os.path.dirname(os.path.abspath(__file__))
OURS = os.path.join(HERE, 'fixtures', 'gfxinfo-ours-stall.txt')
# The fixture's run, in the BENCH format of fix round 1.
OURS_BENCH = (
    'BENCH lib=ours snapshotMs=93.3 jsMaxGapMs=107.8 totalMs=652.7 durationMs=450 pressAt=821549545.0 t0=event'
    ' overlayAt=821549638.3 endAt=821550197.7 target=dark transition=reveal'
)
SCALES_ON = {name: '1.0' for name in runresult.SCALE_NAMES}


def read(path):
    with open(path) as file:
        return file.read()


def build(bench=OURS_BENCH, scales=SCALES_ON, dump=None):
    return runresult.build('M2101K9AG', 'ours', 1, 2, ['skia', 'ours'], scales, bench, read(OURS) if dump is None else dump)


class ParseBenchTest(unittest.TestCase):
    def test_numbers_and_words(self):
        fields = runresult.parse_bench(OURS_BENCH)
        self.assertEqual(fields['lib'], 'ours')
        self.assertEqual(fields['snapshotMs'], 93.3)
        self.assertEqual(fields['overlayAt'], 821549638.3)
        self.assertEqual(fields['transition'], 'reveal')

    def test_error_keeps_its_spaces(self):
        fields = runresult.parse_bench('BENCH lib=skia pressAt=1.0 t0=event error=Error: no Skia host')
        self.assertEqual(fields['error'], 'Error: no Skia host')
        self.assertEqual(fields['pressAt'], 1.0)


class ValidityTest(unittest.TestCase):
    def test_a_good_run_counts(self):
        result = build()
        self.assertTrue(result['valid'])
        self.assertEqual(result['invalid'], [])
        self.assertEqual(result['overlayFrameMs'], 224.9)
        self.assertEqual(result['snapshotMs'], 93.3)
        self.assertEqual((result['round'], result['position'], result['order']), (1, 2, ['skia', 'ours']))

    def test_null_scale_is_the_default(self):
        self.assertTrue(build(scales={name: 'null' for name in runresult.SCALE_NAMES})['valid'])

    def test_an_animation_scale_off_1(self):
        scales = dict(SCALES_ON, animator_duration_scale='0.5')
        self.assertEqual(build(scales=scales)['invalid'], ['animation scale not 1: animator_duration_scale'])

    def test_a_run_shorter_than_the_duration(self):
        bench = OURS_BENCH.replace('totalMs=652.7', 'totalMs=129.0')
        self.assertIn('ended before the duration', build(bench=bench)['invalid'])

    def test_ours_without_a_reveal(self):
        bench = OURS_BENCH.replace('transition=reveal', 'transition=null')
        self.assertIn('settled with transition=null', build(bench=bench)['invalid'])

    def test_skia_without_an_overlay(self):
        bench = OURS_BENCH.replace('lib=ours', 'lib=skia').replace('transition=reveal', 'overlay=0')
        self.assertIn('no overlay', build(bench=bench)['invalid'])

    def test_a_first_frame_long_before_overlay_at(self):
        # The first frame after the press (+107.6 ms) starts 192.4 ms before this overlayAt (+300 ms).
        result = build(bench=OURS_BENCH.replace('overlayAt=821549638.3', 'overlayAt=821549845.0'))
        self.assertIn('first frame 192.4 ms before overlayAt: probably not the overlay', result['invalid'])
        self.assertEqual(build()['invalid'], [])

    def test_press_time_not_on_the_frame_clock(self):
        result = build(bench=OURS_BENCH.replace('t0=event', 't0=onPress'))
        self.assertIn('the press time is not on the frame clock', result['invalid'])
        self.assertIn('no frame after the overlay', result['invalid'])

    def test_a_dump_without_profile_data(self):
        result = build(dump='No process found for: expo.modules.themereveal.example\n')
        self.assertIn('no PROFILEDATA in the dump', result['invalid'])
        self.assertEqual(result['frames'], 0)

    def test_an_error_line(self):
        result = build(bench='BENCH lib=ours pressAt=821549545.0 t0=event error=Error: boom')
        self.assertEqual(result['invalid'], ['error: Error: boom'])

    def test_no_bench_line(self):
        self.assertEqual(build(bench='')['invalid'], ['no BENCH line'])


class CliTest(unittest.TestCase):
    def test_writes_the_run(self):
        with tempfile.TemporaryDirectory() as folder:
            out = os.path.join(folder, 'run.json')
            subprocess.run(
                [
                    sys.executable, '-I', os.path.join(HERE, 'runresult.py'),
                    '--device', 'M2101K9AG', '--lib', 'ours', '--round', '1', '--position', '1',
                    '--order', 'ours,theme-switch-animation,skia', '--scales', '1.0,1.0,null',
                    '--bench', OURS_BENCH, '--dump', OURS, '--out', out,
                ],
                check=True,
                capture_output=True,
            )
            with open(out) as file:
                result = json.load(file)
        self.assertTrue(result['valid'])
        self.assertEqual(result['scales']['window_animation_scale'], 'null')
        self.assertEqual(result['order'], ['ours', 'theme-switch-animation', 'skia'])
        self.assertEqual(result['dump'], 'gfxinfo-ours-stall.txt')


if __name__ == '__main__':
    unittest.main()
