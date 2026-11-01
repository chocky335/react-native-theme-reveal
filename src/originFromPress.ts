import type { GestureResponderEvent } from 'react-native'

import type { Point } from './types'

export type MeasurableView = {
  measureInWindow(callback: (x: number, y: number, width: number, height: number) => void): void
}

export type PressOrigin = { point: Point; viewTag?: number }

const isFiniteNumber = (value: unknown): value is number =>
  typeof value === 'number' && Number.isFinite(value)

const viewTagOf = (event: GestureResponderEvent): { viewTag?: number } => {
  const { target }: { target?: unknown } = event.nativeEvent
  return typeof target === 'number' ? { viewTag: target } : {}
}

// Touch point and target tag of a press event, or null when the event has no page point.
export function pagePointOf(event: GestureResponderEvent): PressOrigin | null {
  const { pageX, pageY }: { pageX?: unknown; pageY?: unknown } = event.nativeEvent
  if (!isFiniteNumber(pageX) || !isFiniteNumber(pageY)) return null
  return { point: { x: pageX, y: pageY }, ...viewTagOf(event) }
}

export function originFromPress(
  event: GestureResponderEvent,
  fallbackView?: MeasurableView | null,
): Promise<PressOrigin | null> {
  const tag = viewTagOf(event)
  const origin = pagePointOf(event)

  if (origin) return Promise.resolve(origin)
  if (!fallbackView) return Promise.resolve(null)
  return new Promise((resolve) => {
    fallbackView.measureInWindow((x, y, width, height) => {
      resolve({ point: { x: x + width / 2, y: y + height / 2 }, ...tag })
    })
  })
}
