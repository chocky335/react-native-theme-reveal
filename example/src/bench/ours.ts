// react-native-theme-reveal, circle reveal from the press point.
import { useLayoutEffect } from 'react'
import { createThemeReveal } from 'react-native-theme-reveal'
import type { Point } from 'react-native-theme-reveal'

import type { Theme } from '../theme'
import { DURATION_MS } from './shared'
import type { HostProps, RunResult } from './shared'

let host: HostProps | null = null
let pending: { calledAt: number; overlayMs: number; resolve: (result: RunResult) => void } | null = null

const reveal = createThemeReveal<Theme>({
  getTheme: () => host?.getTheme() ?? 'light',
  // The package calls setTheme once prepare has added the native overlay to the window.
  setTheme: (theme) => {
    if (pending) pending.overlayMs = performance.now() - pending.calledAt
    host?.setTheme(theme)
  },
  durations: { reveal: DURATION_MS },
  // transition is null when prepare showed no overlay.
  onSettled: ({ transition }) => {
    const done = pending
    pending = null
    done?.resolve({ overlayMs: done.overlayMs, detail: `transition=${transition ?? 'null'}` })
  },
})

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
  reveal.useStart()
  return null
}

// Resolves when the reveal settles.
export function run(target: Theme, origin: Point): Promise<RunResult> {
  return new Promise((resolve) => {
    pending = { calledAt: performance.now(), overlayMs: NaN, resolve }
    reveal.setOrigin(origin)
    void reveal.switchTo(target, { transition: 'reveal' })
  })
}
