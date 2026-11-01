import { useRef, useState } from 'react'
import { Pressable, ScrollView, StyleSheet, Switch, Text, TextInput, View } from 'react-native'
import type { GestureResponderEvent } from 'react-native'
import type { PressCapture } from 'react-native-theme-reveal'

import { Button } from './Button'
import { MODAL_PRESENTATIONS, ModalScreen } from './ModalScreen'
import type { ModalPresentation } from './ModalScreen'
import { SWITCH_BUTTONS } from './switches'
import type { SwitchButton } from './switches'
import { useKeyboardLink, useModalLink } from './testLinks'
import type { Colors, Theme } from './theme'

type Toggle = { value: boolean; set: (value: boolean) => void }

type Props = {
  theme: Theme
  colors: Colors
  topInset: number
  followSystem: Toggle
  measureOnly: Toggle
  switchOnBackground: Toggle
  pressIn: Toggle
  onSwitch: (button: SwitchButton, event: GestureResponderEvent, view: View | null) => void
  // Takes the snapshot on press-in, and onSwitch switches on release. Undefined means the snapshot is taken on
  // release.
  pressCapture?: PressCapture
  onOpenBenchmark: () => void
}

export function HomeScreen({
  theme,
  colors,
  topInset,
  followSystem,
  measureOnly,
  switchOnBackground,
  pressIn,
  onSwitch,
  pressCapture,
  onOpenBenchmark,
}: Props) {
  const [presentation, setPresentation] = useState<ModalPresentation>('fullScreen')
  const [closeOnSelect, setCloseOnSelect] = useState(false)
  const [modalVisible, setModalVisible] = useState(false)

  const inputRef = useRef<TextInput>(null)
  useKeyboardLink(inputRef)
  useModalLink((linkPresentation) => {
    setPresentation(linkPresentation)
    setCloseOnSelect(false)
    setModalVisible(true)
  })

  const toggleRow = (label: string, toggle: Toggle) => (
    <View style={styles.row}>
      <Text style={[styles.rowLabel, { color: colors.text }]}>{label}</Text>
      <Switch
        value={toggle.value}
        onValueChange={toggle.set}
        trackColor={{ false: colors.button, true: colors.accent }}
      />
    </View>
  )

  return (
    <ScrollView
      style={{ backgroundColor: colors.background }}
      contentContainerStyle={[styles.content, { paddingTop: topInset + 12 }]}
      keyboardShouldPersistTaps="handled"
    >
      <Text style={[styles.title, { color: colors.text }]}>Theme: {theme}</Text>
      <Text style={[styles.hint, { color: colors.muted }]}>
        Tap near a button's edge: the reveal starts where you touch.
      </Text>

      {SWITCH_BUTTONS.map((button) => (
        <Button
          key={button.label}
          label={button.label}
          colors={colors}
          onPress={(event, view) => onSwitch(button, event, view)}
          onPressIn={pressCapture?.onPressIn}
          onPressOut={pressCapture?.onPressOut}
          pressedLook={button.pressedLook}
        />
      ))}

      <TextInput
        ref={inputRef}
        keyboardAppearance={theme}
        placeholder="Type here, then switch with the keyboard up"
        placeholderTextColor={colors.muted}
        style={[styles.input, { color: colors.text, borderColor: colors.button }]}
      />

      {toggleRow('Follow system', followSystem)}
      {toggleRow('Origin from measureInWindow', measureOnly)}
      {toggleRow('Switch when the app goes to background', switchOnBackground)}
      {toggleRow('Snapshot on press-in', pressIn)}

      <View style={styles.chips}>
        {MODAL_PRESENTATIONS.map((option) => (
          <Pressable
            key={option}
            accessibilityRole="radio"
            accessibilityState={{ selected: option === presentation }}
            onPress={() => setPresentation(option)}
            style={[
              styles.chip,
              { backgroundColor: option === presentation ? colors.accent : colors.button },
            ]}
          >
            <Text style={{ color: option === presentation ? '#ffffff' : colors.text }}>{option}</Text>
          </Pressable>
        ))}
      </View>
      {toggleRow('Close modal on select', { value: closeOnSelect, set: setCloseOnSelect })}
      <Button label="Open modal" colors={colors} onPress={() => setModalVisible(true)} />
      <Button label="Benchmark" colors={colors} onPress={onOpenBenchmark} />

      <ModalScreen
        visible={modalVisible}
        presentation={presentation}
        closeOnSelect={closeOnSelect}
        colors={colors}
        topInset={topInset}
        onSwitch={onSwitch}
        pressCapture={pressCapture}
        onClose={() => setModalVisible(false)}
      />
    </ScrollView>
  )
}

const styles = StyleSheet.create({
  content: {
    paddingHorizontal: 16,
    paddingBottom: 48,
    gap: 6,
  },
  title: {
    fontSize: 22,
    fontWeight: '600',
  },
  hint: {
    fontSize: 13,
    marginBottom: 6,
  },
  input: {
    height: 40,
    borderWidth: 1,
    borderRadius: 8,
    paddingHorizontal: 12,
    marginTop: 6,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    minHeight: 36,
  },
  rowLabel: {
    fontSize: 15,
    flexShrink: 1,
  },
  chips: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 6,
    marginTop: 6,
  },
  chip: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 16,
  },
})
