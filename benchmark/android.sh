#!/bin/zsh
# android.sh <serial> <runs> <lib>...: interleaved benchmark pass in the example's release build.
#   lib: ours | theme-switch-animation | skia
# A pass is one discarded warm-up run per library, then <runs> rounds. Each round runs every library once, and
# the start library rotates each round (round 1: a b c, round 2: b c a, ...).
# A run opens themereveal-example://bench?lib=<lib> (fresh screen, light theme), resets gfxinfo, taps Run,
# waits for the BENCH log line and dumps gfxinfo framestats.
# Output: results/raw/<device>-<lib>-<round>.txt (the dump) and results/<device>-<lib>-<round>.json (the run).
#
# Environment (all optional; by default the standard adb server, with no lease):
#   ADB_SERVER_SOCKET  remote adb server. A remote server needs a lease: BENCH_LEASE or BENCH_LEASED=1.
#   BENCH_LEASE        lease helper, run as `$BENCH_LEASE <serial> <max-wait-s> -- <command>`. The script runs
#                      itself under it for the whole pass.
#   BENCH_LEASED=1     the caller already holds the phone's lease.
#   BENCH_PAUSED_FILE  if this file exists, the pass stops before the next run.
set -u
if (( $# < 3 )); then
  echo "usage: android.sh <serial> <runs> <lib>..." >&2
  exit 2
fi
SERIAL=$1 RUNS=$2
shift 2
LIBS=("$@")
HERE=${0:A:h}
PKG=expo.modules.themereveal.example

if [[ -z ${BENCH_LEASED:-} ]]; then
  if [[ -n ${BENCH_LEASE:-} ]]; then
    [[ -x $BENCH_LEASE ]] || { echo "BENCH_LEASE is not executable: $BENCH_LEASE" >&2; exit 2; }
    BENCH_LEASED=1 exec $BENCH_LEASE $SERIAL 900 -- zsh $0 $SERIAL $RUNS "${LIBS[@]}"
  fi
  if [[ -n ${ADB_SERVER_SOCKET:-} ]]; then
    echo "ADB_SERVER_SOCKET names a remote adb server: set BENCH_LEASE, or BENCH_LEASED=1 if you hold the lease" >&2
    exit 2
  fi
fi

# adb against the phone, with a watchdog, since adb shell over a remote server can hang forever.
a() { perl -e 'alarm shift; exec @ARGV' ${ADB_WATCHDOG:-120} adb -s $SERIAL "$@" }

# Our window has focus, or no window has focus and our activity is the top resumed one.
focus() {
  local f=$(a shell dumpsys window | grep -m1 mCurrentFocus)
  [[ $f == *$PKG* ]] && return 0
  [[ $f == *mCurrentFocus=null* ]] && a shell dumpsys activity activities | grep -m1 topResumedActivity | grep -q $PKG
}

# Wait up to <seconds> for a ReactNativeJS line matching <pattern>, and print it from the pattern onward.
wait_log() {
  local pattern=$1 seconds=$2 line
  for _ in $(seq 1 $(( seconds * 2 ))); do
    line=$(a logcat -d -s ReactNativeJS:V | grep -oE "$pattern.*" | tail -1)
    [[ -n $line ]] && { echo $line; return 0 }
    sleep 0.5
  done
  return 1
}

# Value of key=<value> in a log line.
field() { echo $1 | grep -oE "(^| )$2=[^ ]+" | cut -d= -f2 }

paused() { [[ -n ${BENCH_PAUSED_FILE:-} && -e $BENCH_PAUSED_FILE ]] }

# One run of <lib>, dumped to <raw>. Sets BENCH to the BENCH line, or empty if the app didn't log one. Exits the
# pass if the screen doesn't open or another app has focus.
one_run() {
  local lib=$1 raw=$2 ready x y
  BENCH=""
  a logcat -c
  a shell am start -W -a android.intent.action.VIEW -d "themereveal-example://bench?lib=$lib" $PKG > /dev/null
  ready=$(wait_log "BENCH-READY lib=$lib " 20) || { echo "no BENCH-READY for $lib" >&2; exit 1; }
  x=$(field $ready x) y=$(field $ready y)
  # Let the fresh screen settle so the dump only has the switch in it.
  sleep 1.5
  a logcat -c
  a shell dumpsys gfxinfo $PKG reset > /dev/null
  focus || { echo "focus is not $PKG:" >&2; a shell dumpsys window | grep -m1 mCurrentFocus >&2; exit 1; }
  a shell input tap $x $y
  BENCH=$(wait_log "BENCH lib=$lib " 15)
  # Let the transition's last frames finish drawing.
  sleep 0.5
  a shell dumpsys gfxinfo $PKG framestats > $raw
}

a get-state > /dev/null 2>&1 || { echo "phone $SERIAL is not reachable" >&2; exit 1; }
a shell pm path $PKG > /dev/null || { echo "$PKG is not installed on $SERIAL" >&2; exit 1; }
a shell input keyevent 224
if a shell dumpsys window | grep -qE 'mDreamingLockscreen=true|showing=true'; then
  echo "phone $SERIAL is locked; unlock it and run again" >&2
  exit 1
fi

DEVICE=$(a shell getprop ro.product.model | tr -d '\r' | tr ' ' '-')
# Any scale other than 1 changes Android animator durations (ours, theme-switch-animation) but not Reanimated's
# (Skia), so stop the pass.
SCALES=()
for name in animator_duration_scale transition_animation_scale window_animation_scale; do
  value=$(a shell settings get global $name | tr -d '\r')
  SCALES+=($value)
  if [[ $value != 1 && $value != 1.0 && $value != null ]]; then
    echo "$name is $value on $SERIAL; the benchmark needs 1" >&2
    exit 1
  fi
done
RESULTS=$HERE/results
mkdir -p $RESULTS/raw
WARMUP=$(mktemp -d)
echo "device $DEVICE, scales ${(j:,:)SCALES}, $RUNS rounds of ${LIBS[*]}"

for lib in $LIBS; do
  paused && { echo "paused; stopping" >&2; exit 75; }
  one_run $lib $WARMUP/$lib.txt
  echo "warm-up $lib: ${BENCH:-no BENCH line}"
done
rm -rf $WARMUP

count=${#LIBS}
for round in $(seq 1 $RUNS); do
  start=$(( (round - 1) % count ))
  order=(${LIBS[$(( start + 1 )),-1]} ${LIBS[1,$start]})
  position=0
  for lib in $order; do
    position=$(( position + 1 ))
    paused && { echo "paused; stopping" >&2; exit 75; }
    raw=$RESULTS/raw/$DEVICE-$lib-$round.txt
    one_run $lib $raw
    echo "round $round, $lib: ${BENCH:-no BENCH line}"
    python3 -I $HERE/runresult.py --device $DEVICE --lib $lib --round $round --position $position \
      --order ${(j:,:)order} --scales ${(j:,:)SCALES} --bench "$BENCH" --dump $raw \
      --out $RESULTS/$DEVICE-$lib-$round.json
  done
done
