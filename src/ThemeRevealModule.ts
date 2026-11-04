import { requireNativeModule } from 'expo'

import type { CapturePath, Transition } from './types'

type NativeThemeReveal = {
  // The capture path, or null when no overlay shows.
  prepare(viewTag: number | undefined): Promise<CapturePath | null>
  start(transition: Transition): Promise<void>
  cancel(): Promise<void>
  warmUp(): Promise<void>
  capture(viewTag: number | undefined): Promise<boolean>
  dropCapture(): Promise<void>
}

const native = requireNativeModule<NativeThemeReveal>('ThemeReveal')

export default {
  prepare: (viewTag?: number): Promise<CapturePath | null> => native.prepare(viewTag),
  start: (transition: Transition): Promise<void> => native.start(transition),
  cancel: (): Promise<void> => native.cancel(),
  warmUp: (): Promise<void> => native.warmUp(),
  capture: (viewTag?: number): Promise<boolean> => native.capture(viewTag),
  dropCapture: (): Promise<void> => native.dropCapture(),
}
