"""runresult.py: one benchmark run as JSON, plus the reasons it doesn't count.

runresult.py --device D --lib L --round N --position P --order a,b,c --scales A,T,W --bench "<BENCH line>"
             --dump <framestats dump> --out <run.json>

--scales: animator_duration_scale, transition_animation_scale, window_animation_scale as `settings get global`
prints them ("null" means the default, 1). --bench "" means the run logged no BENCH line.
"""

import argparse
import json
import os
import sys

# python3 -I leaves the script's folder out of sys.path.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import framestats  # noqa: E402

SCALE_NAMES = ('animator_duration_scale', 'transition_animation_scale', 'window_animation_scale')
NUMBERS = ('snapshotMs', 'jsMaxGapMs', 'totalMs', 'durationMs', 'pressAt', 'overlayAt', 'endAt')


def parse_bench(line):
    """key=value fields of a BENCH line. error= comes last and keeps its spaces."""
    line, _, error = line.partition(' error=')
    fields = dict(pair.split('=', 1) for pair in line.split()[1:] if '=' in pair)
    for key in NUMBERS:
        if key in fields:
            fields[key] = float(fields[key])
    if error:
        fields['error'] = error
    return fields


def scale_value(text):
    return 1.0 if text in ('null', '') else float(text)


def invalid_reasons(bench, scales, has_profile_data, frames):
    if not bench:
        return ['no BENCH line']
    if 'error' in bench:
        return ['error: ' + bench['error']]
    reasons = []
    off = [name for name, value in scales.items() if scale_value(value) != 1]
    if off:
        reasons.append('animation scale not 1: ' + ', '.join(off))
    if not has_profile_data:
        reasons.append('no PROFILEDATA in the dump')
    if bench.get('t0') != 'event':
        reasons.append('the press time is not on the frame clock')
    if bench['totalMs'] < bench['durationMs']:
        reasons.append('ended before the duration')
    if 'transition' in bench and bench['transition'] != 'reveal':
        reasons.append('settled with transition=' + bench['transition'])
    if bench.get('overlay') == '0':
        reasons.append('no overlay')
    if frames['overlayFrameMs'] is None:
        reasons.append('no frame after the overlay')
    return reasons


def build(device, lib, round_number, position, order, scales, bench_line, dump_text):
    bench = parse_bench(bench_line) if bench_line else {}
    has_profile_data = framestats.has_profile_data(dump_text)
    frames = framestats.parse_frames(dump_text)
    if bench.get('t0') == 'event' and 'error' not in bench:
        summary = framestats.summarize(frames, bench['pressAt'], bench['overlayAt'], bench['endAt'])
    else:
        summary = framestats.summarize([])
    reasons = invalid_reasons(bench, scales, has_profile_data, summary)
    result = {'device': device, 'lib': lib, 'round': round_number, 'position': position, 'order': order}
    result['scales'] = scales
    result.update({key: value for key, value in bench.items() if key != 'lib'})
    result.update(summary)
    result['valid'] = not reasons
    result['invalid'] = reasons
    result['bench'] = bench_line
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--device', required=True)
    parser.add_argument('--lib', required=True)
    parser.add_argument('--round', type=int, required=True)
    parser.add_argument('--position', type=int, required=True)
    parser.add_argument('--order', required=True)
    parser.add_argument('--scales', required=True)
    parser.add_argument('--bench', required=True)
    parser.add_argument('--dump', required=True)
    parser.add_argument('--out', required=True)
    args = parser.parse_args()
    dump_text = ''
    if os.path.exists(args.dump):
        with open(args.dump) as file:
            dump_text = file.read()
    scales = dict(zip(SCALE_NAMES, args.scales.split(',')))
    result = build(
        args.device, args.lib, args.round, args.position, args.order.split(','), scales, args.bench, dump_text
    )
    result['dump'] = os.path.basename(args.dump)
    with open(args.out, 'w') as file:
        json.dump(result, file, indent=2)
    status = 'valid' if result['valid'] else 'INVALID (' + '; '.join(result['invalid']) + ')'
    print(f"overlayFrameMs={result['overlayFrameMs']} snapshotMs={result.get('snapshotMs')} {status}")


if __name__ == '__main__':
    main()
