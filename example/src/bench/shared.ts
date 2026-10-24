import type { RefObject } from 'react'
import type { View } from 'react-native'

import type { Theme } from '../theme'

// Every library runs the same circular switch for the same time.
export const DURATION_MS = 450

// What a library's run resolves with, at the end of its transition.
// - overlayMs: from the run call to the moment JS learns that the library's overlay is in the view tree.
// - detail: key=value pairs for the BENCH line that tell whether the run animated.
export type RunResult = { overlayMs: number; detail: string }

// Props the benchmark screen passes to each library's host component.
export type HostProps = {
  getTheme: () => Theme
  setTheme: (theme: Theme) => void
  rootRef: RefObject<View | null>
}
