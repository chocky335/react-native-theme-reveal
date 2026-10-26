import type { GestureResponderEvent } from 'react-native'

import { originFromPress } from '../originFromPress'

const pressEvent = (nativeEvent: Record<string, unknown>) =>
  ({ nativeEvent, target: 42, currentTarget: 42 }) as unknown as GestureResponderEvent

const viewAt = (x: number, y: number, w: number, h: number) => ({
  measureInWindow: jest.fn((cb: (x: number, y: number, w: number, h: number) => void) => cb(x, y, w, h)),
})

describe('originFromPress', () => {
  it('returns the touch point and the target tag for a touch', async () => {
    const view = viewAt(0, 480, 200, 64)
    const event = pressEvent({ pageX: 37, pageY: 512, locationX: 37, locationY: 32, target: 42 })

    await expect(originFromPress(event, view)).resolves.toEqual({
      point: { x: 37, y: 512 },
      viewTag: 42,
    })
    expect(view.measureInWindow).not.toHaveBeenCalled()
  })

  it('omits the view tag when the target is not a number', async () => {
    await expect(originFromPress(pressEvent({ pageX: 37, pageY: 512 }))).resolves.toEqual({
      point: { x: 37, y: 512 },
    })
  })

  it('falls back to the view centre when the touch point is undefined', async () => {
    const event = pressEvent({ pageX: undefined, pageY: undefined, target: 42 })

    await expect(originFromPress(event, viewAt(10, 480, 200, 64))).resolves.toEqual({
      point: { x: 110, y: 512 },
      viewTag: 42,
    })
  })

  it('falls back to the view centre for an accessibility click with an empty payload', async () => {
    await expect(originFromPress(pressEvent({}), viewAt(10, 480, 200, 64))).resolves.toEqual({
      point: { x: 110, y: 512 },
    })
  })

  it('returns null without a touch point and without a fallback view', async () => {
    await expect(originFromPress(pressEvent({}))).resolves.toBeNull()
    await expect(
      originFromPress(pressEvent({ pageX: undefined, pageY: undefined }), null),
    ).resolves.toBeNull()
  })

  it.each([
    [NaN, 512],
    [37, Infinity],
    [-Infinity, 512],
  ])('ignores a non-finite touch point (%p, %p)', async (pageX, pageY) => {
    const event = pressEvent({ pageX, pageY, target: 42 })

    await expect(originFromPress(event, viewAt(10, 480, 200, 64))).resolves.toEqual({
      point: { x: 110, y: 512 },
      viewTag: 42,
    })
    await expect(originFromPress(event)).resolves.toBeNull()
  })
})
