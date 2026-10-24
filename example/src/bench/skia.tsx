// The Skia technique: snapshot the root view with makeImageFromView, draw it on a Skia canvas over the
// screen, apply the new theme underneath, then grow a circular hole in the snapshot with Reanimated.
// makeImageFromView: https://shopify.github.io/react-native-skia/docs/snapshot-views/
import { Canvas, Group, Image, makeImageFromView, Skia } from '@shopify/react-native-skia'
import type { SkImage, SkSize } from '@shopify/react-native-skia'
import { useLayoutEffect, useRef, useState } from 'react'
import { StyleSheet } from 'react-native'
import { Easing, useDerivedValue, useSharedValue, withTiming } from 'react-native-reanimated'
import type { Point } from 'react-native-theme-reveal'
import { scheduleOnRN } from 'react-native-worklets'

import type { Theme } from '../theme'
import { DURATION_MS } from './shared'
import type { HostProps, RunResult } from './shared'

type Overlay = { image: SkImage; origin: Point; onDone: () => void }

type SkiaHost = HostProps & {
  // Resolves once React has committed the canvas with the snapshot.
  show: (overlay: Overlay) => Promise<void>
  // Grows the hole. At the end the overlay goes away and onDone runs.
  grow: () => void
}

let host: SkiaHost | null = null

// Two JS frames after the commit the canvas has drawn the snapshot on screen, so the theme can change
// underneath.
const afterTwoFrames = () =>
  new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))

// Canvas mounts with the benchmark screen, before the press, so creating it isn't part of a switch's cost.
export function Host(props: HostProps) {
  const [overlay, setOverlay] = useState<Overlay | null>(null)
  const shown = useRef<Overlay | null>(null)
  const committed = useRef<(() => void) | null>(null)
  const size = useSharedValue<SkSize>({ width: 0, height: 0 })
  const center = useSharedValue<Point>({ x: 0, y: 0 })
  const radius = useSharedValue(0)

  const hole = useDerivedValue(() => {
    const path = Skia.Path.Make()
    path.addCircle(center.value.x, center.value.y, radius.value)
    return path
  })
  const width = useDerivedValue(() => size.value.width)
  const height = useDerivedValue(() => size.value.height)

  // Runs right after React commits the canvas with the snapshot.
  useLayoutEffect(() => {
    if (!overlay) return
    committed.current?.()
    committed.current = null
  }, [overlay])

  useLayoutEffect(() => {
    // The worklet calls back with no arguments, so the snapshot never crosses to the UI runtime.
    const finish = () => {
      const done = shown.current
      shown.current = null
      setOverlay(null)
      done?.onDone()
    }
    host = {
      ...props,
      show: (next) =>
        new Promise((resolve) => {
          committed.current = resolve
          center.value = next.origin
          radius.value = 0
          shown.current = next
          setOverlay(next)
        }),
      grow: () => {
        const { width: w, height: h } = size.value
        const { x, y } = center.value
        const farthest = Math.max(
          Math.hypot(x, y),
          Math.hypot(w - x, y),
          Math.hypot(x, h - y),
          Math.hypot(w - x, h - y),
        )
        radius.value = withTiming(farthest, { duration: DURATION_MS, easing: Easing.linear }, () => {
          scheduleOnRN(finish)
        })
      },
    }
  })
  useLayoutEffect(
    () => () => {
      host = null
    },
    [],
  )

  return (
    <Canvas style={StyleSheet.absoluteFill} pointerEvents="none" onSize={size}>
      {overlay && (
        <Group clip={hole} invertClip>
          <Image image={overlay.image} x={0} y={0} width={width} height={height} fit="fill" />
        </Group>
      )}
    </Canvas>
  )
}

// Resolves once the hole covers the screen. overlayMs stops when React has committed the canvas with the
// snapshot.
export async function run(target: Theme, origin: Point): Promise<RunResult> {
  const current = host
  if (!current) throw new Error('no Skia host')
  const calledAt = performance.now()
  const image = await makeImageFromView(current.rootRef)
  if (!image) {
    const overlayMs = performance.now() - calledAt
    current.setTheme(target)
    return { overlayMs, detail: 'overlay=0' }
  }
  return new Promise((resolve, reject) => {
    let overlayMs = NaN
    // Only change the theme after the canvas has drawn the snapshot. In the same commit the canvas draws a
    // frame late and the new theme flashes for a frame.
    current
      .show({
        image,
        origin,
        onDone: () => {
          image.dispose()
          resolve({ overlayMs, detail: 'overlay=1' })
        },
      })
      .then(() => {
        overlayMs = performance.now() - calledAt
      })
      .then(afterTwoFrames)
      .then(() => {
        current.setTheme(target)
        current.grow()
      })
      .catch(reject)
  })
}
