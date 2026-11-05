"""report.py <results/*.json>

Markdown comparison table from the per-run JSON files that android.sh writes: one row per device and library,
with the median of each metric over the valid runs. Runs shows valid / all. A run that doesn't count
(runresult.py: invalid) is left out of every median and listed under the table with its reasons.

report.py --gate <results/*.json>

Pass/fail table instead: for each device and metric, ours against the best alternative's median plus 5% (lower is
better). An alternative that drew at a frame interval more than 10% off ours is left out of the frame metrics
(p50, p90, janky, late frames, missed vsyncs), as for Skia at 30 Hz on the Pixel 8 Pro.
"""

import json
import statistics
import sys

LIBRARY_ORDER = ['ours', 'theme-switch-animation', 'skia']
COLUMNS = [
    ('Overlay frame (ms)', 'overlayFrameMs'),
    ('Snapshot, JS (ms)', 'snapshotMs'),
    ('Frame p50 (ms)', 'p50'),
    ('Frame p90 (ms)', 'p90'),
    ('Janky (%)', 'jankyShare'),
    ('Late frames', 'lateFrames'),
    ('Missed vsyncs', 'missedVsyncs'),
    ('JS max gap (ms)', 'jsMaxGapMs'),
    ('Frame interval (ms)', 'vsyncMs'),
]
COUNTS = ('lateFrames', 'missedVsyncs')
FRAME_METRICS = ('p50', 'p90', 'jankyShare', 'lateFrames', 'missedVsyncs')
GATE_MARGIN = 1.05
SAME_RATE = 0.10


def library_rank(lib):
    return LIBRARY_ORDER.index(lib) if lib in LIBRARY_ORDER else len(LIBRARY_ORDER)


def median_of(runs, key):
    values = [run[key] for run in runs if run.get(key) is not None]
    return statistics.median(values) if values else None


def cell(key, value):
    if value is None:
        return '-'
    if key == 'jankyShare':
        return f'{value * 100:.0f}'
    if key in COUNTS:
        return str(int(value)) if value == int(value) else f'{value:.1f}'
    return f'{value:.1f}'


def table(runs):
    groups = {}
    for run in runs:
        groups.setdefault((run['device'], run['lib']), []).append(run)
    lines = [
        '| Device | Library | Runs | ' + ' | '.join(title for title, _ in COLUMNS) + ' |',
        '|' + '---|' * (3 + len(COLUMNS)),
    ]
    for device, lib in sorted(groups, key=lambda group: (group[0], library_rank(group[1]), group[1])):
        group = groups[(device, lib)]
        valid = [run for run in group if run.get('valid')]
        cells = [cell(key, median_of(valid, key)) for _, key in COLUMNS]
        lines.append(f'| {device} | {lib} | {len(valid)}/{len(group)} | ' + ' | '.join(cells) + ' |')
    return '\n'.join(lines)


def excluded(runs):
    """Runs left out of the medians, with their reasons."""
    lines = [
        f"- {run['device']} {run['lib']} round {run.get('round', '?')}: " + '; '.join(run.get('invalid', []))
        for run in runs
        if not run.get('valid')
    ]
    return '\n'.join(['Runs that do not count:'] + lines) if lines else ''


def gate(runs):
    """Pass/fail lines and the count of FAIL rows. Only valid runs count."""
    groups = {}
    for run in runs:
        if run.get('valid'):
            groups.setdefault(run['device'], {}).setdefault(run['lib'], []).append(run)
    lines = ['| Device | Metric | ours | Best alternative | Limit (+5%) | Result |', '|---|---|---|---|---|---|']
    fails = 0
    for device in sorted(groups):
        libs = groups[device]
        if 'ours' not in libs:
            continue
        ours_interval = median_of(libs['ours'], 'vsyncMs')
        for title, key in COLUMNS:
            if key == 'vsyncMs':
                continue
            ours = median_of(libs['ours'], key)
            best = None
            for lib, group in libs.items():
                if lib == 'ours':
                    continue
                if key in FRAME_METRICS and ours_interval:
                    interval = median_of(group, 'vsyncMs')
                    if interval is None or abs(interval / ours_interval - 1) > SAME_RATE:
                        continue
                value = median_of(group, key)
                if value is not None and (best is None or value < best[1]):
                    best = (lib, value)
            if ours is None or best is None:
                lines.append(f'| {device} | {title} | {cell(key, ours)} | - | - | n/a |')
                continue
            limit = best[1] * GATE_MARGIN
            passed = ours <= limit + 1e-9
            fails += not passed
            shown_limit = f'{limit:.2f}' if key in COUNTS else cell(key, limit)
            lines.append(
                f'| {device} | {title} | {cell(key, ours)} | {cell(key, best[1])} ({best[0]}) | {shown_limit} | '
                + ('PASS' if passed else 'FAIL')
                + ' |'
            )
    return lines, fails


def main():
    args = sys.argv[1:]
    gate_mode = bool(args) and args[0] == '--gate'
    runs = []
    for path in args[1:] if gate_mode else args:
        with open(path) as file:
            runs.append(json.load(file))
    if gate_mode:
        lines, fails = gate(runs)
        print('\n'.join(lines))
        print(f'\nFAIL rows: {fails}')
        return
    print(table(runs))
    note = excluded(runs)
    if note:
        print('\n' + note)


if __name__ == '__main__':
    main()
