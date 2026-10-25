// Benchmark screen: same switch, content, origin and duration for each library. benchmark/android.sh opens it
// via themereveal-example://bench?lib=<id>, gets the Run button position from the BENCH-READY log line, taps
// it, then reads the BENCH line. Both lines go through console.error, which reaches logcat in release builds.
// The BENCH line fields are documented in benchmark/README.md.
import { useEffect, useRef, useState } from 'react'
import type { ComponentType } from 'react'
import {
  Linking,
  PixelRatio,
  Platform,
  Pressable,
  Settings,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native'
import type { GestureResponderEvent } from 'react-native'
import type { Point } from 'react-native-theme-reveal'

import { Button } from './Button'
import * as ours from './bench/ours'
import { DURATION_MS } from './bench/shared'
import type { HostProps, RunResult } from './bench/shared'
import * as skia from './bench/skia'
import * as themeSwitchAnimation from './bench/themeSwitchAnimation'
import { opposite, palette } from './theme'
import type { Theme } from './theme'

type Library = {
  label: string
  Host: ComponentType<HostProps>
  run: (target: Theme, origin: Point) => Promise<RunResult>
}

export const LIBRARIES = {
  ours: { label: 'theme-reveal', ...ours },
  'theme-switch-animation': { label: 'theme-switch-animation', ...themeSwitchAnimation },
  skia: { label: 'Skia', ...skia },
} satisfies Record<string, Library>

export type LibraryId = keyof typeof LIBRARIES

const LIBRARY_IDS = Object.keys(LIBRARIES) as LibraryId[]
const ROWS = Array.from({ length: 12 }, (_, index) => index + 1)
// Press timestamps older than this are on a different clock than performance.now().
const MAX_EVENT_AGE_MS = 1000

// iOS launch argument -benchRuns '<id> <id> ...': taps don't reach the simulator, so each id opens a fresh
// benchmark screen this far apart and the screen runs itself from the Run button's centre (t0=onPress).
const LAUNCH_RUN_GAP_MS = 4000
const AUTO_RUN_DELAY_MS = 1500

const format = (ms: number) => (Number.isFinite(ms) ? ms.toFixed(1) : 'NaN')

// Largest gap between JS frames from `from` until stop().
function startJsProbe(from: number) {
  let last = from
  let maxGap = 0
  let active = true
  const tick = () => {
    if (!active) return
    const now = performance.now()
    maxGap = Math.max(maxGap, now - last)
    last = now
    requestAnimationFrame(tick)
  }
  requestAnimationFrame(tick)
  return () => {
    active = false
    return Math.max(maxGap, performance.now() - last)
  }
}

// Opens the benchmark screen for themereveal-example://bench?lib=<id>, and on iOS for each id of -benchRuns.
export function useBenchmarkLink(open: (id: LibraryId, autoRun?: boolean) => void) {
  const openRef = useRef(open)
  useEffect(() => {
    openRef.current = open
  })
  useEffect(() => {
    const handle = (url: string | null) => {
      const match = url?.match(/^themereveal-example:\/\/bench\?lib=([\w-]+)/)
      const id = LIBRARY_IDS.find((value) => value === match?.[1])
      if (id) openRef.current(id)
    }
    void Linking.getInitialURL().then(handle)
    const subscription = Linking.addEventListener('url', ({ url }) => handle(url))
    const launchRuns = Platform.OS === 'ios' ? Settings.get('benchRuns') : null
    const timers =
      typeof launchRuns === 'string'
        ? launchRuns
            .split(' ')
            .map((value) => LIBRARY_IDS.find((id) => id === value))
            .filter((id) => id !== undefined)
            .map((id, index) => setTimeout(() => openRef.current(id, true), (index + 1) * LAUNCH_RUN_GAP_MS))
        : []
    return () => {
      subscription.remove()
      timers.forEach(clearTimeout)
    }
  }, [])
}

type Props = {
  library: LibraryId
  autoRun?: boolean
  topInset: number
  onSelect: (id: LibraryId) => void
  onClose: () => void
}

export function BenchmarkScreen({ library, autoRun, topInset, onSelect, onClose }: Props) {
  const [theme, setTheme] = useState<Theme>('light')
  const themeRef = useRef<Theme>(theme)
  const rootRef = useRef<View>(null)
  const runRef = useRef<View>(null)
  const running = useRef(false)
  const autoRunTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  useEffect(() => () => clearTimeout(autoRunTimer.current), [])
  const [hostProps] = useState<HostProps>(() => ({
    getTheme: () => themeRef.current,
    setTheme: (next) => {
      themeRef.current = next
      setTheme(next)
    },
    rootRef,
  }))
  const { Host, run } = LIBRARIES[library]
  const colors = palette[theme]

  const reportReady = () => {
    runRef.current?.measureInWindow((x, y, width, height) => {
      const scale = PixelRatio.get()
      const tapX = Math.round((x + width / 2) * scale)
      const tapY = Math.round((y + height / 2) * scale)
      console.error(`BENCH-READY lib=${library} x=${tapX} y=${tapY}`)
      if (!autoRun) return
      clearTimeout(autoRunTimer.current)
      autoRunTimer.current = setTimeout(
        () => runFrom(Number.NaN, x + width / 2, y + height / 2),
        AUTO_RUN_DELAY_MS,
      )
    })
  }

  const onRun = (event: GestureResponderEvent) => {
    const { timestamp, pageX, pageY } = event.nativeEvent
    runFrom(timestamp, pageX, pageY)
  }

  const runFrom = (timestamp: number, pageX: number, pageY: number) => {
    if (running.current) return
    const pressedAt = performance.now()
    const age = pressedAt - timestamp
    const fromEvent = Number.isFinite(age) && age >= 0 && age < MAX_EVENT_AGE_MS
    const t0 = fromEvent ? timestamp : pressedAt
    running.current = true
    const stopProbe = startJsProbe(pressedAt)
    const target = opposite(themeRef.current)
    const calledAt = performance.now()
    const clock = `pressAt=${format(t0)} t0=${fromEvent ? 'event' : 'onPress'}`
    run(target, { x: pageX, y: pageY })
      .then(({ overlayMs, detail }) => {
        const endedAt = performance.now()
        const jsMaxGapMs = stopProbe()
        const overlayAt = calledAt + overlayMs
        console.error(
          `BENCH lib=${library} snapshotMs=${format(overlayAt - t0)} jsMaxGapMs=${format(jsMaxGapMs)}` +
            ` totalMs=${format(endedAt - t0)} durationMs=${DURATION_MS} ${clock}` +
            ` overlayAt=${format(overlayAt)} endAt=${format(endedAt)} target=${target} ${detail}`,
        )
      })
      .catch((error: unknown) => {
        stopProbe()
        // Goes last on the line since the message can contain spaces.
        console.error(`BENCH lib=${library} ${clock} error=${String(error)}`)
      })
      .finally(() => {
        running.current = false
      })
  }

  return (
    <View ref={rootRef} collapsable={false} style={[styles.root, { backgroundColor: colors.background }]}>
      <StatusBar barStyle={theme === 'light' ? 'dark-content' : 'light-content'} />
      <View style={[styles.content, { paddingTop: topInset + 12 }]}>
        <Text style={[styles.title, { color: colors.text }]}>Benchmark</Text>
        <View style={styles.chips}>
          {LIBRARY_IDS.map((id) => (
            <Pressable
              key={id}
              accessibilityRole="radio"
              accessibilityState={{ selected: id === library }}
              onPress={() => onSelect(id)}
              style={[styles.chip, { backgroundColor: id === library ? colors.accent : colors.button }]}
            >
              <Text style={{ color: id === library ? '#ffffff' : colors.text }}>{LIBRARIES[id].label}</Text>
            </Pressable>
          ))}
          <Pressable
            accessibilityRole="button"
            onPress={onClose}
            style={[styles.chip, { backgroundColor: colors.button }]}
          >
            <Text style={{ color: colors.text }}>Close</Text>
          </Pressable>
        </View>
        <View ref={runRef} collapsable={false} onLayout={reportReady}>
          <Button label="Run" colors={colors} onPress={onRun} />
        </View>
        {ROWS.map((row) => (
          <View key={row} style={[styles.row, { backgroundColor: colors.card }]}>
            <View style={[styles.avatar, { backgroundColor: colors.accent }]} />
            <View style={styles.rowText}>
              <Text style={[styles.rowTitle, { color: colors.text }]}>Row {row}</Text>
              <Text style={[styles.rowSubtitle, { color: colors.muted }]}>
                Themed content for the snapshot
              </Text>
            </View>
          </View>
        ))}
      </View>
      {/* Last child so any overlay a library draws in React sits on top of the content. */}
      <Host {...hostProps} />
    </View>
  )
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
  content: {
    paddingHorizontal: 16,
    gap: 6,
  },
  title: {
    fontSize: 22,
    fontWeight: '600',
  },
  chips: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 6,
  },
  chip: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 16,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    padding: 8,
    borderRadius: 8,
  },
  avatar: {
    width: 28,
    height: 28,
    borderRadius: 14,
  },
  rowText: {
    flexShrink: 1,
  },
  rowTitle: {
    fontSize: 15,
  },
  rowSubtitle: {
    fontSize: 12,
  },
})
