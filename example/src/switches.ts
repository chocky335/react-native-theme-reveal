import type { GestureResponderEvent } from 'react-native'
import { originFromPress } from 'react-native-theme-reveal'
import type { MeasurableView, SwitchOptions, ThemeReveal } from 'react-native-theme-reveal'

import { STAR } from './StarShape'
import { opposite } from './theme'
import type { Theme } from './theme'

export type SwitchButton = { label: string; options: SwitchOptions; inModal?: boolean }

export const SWITCH_BUTTONS: SwitchButton[] = [
  { label: 'Reveal circle', options: { transition: 'reveal' }, inModal: true },
  { label: 'Reveal star', options: { transition: 'reveal', shape: STAR }, inModal: true },
  { label: 'Inverse reveal', options: { transition: 'inverseReveal' }, inModal: true },
  { label: 'Inverse reveal star', options: { transition: 'inverseReveal', shape: STAR } },
  { label: 'Wipe up', options: { transition: 'wipe', wipeDirection: 'up' }, inModal: true },
  { label: 'Wipe down', options: { transition: 'wipe', wipeDirection: 'down' } },
  { label: 'Wipe left', options: { transition: 'wipe', wipeDirection: 'left' } },
  { label: 'Wipe right', options: { transition: 'wipe', wipeDirection: 'right' } },
  { label: 'Crossfade', options: { transition: 'crossfade' }, inModal: true },
]

// Press event with no pageX/pageY, so originFromPress measures the view instead.
const withoutPagePoint = (event: GestureResponderEvent) =>
  ({ nativeEvent: { target: event.nativeEvent.target } }) as GestureResponderEvent

export async function switchFromPress(
  reveal: ThemeReveal<Theme>,
  getTheme: () => Theme,
  button: SwitchButton,
  event: GestureResponderEvent,
  view: MeasurableView | null,
  measureOnly = false,
): Promise<void> {
  const origin = await originFromPress(measureOnly ? withoutPagePoint(event) : event, view)
  if (origin) reveal.setOrigin(origin.point, origin.viewTag)
  await reveal.switchTo(opposite(getTheme()), button.options)
}
