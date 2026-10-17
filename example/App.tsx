import { useRef, useState } from 'react'
import { Pressable, StatusBar, StyleSheet, Text, View } from 'react-native'
import type { GestureResponderEvent } from 'react-native'
import { createThemeReveal, originFromPress } from 'react-native-theme-reveal'

type Theme = 'light' | 'dark'

const palette = {
  light: { background: '#ffffff', text: '#111111', button: '#e4e4e7' },
  dark: { background: '#111111', text: '#f4f4f5', button: '#3f3f46' },
} as const

export default function App() {
  const [theme, setTheme] = useState<Theme>('light')
  const themeRef = useRef<Theme>(theme)
  const buttonRef = useRef<View>(null)
  const [reveal] = useState(() =>
    createThemeReveal<Theme>({
      getTheme: () => themeRef.current,
      setTheme: (next) => {
        themeRef.current = next
        setTheme(next)
      },
    }),
  )
  reveal.useStart()

  const colors = palette[theme]

  const onPress = async (event: GestureResponderEvent) => {
    const origin = await originFromPress(event, buttonRef.current)
    if (origin) reveal.setOrigin(origin.point, origin.viewTag)
    await reveal.switchTo(themeRef.current === 'light' ? 'dark' : 'light')
  }

  return (
    <View style={[styles.container, { backgroundColor: colors.background }]}>
      <StatusBar barStyle={theme === 'light' ? 'dark-content' : 'light-content'} />
      <Text style={[styles.title, { color: colors.text }]}>Theme: {theme}</Text>
      <Pressable
        ref={buttonRef}
        onPress={onPress}
        style={[styles.button, { backgroundColor: colors.button }]}
      >
        <Text style={{ color: colors.text }}>Switch theme</Text>
      </Pressable>
    </View>
  )
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  title: {
    fontSize: 20,
    marginBottom: 24,
  },
  button: {
    paddingHorizontal: 24,
    paddingVertical: 12,
    borderRadius: 8,
  },
})
