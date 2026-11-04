import { requireNativeModule } from 'expo'

import ThemeRevealModule from '../ThemeRevealModule'
import type { CapturePath, Transition } from '../types'

type NativeMock = {
  prepare: jest.Mock<Promise<CapturePath | null>, [number | undefined]>
  start: jest.Mock<Promise<void>, [Transition]>
  cancel: jest.Mock<Promise<void>, []>
  warmUp: jest.Mock<Promise<void>, []>
  capture: jest.Mock<Promise<boolean>, [number | undefined]>
  dropCapture: jest.Mock<Promise<void>, []>
}

jest.mock('expo', () => {
  const actual = jest.requireActual<typeof import('expo')>('expo')
  const native = {
    prepare: jest.fn(() => Promise.resolve('gpu')),
    start: jest.fn(() => Promise.resolve()),
    cancel: jest.fn(() => Promise.resolve()),
    warmUp: jest.fn(() => Promise.resolve()),
    capture: jest.fn(() => Promise.resolve(true)),
    dropCapture: jest.fn(() => Promise.resolve()),
  }
  return {
    ...actual,
    requireNativeModule: (name: string) =>
      name === 'ThemeReveal' ? native : actual.requireNativeModule(name),
  }
})

const native = requireNativeModule<NativeMock>('ThemeReveal')

describe('ThemeRevealModule', () => {
  it('passes the view tag to native prepare and resolves its capture path', async () => {
    await expect(ThemeRevealModule.prepare(7)).resolves.toBe('gpu')
    expect(native.prepare).toHaveBeenCalledTimes(1)
    expect(native.prepare).toHaveBeenCalledWith(7)
  })

  it('passes undefined to native prepare without a view tag', async () => {
    await ThemeRevealModule.prepare()
    expect(native.prepare).toHaveBeenCalledTimes(1)
    expect(native.prepare).toHaveBeenCalledWith(undefined)
  })

  it('passes the transition object to native start unchanged', async () => {
    const transition: Transition = {
      type: 'reveal',
      origin: { x: 10, y: 20 },
      durationMs: 450,
      shape: { path: 'M0 0L1 1Z', anchor: { x: 0.5, y: 0.5 }, inscribedRadius: 0.4 },
    }
    await ThemeRevealModule.start(transition)
    expect(native.start).toHaveBeenCalledTimes(1)
    expect(native.start.mock.calls[0]?.[0]).toBe(transition)
  })

  it('calls native cancel', async () => {
    await ThemeRevealModule.cancel()
    expect(native.cancel).toHaveBeenCalledTimes(1)
  })

  it('calls native warmUp with no arguments', async () => {
    await ThemeRevealModule.warmUp()
    expect(native.warmUp).toHaveBeenCalledTimes(1)
    expect(native.warmUp).toHaveBeenCalledWith()
  })

  it('passes the view tag to native capture', async () => {
    await expect(ThemeRevealModule.capture(7)).resolves.toBe(true)
    expect(native.capture).toHaveBeenCalledTimes(1)
    expect(native.capture).toHaveBeenCalledWith(7)
  })

  it('calls native dropCapture', async () => {
    await ThemeRevealModule.dropCapture()
    expect(native.dropCapture).toHaveBeenCalledTimes(1)
  })
})
