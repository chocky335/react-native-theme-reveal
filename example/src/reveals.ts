import type { GestureResponderEvent } from 'react-native'
import { createThemeReveal, originFromPress } from 'react-native-theme-reveal'
import type {
  MeasurableView,
  ThemeReveal,
  ThemeRevealOptions,
  TransitionType,
} from 'react-native-theme-reveal'

import { STAR } from './StarShape'
import { opposite } from './theme'
import type { Theme } from './theme'

// Shape and wipe direction are factory options, so each one gets its own instance.
export type Variant = 'circle' | 'star' | 'wipeUp' | 'wipeDown' | 'wipeLeft' | 'wipeRight'

export type Reveals = Record<Variant, ThemeReveal<Theme>>

export type SwitchButton = { label: string; variant: Variant; transition: TransitionType }

export const SWITCH_BUTTONS: SwitchButton[] = [
  { label: 'Reveal circle', variant: 'circle', transition: 'reveal' },
  { label: 'Reveal star', variant: 'star', transition: 'reveal' },
  { label: 'Inverse reveal', variant: 'circle', transition: 'inverseReveal' },
  { label: 'Inverse reveal star', variant: 'star', transition: 'inverseReveal' },
  { label: 'Wipe up', variant: 'wipeUp', transition: 'wipe' },
  { label: 'Wipe down', variant: 'wipeDown', transition: 'wipe' },
  { label: 'Wipe left', variant: 'wipeLeft', transition: 'wipe' },
  { label: 'Wipe right', variant: 'wipeRight', transition: 'wipe' },
  { label: 'Crossfade', variant: 'circle', transition: 'crossfade' },
]

export function createReveals(getTheme: () => Theme, setTheme: (theme: Theme) => void): Reveals {
  const make = (extra: Pick<ThemeRevealOptions<Theme>, 'shape' | 'wipeDirection'>) =>
    createThemeReveal<Theme>({
      getTheme,
      setTheme,
      ...extra,
      onSettled: (info) => console.log(`theme-reveal settled ${JSON.stringify(info)}`),
    })
  return {
    circle: make({}),
    star: make({ shape: STAR }),
    wipeUp: make({ wipeDirection: 'up' }),
    wipeDown: make({ wipeDirection: 'down' }),
    wipeLeft: make({ wipeDirection: 'left' }),
    wipeRight: make({ wipeDirection: 'right' }),
  }
}

// Mounts every instance's start hook and returns the theme any of them holds for the system bars.
// The instance set never changes, so the hook order is stable.
export function useReveals(reveals: Reveals): Theme | null {
  let held: Theme | null = null
  for (const reveal of Object.values(reveals)) {
    reveal.useStart()
    held = reveal.useHeldTheme() ?? held
  }
  return held
}

// Press event with no pageX/pageY, so originFromPress measures the view instead.
const withoutPagePoint = (event: GestureResponderEvent) =>
  ({ nativeEvent: { target: event.nativeEvent.target } }) as GestureResponderEvent

export type PressSwitch = {
  reveals: Reveals
  getTheme: () => Theme
  measureOnly: boolean
}

export async function switchFromPress(
  { reveals, getTheme, measureOnly }: PressSwitch,
  button: SwitchButton,
  event: GestureResponderEvent,
  view: MeasurableView | null,
): Promise<void> {
  const origin = await originFromPress(measureOnly ? withoutPagePoint(event) : event, view)
  const reveal = reveals[button.variant]
  if (origin) reveal.setOrigin(origin.point, origin.viewTag)
  await reveal.switchTo(opposite(getTheme()), { transition: button.transition })
}
