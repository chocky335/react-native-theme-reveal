// react-native-theme-switch-animation 0.8.0, `circular` from the press point.
import { useLayoutEffect } from 'react'
import type { Point } from 'react-native-theme-reveal'
import switchTheme from 'react-native-theme-switch-animation'

import type { Theme } from '../theme'
import { DURATION_MS } from './shared'
import type { HostProps, RunResult } from './shared'

// The library doesn't report an end to JS. Its circle starts once it has captured the new theme, two or more
// frames after the switch (Animations.performCircleAnimation; 122 ms after the switch on a Mi 11 Lite). The
// JS run ends this long after the switch. The frame window doesn't depend on this, since framestats.py
// follows the frames.
const END_AFTER_SWITCH_MS = DURATION_MS + 250

let host: HostProps | null = null

export function Host(props: HostProps) {
  useLayoutEffect(() => {
    host = props
  })
  useLayoutEffect(
    () => () => {
      host = null
    },
    [],
  )
  return null
}

// Resolves after the animation.
export function run(target: Theme, origin: Point): Promise<RunResult> {
  return new Promise((resolve) => {
    const calledAt = performance.now()
    switchTheme({
      // The library calls this once its screenshot overlay is in the decor view, so overlay=1 always.
      switchThemeFunction: () => {
        const overlayMs = performance.now() - calledAt
        host?.setTheme(target)
        setTimeout(() => resolve({ overlayMs, detail: 'overlay=1' }), END_AFTER_SWITCH_MS)
      },
      animationConfig: {
        type: 'circular',
        duration: DURATION_MS,
        startingPoint: { cx: origin.x, cy: origin.y },
      },
    })
  })
}
