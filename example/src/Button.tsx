import { useRef } from 'react'
import { Pressable, StyleSheet, Text, View } from 'react-native'
import type { GestureResponderEvent } from 'react-native'

import type { Colors } from './theme'

type Props = {
  label: string
  colors: Colors
  onPress: (event: GestureResponderEvent, view: View | null) => void
}

// Full width, so a tap near an edge lands far from the centre.
export function Button({ label, colors, onPress }: Props) {
  const ref = useRef<View>(null)
  return (
    <Pressable
      ref={ref}
      accessibilityRole="button"
      onPress={(event) => onPress(event, ref.current)}
      style={[styles.button, { backgroundColor: colors.button }]}
    >
      <Text style={[styles.label, { color: colors.text }]}>{label}</Text>
    </Pressable>
  )
}

const styles = StyleSheet.create({
  button: {
    alignSelf: 'stretch',
    height: 40,
    borderRadius: 8,
    justifyContent: 'center',
    paddingHorizontal: 16,
  },
  label: {
    fontSize: 15,
  },
})
