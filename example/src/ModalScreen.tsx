import { useRef } from 'react'
import { findNodeHandle, Modal, Platform, ScrollView, StyleSheet, Text, View } from 'react-native'
import type { GestureResponderEvent } from 'react-native'
import type { Point } from 'react-native-theme-reveal'

import { Button } from './Button'
import { SWITCH_BUTTONS } from './reveals'
import type { SwitchButton } from './reveals'
import type { Colors } from './theme'
import type { ModalLink } from './useSwitchLink'

export type ModalPresentation = 'fullScreen' | 'pageSheet' | 'formSheet' | 'transparent'

export const MODAL_PRESENTATIONS: ModalPresentation[] =
  Platform.OS === 'ios' ? ['fullScreen', 'pageSheet', 'formSheet', 'transparent'] : ['fullScreen', 'transparent']

const MODAL_BUTTONS = SWITCH_BUTTONS.filter((button) =>
  ['Reveal circle', 'Reveal star', 'Inverse reveal', 'Wipe up', 'Crossfade'].includes(button.label),
)

type Props = {
  visible: boolean
  presentation: ModalPresentation
  closeOnSelect: boolean
  colors: Colors
  topInset: number
  onSwitch: (button: SwitchButton, event: GestureResponderEvent, view: View | null) => void
  onClose: () => void
  modalLink: ModalLink | null
  onModalLinkOrigin: (link: ModalLink, point: Point, viewTag?: number) => void
}

// Waits for the presentation to settle before a modal link measures the first button.
const MODAL_LINK_DELAY_MS = 500

export function ModalScreen({
  visible,
  presentation,
  closeOnSelect,
  colors,
  topInset,
  onSwitch,
  onClose,
  modalLink,
  onModalLinkOrigin,
}: Props) {
  const firstButtonRef = useRef<View>(null)
  const transparent = presentation === 'transparent'
  const sheet = presentation === 'pageSheet' || presentation === 'formSheet'

  const onPress = (button: SwitchButton, event: GestureResponderEvent, view: View | null) => {
    onSwitch(button, event, view)
    if (closeOnSelect) onClose()
  }

  // A modal link measures the first button the way a press reports it (page or window point, 10% from its left
  // edge, its vertical centre) and switches with the button's view tag.
  const onShow = () => {
    if (!modalLink) return
    setTimeout(() => {
      const view = firstButtonRef.current
      if (!view) return
      const viewTag = findNodeHandle(view) ?? undefined
      const switchAt = (x: number, y: number, width: number, height: number) =>
        onModalLinkOrigin(modalLink, { x: x + 0.1 * width, y: y + height / 2 }, viewTag)
      if (modalLink.measure === 'window') view.measureInWindow(switchAt)
      else view.measure((_x, _y, width, height, pageX, pageY) => switchAt(pageX, pageY, width, height))
    }, MODAL_LINK_DELAY_MS)
  }

  const content = (
    <View style={[styles.card, { backgroundColor: colors.card }, transparent && styles.floatingCard]}>
      <Text style={[styles.title, { color: colors.text }]}>
        Modal: {presentation}
        {closeOnSelect ? ', close on select' : ''}
      </Text>
      <ScrollView contentContainerStyle={styles.buttons}>
        {MODAL_BUTTONS.map((button, index) => (
          <View key={button.label} ref={index === 0 ? firstButtonRef : undefined} collapsable={false}>
            <Button
              label={button.label}
              colors={colors}
              onPress={(event, view) => onPress(button, event, view)}
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
