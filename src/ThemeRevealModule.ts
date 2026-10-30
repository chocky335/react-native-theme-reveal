import { requireNativeModule } from 'expo'

import type { Transition } from './types'

type NativeThemeReveal = {
  prepare(viewTag: number | undefined): Promise<boolean>
  start(transition: Transition): Promise<void>
  cancel(): Promise<void>
  warmUp(keepBitmap: boolean): Promise<void>
}

const native = requireNativeModule<NativeThemeReveal>('ThemeReveal')

export default {
  prepare: (viewTag?: number): Promise<boolean> => native.prepare(viewTag),
  start: (transition: Transition): Promise<void> => native.start(transition),
  cancel: (): Promise<void> => native.cancel(),
  warmUp: (keepBitmap: boolean): Promise<void> => native.warmUp(keepBitmap),
}
