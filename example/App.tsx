import { useEffect, useRef, useState } from 'react'
import { AppState, Platform, StatusBar } from 'react-native'
import { createThemeReveal } from 'react-native-theme-reveal'

import { HomeScreen } from './src/HomeScreen'
import { switchFromPress } from './src/switches'
import { TestLinksProvider } from './src/testLinks'
import { opposite, palette } from './src/theme'
import type { Theme } from './src/theme'
import { useFollowSystem } from './src/useFollowSystem'

const TOP_INSET = Platform.OS === 'android' ? (StatusBar.currentHeight ?? 24) : 62

export default function App() {
  const [theme, setTheme] = useState<Theme>('light')
  const themeRef = useRef<Theme>(theme)
  const getTheme = () => themeRef.current
  const [reveal] = useState(() =>
    createThemeReveal<Theme>({
      getTheme,
      setTheme: (next) => {
        themeRef.current = next
        setTheme(next)
      },
    }),
  )
  reveal.useStart()
  const heldTheme = reveal.useHeldTheme()

  const [followSystem, setFollowSystem] = useState(false)
  const [measureOnly, setMeasureOnly] = useState(false)
  const [switchOnBackground, setSwitchOnBackground] = useState(false)

  // No origin: the switch falls back to a crossfade.
  useFollowSystem(followSystem, (system) => {
    if (system !== themeRef.current) void reveal.switchTo(system)
  })

  // Switching while the app is in the background applies the theme without animation.
  useEffect(() => {
    if (!switchOnBackground) return
    const subscription = AppState.addEventListener('change', (state) => {
      if (state !== 'background') return
      setSwitchOnBackground(false)
      void reveal.switchTo(opposite(themeRef.current))
    })
    return () => subscription.remove()
  }, [switchOnBackground, reveal])

  const colors = palette[theme]

  return (
    <TestLinksProvider reveal={reveal} getTheme={getTheme}>
      <StatusBar barStyle={(heldTheme ?? theme) === 'light' ? 'dark-content' : 'light-content'} />
      <HomeScreen
        theme={theme}
        colors={colors}
        topInset={TOP_INSET}
        followSystem={{ value: followSystem, set: setFollowSystem }}
        measureOnly={{ value: measureOnly, set: setMeasureOnly }}
        switchOnBackground={{ value: switchOnBackground, set: setSwitchOnBackground }}
        onSwitch={(button, event, view) =>
          void switchFromPress(reveal, getTheme, button, event, view, measureOnly)
        }
      />
    </TestLinksProvider>
  )
}
