import { useEffect, useRef } from 'react'
import { Appearance } from 'react-native'
import type { ColorSchemeName } from 'react-native'

import type { Theme } from './theme'

const themeOf = (scheme: ColorSchemeName | null | undefined): Theme => (scheme === 'dark' ? 'dark' : 'light')

// While enabled, calls follow() with the system theme right away and again on every system change.
export function useFollowSystem(enabled: boolean, follow: (theme: Theme) => void) {
  const followRef = useRef(follow)
  useEffect(() => {
    followRef.current = follow
  })

  useEffect(() => {
    if (!enabled) return
    followRef.current(themeOf(Appearance.getColorScheme()))
    const subscription = Appearance.addChangeListener(({ colorScheme }) => {
      followRef.current(themeOf(colorScheme))
    })
    return () => subscription.remove()
  }, [enabled])
}
