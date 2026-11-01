import { Modal, Platform, ScrollView, StyleSheet, Text, View } from 'react-native'
import type { GestureResponderEvent } from 'react-native'
import type { PressCapture } from 'react-native-theme-reveal'

import { Button } from './Button'
import { SWITCH_BUTTONS } from './switches'
import type { SwitchButton } from './switches'
import { useModalLinkProbe } from './testLinks'
import type { Colors } from './theme'

export type ModalPresentation = 'fullScreen' | 'pageSheet' | 'formSheet' | 'transparent'

export const MODAL_PRESENTATIONS: ModalPresentation[] =
  Platform.OS === 'ios' ? ['fullScreen', 'pageSheet', 'formSheet', 'transparent'] : ['fullScreen', 'transparent']

const MODAL_BUTTONS = SWITCH_BUTTONS.filter((button) => button.inModal)

type Props = {
  visible: boolean
  presentation: ModalPresentation
  closeOnSelect: boolean
  colors: Colors
  topInset: number
  onSwitch: (button: SwitchButton, event: GestureResponderEvent, view: View | null) => void
  pressCapture?: PressCapture
  onClose: () => void
}

export function ModalScreen({
  visible,
  presentation,
  closeOnSelect,
  colors,
  topInset,
  onSwitch,
  pressCapture,
  onClose,
}: Props) {
  const { probeRef, onShow } = useModalLinkProbe()
  const transparent = presentation === 'transparent'
  const sheet = presentation === 'pageSheet' || presentation === 'formSheet'

  const onPress = (button: SwitchButton, event: GestureResponderEvent, view: View | null) => {
    onSwitch(button, event, view)
    if (closeOnSelect) onClose()
  }

  const content = (
    <View style={[styles.card, { backgroundColor: colors.card }, transparent && styles.floatingCard]}>
      <Text style={[styles.title, { color: colors.text }]}>
        Modal: {presentation}
        {closeOnSelect ? ', close on select' : ''}
      </Text>
      <ScrollView contentContainerStyle={styles.buttons}>
        {MODAL_BUTTONS.map((button, index) => (
          <View key={button.label} ref={index === 0 ? probeRef : undefined} collapsable={false}>
            <Button
              label={button.label}
              colors={colors}
              onPress={(event, view) => onPress(button, event, view)}
              onPressIn={pressCapture?.onPressIn}
              onPressOut={pressCapture?.onPressOut}
            />
          </View>
        ))}
        <Button label="Close" colors={colors} onPress={onClose} />
      </ScrollView>
    </View>
  )

  return (
    <Modal
      visible={visible}
      transparent={transparent}
      animationType={transparent ? 'fade' : 'slide'}
      presentationStyle={transparent ? 'overFullScreen' : presentation}
      onRequestClose={onClose}
      onShow={onShow}
    >
      {transparent ? (
        <View style={[styles.backdrop, { backgroundColor: colors.backdrop }]}>{content}</View>
      ) : (
        <View
          style={[
            styles.fill,
            { backgroundColor: colors.card, paddingTop: sheet ? 0 : topInset },
          ]}
        >
          {content}
        </View>
      )}
    </Modal>
  )
}

const styles = StyleSheet.create({
  fill: {
    flex: 1,
  },
  backdrop: {
    flex: 1,
    justifyContent: 'center',
    padding: 16,
  },
  card: {
    flex: 1,
    padding: 16,
  },
  floatingCard: {
    flex: 0,
    borderRadius: 16,
  },
  title: {
    fontSize: 18,
    fontWeight: '600',
    marginBottom: 12,
  },
  buttons: {
    gap: 8,
  },
})
