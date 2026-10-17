import type { GestureResponderEvent } from 'react-native'

import type { Point } from './types'

export type MeasurableView = {
  measureInWindow(callback: (x: number, y: number, width: number, height: number) => void): void
}

export type PressOrigin = { point: Point; viewTag?: number }

export function originFromPress(
  event: GestureResponderEvent,
  fallbackView?: MeasurableView | null,
): Promise<PressOrigin | null> {
  const { pageX, pageY, target }: { pageX?: unknown; pageY?: unknown; target?: unknown } =
    event.nativeEvent
  const tag = typeof target === 'number' ? { viewTag: target } : {}

  if (typeof pageX === 'number' && typeof pageY === 'number') {
    return Promise.resolve({ point: { x: pageX, y: pageY }, ...tag })
  }
  if (!fallbackView) return Promise.resolve(null)
  return new Promise((resolve) => {
    fallbackView.measureInWindow((x, y, width, height) => {
      resolve({ point: { x: x + width / 2, y: y + height / 2 }, ...tag })
    })
  })
}
