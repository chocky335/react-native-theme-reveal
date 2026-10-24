"""report.py <results/*.json>

Markdown comparison table from the per-run JSON files that android.sh writes: one row per device and library,
with the median of each metric over the valid runs. Runs shows valid / all. A run that doesn't count
(runresult.py: invalid) is left out of every median. "Overlay frame" is the column that decides pass/fail.
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


def main():
    runs = []
    for path in sys.argv[1:]:
        with open(path) as file:
            runs.append(json.load(file))
    print(table(runs))


if __name__ == '__main__':
    main()
