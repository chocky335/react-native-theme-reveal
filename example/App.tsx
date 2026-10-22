import { useEffect, useRef, useState } from 'react'
import { AppState, Platform, StatusBar } from 'react-native'

import { HomeScreen } from './src/HomeScreen'
import { createReveals, switchFromPress, useReveals } from './src/reveals'
import { opposite, palette } from './src/theme'
import type { Theme } from './src/theme'
import { useFollowSystem } from './src/useFollowSystem'
import { useSwitchLink } from './src/useSwitchLink'
import type { ModalLink } from './src/useSwitchLink'

const TOP_INSET = Platform.OS === 'android' ? (StatusBar.currentHeight ?? 24) : 62

export default function App() {
  const [theme, setTheme] = useState<Theme>('light')
  const themeRef = useRef<Theme>(theme)
  const getTheme = () => themeRef.current
  const [reveals] = useState(() =>
    createReveals(getTheme, (next) => {
      themeRef.current = next
      setTheme(next)
    }),
  )
  const heldTheme = useReveals(reveals)

  const [followSystem, setFollowSystem] = useState(false)
  const [measureOnly, setMeasureOnly] = useState(false)
  const [switchOnBackground, setSwitchOnBackground] = useState(false)

  // No origin: the switch falls back to a crossfade.
  useFollowSystem(followSystem, (system) => {
    if (system !== themeRef.current) void reveals.circle.switchTo(system)
  })

  const [modalLink, setModalLink] = useState<ModalLink | null>(null)
  useSwitchLink((link) => {
    if (link.kind === 'modal') {
      setModalLink(link)
      return
    }
    const { variant, transition, point } = link
    if (point) reveals[variant].setOrigin(point)
    void reveals[variant].switchTo(opposite(themeRef.current), { transition })
  })

  // Switching while the app is in the background applies the theme without animation.
  useEffect(() => {
    if (!switchOnBackground) return
    const subscription = AppState.addEventListener('change', (state) => {
      if (state !== 'background') return
      setSwitchOnBackground(false)
      void reveals.circle.switchTo(opposite(themeRef.current))
    })
    return () => subscription.remove()
  }, [switchOnBackground, reveals])

  const colors = palette[theme]

  return (
    <>
      <StatusBar barStyle={(heldTheme ?? theme) === 'light' ? 'dark-content' : 'light-content'} />
      <HomeScreen
        theme={theme}
        colors={colors}
        topInset={TOP_INSET}
        followSystem={{ value: followSystem, set: setFollowSystem }}
        measureOnly={{ value: measureOnly, set: setMeasureOnly }}
        switchOnBackground={{ value: switchOnBackground, set: setSwitchOnBackground }}
        onSwitch={(button, event, view) =>
          void switchFromPress({ reveals, getTheme, measureOnly }, button, event, view)
        }
        modalLink={modalLink}
        onModalLinkOrigin={({ variant, transition }, point, viewTag) => {
          reveals[variant].setOrigin(point, viewTag)
          void reveals[variant].switchTo(opposite(themeRef.current), { transition })
        }}
      />
    </>
  )
}
