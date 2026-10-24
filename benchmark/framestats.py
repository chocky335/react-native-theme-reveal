"""framestats.py <dump> [--press-ms MS --overlay-ms MS --end-ms MS]

Frame metrics from an `adb shell dumpsys gfxinfo <package> framestats` dump, printed as JSON.

Rows:
- Every window of the app counts (each PROFILEDATA section).
- Skipped: frames flagged WindowVisibilityChanged (1, a window's first frame, an outlier) or SkippedFrame
  (8, nothing drawn). Kept: RTAnimation frames (2), drawn by an animation on the RenderThread (for example
  createCircularReveal), and SurfaceCanvas frames (4).
- A frame ends at FrameCompleted. HWUI sometimes leaves a stale FrameCompleted (older than IntendedVsync).
  On a section's last row that means the frame hadn't completed when the dump ran, so the row is skipped.
  On any other row the frame did complete, and it ends at SwapBuffersCompleted.
- Frame time is end - IntendedVsync.

Transition window (all times in CLOCK_MONOTONIC ms, the clock of the touch event timestamp):
- --press-ms: the press. --overlay-ms: when JS learned the overlay is in the view tree.
  --end-ms: end of the run in JS.
- The window starts at the press and ends at the last frame of the burst that runs past --end-ms: after
  --end-ms, a frame stays in the window while it starts within 2.5 frame intervals of the previous one
  (so one missed vsync plus jitter stays in).
- Without --press-ms, every frame in the dump counts and the press metrics are null.

Output:
- frames, p50, p90: count and frame time percentiles (linear interpolation between the closest ranks).
- vsyncMs: the frame interval the app ran at, the median gap between distinct IntendedVsync values.
- janky, jankyShare, jankyOverMs: frames longer than vsyncMs rounded to 0.1 ms, as a count and a share.
- overlayFrameMs: press to SwapBuffersCompleted of the first frame whose IntendedVsync is at or after the
  press (FrameCompleted if SwapBuffersCompleted isn't valid). That's the first frame with the overlay,
  since the screen draws nothing else after the press. Null if no frame starts at or after --overlay-ms.
- missedVsyncs: whole frame intervals from the press to the Vsync of the first frame after the press.
- lateFrames: frames that started (Vsync) half a frame interval or more after their IntendedVsync, because the
  UI thread was busy.
"""

import argparse
import json
from collections import namedtuple

Frame = namedtuple('Frame', ['intended_vsync_ns', 'vsync_ns', 'end_ns', 'swap_completed_ns'])

WINDOW_VISIBILITY_CHANGED = 1
SKIPPED_FRAME = 8


def has_profile_data(text):
    return '---PROFILEDATA---' in text


def _sections(text):
    """Rows of each PROFILEDATA section, as dicts."""
    sections, rows, header, inside = [], [], None, False
    for line in text.splitlines():
        line = line.strip()
        if line == '---PROFILEDATA---':
            if inside:
                sections.append(rows)
                rows = []
            inside = not inside
            continue
        if not inside:
            continue
        if line.startswith('Flags,'):
            header = line.rstrip(',').split(',')
            continue
        if header is None or not line or not line[0].isdigit():
            continue
        values = line.rstrip(',').split(',')
        if len(values) >= len(header):
            rows.append(dict(zip(header, (int(value) for value in values))))
    return sections


def parse_frames(text):
    frames = []
    for rows in _sections(text):
        for index, row in enumerate(rows):
            if row['Flags'] & (WINDOW_VISIBILITY_CHANGED | SKIPPED_FRAME):
                continue
            intended = row['IntendedVsync']
            swap_completed = row.get('SwapBuffersCompleted', 0)
            end = row['FrameCompleted']
            if end < intended:
                if index == len(rows) - 1 or swap_completed < intended:
                    continue
                end = swap_completed
            frames.append(Frame(intended, row['Vsync'], end, swap_completed))
    return frames


def frame_ms(frame):
    return (frame.end_ns - frame.intended_vsync_ns) / 1e6


def percentile(sorted_values, fraction):
    position = (len(sorted_values) - 1) * fraction
    lower = int(position)
    upper = min(lower + 1, len(sorted_values) - 1)
    return sorted_values[lower] + (sorted_values[upper] - sorted_values[lower]) * (position - lower)


def vsync_ms(frames):
    vsyncs = sorted({frame.intended_vsync_ns for frame in frames})
    gaps = sorted((later - earlier) / 1e6 for earlier, later in zip(vsyncs, vsyncs[1:]))
    return percentile(gaps, 0.5) if gaps else None


def window(frames, press_ms, end_ms, interval_ms):
    """Frames from the press to the end of the burst that runs past end_ms."""
    ordered = sorted(frames, key=lambda frame: frame.intended_vsync_ns)
    inside = [frame for frame in ordered if press_ms * 1e6 <= frame.intended_vsync_ns <= end_ms * 1e6]
    last_ns = inside[-1].intended_vsync_ns if inside else end_ms * 1e6
    last_ns = max(last_ns, end_ms * 1e6)
    for frame in ordered:
        if frame.intended_vsync_ns <= end_ms * 1e6:
            continue
        if frame.intended_vsync_ns - last_ns > 2.5 * interval_ms * 1e6:
            break
        inside.append(frame)
        last_ns = frame.intended_vsync_ns
    return inside


def summarize(frames, press_ms=None, overlay_ms=None, end_ms=None):
    interval = vsync_ms(frames)
    if press_ms is not None and end_ms is not None and interval:
        frames = window(frames, press_ms, end_ms, interval)
        interval = vsync_ms(frames) or interval
    times = sorted(frame_ms(frame) for frame in frames)
    threshold = round(interval, 1) if interval else None
    janky = sum(time > threshold for time in times) if threshold else None
    summary = {
        'frames': len(times),
        'p50': round(percentile(times, 0.5), 2) if times else None,
        'p90': round(percentile(times, 0.9), 2) if times else None,
        'vsyncMs': round(interval, 2) if interval else None,
        'janky': janky,
        'jankyShare': round(janky / len(times), 3) if times and janky is not None else None,
        'jankyOverMs': threshold,
        'overlayFrameMs': None,
        'missedVsyncs': None,
        'lateFrames': None,
    }
    if press_ms is None or not frames or not interval:
        return summary
    ordered = sorted(frames, key=lambda frame: frame.intended_vsync_ns)
    summary['missedVsyncs'] = int((ordered[0].vsync_ns / 1e6 - press_ms) // interval)
    summary['lateFrames'] = sum(
        (frame.vsync_ns - frame.intended_vsync_ns) / 1e6 >= interval / 2 for frame in ordered
    )
    # The first frame after the press is the overlay frame: the screen draws nothing between press and overlay,
    # and a library can add its overlay natively before JS hears about it (overlayAt). Still require a frame at
    # or after overlayAt, so a run whose overlay never drew gets none.
    if overlay_ms is not None and any(frame.intended_vsync_ns >= overlay_ms * 1e6 for frame in ordered):
        first = ordered[0]
        shown_ns = first.swap_completed_ns if first.swap_completed_ns >= first.intended_vsync_ns else first.end_ns
        summary['overlayFrameMs'] = round(shown_ns / 1e6 - press_ms, 1)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('dump')
    parser.add_argument('--press-ms', type=float)
    parser.add_argument('--overlay-ms', type=float)
    parser.add_argument('--end-ms', type=float)
    args = parser.parse_args()
    with open(args.dump) as file:
        frames = parse_frames(file.read())
    print(json.dumps(summarize(frames, args.press_ms, args.overlay_ms, args.end_ms)))


if __name__ == '__main__':
    main()
