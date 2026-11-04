import { act, renderHook } from '@testing-library/react-native'
import { StrictMode } from 'react'
import type { GestureResponderEvent } from 'react-native'

import ThemeRevealModule from '../ThemeRevealModule'
import { createThemeReveal, type ThemeRevealOptions } from '../createThemeReveal'
import type { CapturePath } from '../types'

jest.mock('../ThemeRevealModule', () => ({
  __esModule: true,
  default: {
    prepare: jest.fn(),
    start: jest.fn(),
    cancel: jest.fn(),
    capture: jest.fn(),
    dropCapture: jest.fn(),
    warmUp: jest.fn(),
  },
}))

type Theme = { name: string }

const light: Theme = { name: 'light' }
const dark: Theme = { name: 'dark' }
const dim: Theme = { name: 'dim' }

const prepare = jest.mocked(ThemeRevealModule.prepare)
const start = jest.mocked(ThemeRevealModule.start)
const cancel = jest.mocked(ThemeRevealModule.cancel)
const warmUp = jest.mocked(ThemeRevealModule.warmUp)
const capture = jest.mocked(ThemeRevealModule.capture)
const dropCapture = jest.mocked(ThemeRevealModule.dropCapture)

const setup = (options: Partial<ThemeRevealOptions<Theme>> = {}) => {
  let current = light
  const setTheme = jest.fn((theme: Theme) => {
    current = theme
  })
  const onError = jest.fn()
  const reveal = createThemeReveal<Theme>({
    getTheme: () => current,
    setTheme,
    onError,
    ...options,
  })
  const mount = (wrapper?: typeof StrictMode) =>
    renderHook(
      () => {
        reveal.useStart()
        return reveal.useHeldTheme()
      },
      { wrapper },
    )
  return { reveal, setTheme, onError, mount, getTheme: () => current }
}

const deferStart = () => {
  let finish: () => void = () => {}
  start.mockReturnValueOnce(
    new Promise<void>((resolve) => {
      finish = resolve
    }),
  )
  return () => act(async () => finish())
}

beforeEach(() => {
  prepare.mockReset().mockResolvedValue('gpu')
  start.mockReset().mockResolvedValue(undefined)
  cancel.mockReset().mockResolvedValue(undefined)
  warmUp.mockReset().mockResolvedValue(undefined)
  capture.mockReset().mockResolvedValue(true)
  dropCapture.mockReset().mockResolvedValue(undefined)
})

afterEach(() => {
  jest.restoreAllMocks()
})

describe('switchTo', () => {
  it('applies the theme under the snapshot and queues the start', async () => {
    const { reveal, getTheme, mount } = setup()

    await reveal.switchTo(dark)

    expect(prepare).toHaveBeenCalledTimes(1)
    expect(getTheme()).toBe(dark)
    expect(start).not.toHaveBeenCalled()

    const view = mount()

    expect(start).toHaveBeenCalledTimes(1)
    expect(start).toHaveBeenCalledWith({ type: 'crossfade', durationMs: 300 })

    await act(async () => {})

    expect(view.result.current).toBeNull()
  })

  it('uses the origin for the next switch only', async () => {
    const { reveal, mount } = setup()
    mount()

    reveal.setOrigin({ x: 10, y: 20 })
    await act(() => reveal.switchTo(dark))
    await act(() => reveal.switchTo(light))

    expect(start).toHaveBeenCalledTimes(2)
    expect(start).toHaveBeenNthCalledWith(1, {
      type: 'reveal',
      origin: { x: 10, y: 20 },
      durationMs: 450,
    })
    expect(start).toHaveBeenNthCalledWith(2, { type: 'crossfade', durationMs: 300 })
  })

  it('cancels and skips when the theme is already on screen', async () => {
    const { reveal, setTheme, mount } = setup()
    mount()

    reveal.setOrigin({ x: 10, y: 20 })
    await act(() => reveal.switchTo(light))

    expect(cancel).toHaveBeenCalledTimes(1)
    expect(prepare).not.toHaveBeenCalled()
    expect(setTheme).not.toHaveBeenCalled()
    expect(start).not.toHaveBeenCalled()

    await act(() => reveal.switchTo(dark))

    expect(start).toHaveBeenCalledWith({ type: 'crossfade', durationMs: 300 })
  })

  it('switches without animation when prepare returns null', async () => {
    prepare.mockResolvedValue(null)
    const { reveal, getTheme, onError, mount } = setup()
    const view = mount()

    await act(() => reveal.switchTo(dark))

    expect(getTheme()).toBe(dark)
    expect(start).not.toHaveBeenCalled()
    expect(view.result.current).toBeNull()
    expect(onError).not.toHaveBeenCalled()
  })

  it('switches without animation when prepare rejects', async () => {
    const error = new Error('no window')
    prepare.mockRejectedValue(error)
    const { reveal, getTheme, onError, mount } = setup()
    mount()

    await act(() => reveal.switchTo(dark))

    expect(getTheme()).toBe(dark)
    expect(start).not.toHaveBeenCalled()
    expect(onError).toHaveBeenCalledTimes(1)
    expect(onError).toHaveBeenCalledWith(error)
  })

  it('reports errors through console.warn by default', async () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {})
    const error = new Error('no window')
    prepare.mockRejectedValue(error)
    const { reveal } = setup({ onError: undefined })

    await reveal.switchTo(dark)

    expect(warn).toHaveBeenCalledTimes(1)
    expect(warn).toHaveBeenCalledWith(error)
  })

  it('lets the newest switch win', async () => {
    let resolveFirst: (capture: CapturePath | null) => void = () => {}
    prepare.mockReturnValueOnce(
      new Promise<CapturePath | null>((resolve) => {
        resolveFirst = resolve
      }),
    )
    const { reveal, setTheme, getTheme, mount } = setup()
    const view = mount()

    const first = reveal.switchTo(dark)
    reveal.setOrigin({ x: 1, y: 2 })
    await act(() => reveal.switchTo(dim))
    await act(async () => {
      resolveFirst('gpu')
      await first
    })

    expect(getTheme()).toBe(dim)
    expect(setTheme).toHaveBeenCalledTimes(1)
    expect(setTheme).toHaveBeenCalledWith(dim)
    expect(start).toHaveBeenCalledTimes(1)
    expect(start).toHaveBeenCalledWith({ type: 'reveal', origin: { x: 1, y: 2 }, durationMs: 450 })
    expect(view.result.current).toBeNull()
  })

  it('passes viewTag from setOrigin to prepare', async () => {
    const { reveal } = setup()

    reveal.setOrigin({ x: 1, y: 2 }, 42)
    await reveal.switchTo(dark)
    await reveal.switchTo(light)

    expect(prepare).toHaveBeenNthCalledWith(1, 42)
    expect(prepare).toHaveBeenNthCalledWith(2, undefined)
  })

  it('drops an older switch when a same-theme switch comes during its prepare', async () => {
    let resolveFirst: (capture: CapturePath | null) => void = () => {}
    prepare.mockReturnValueOnce(
      new Promise<CapturePath | null>((resolve) => {
        resolveFirst = resolve
      }),
    )
    const { reveal, setTheme, getTheme, mount } = setup()
    const view = mount()

    const first = reveal.switchTo(dark)
    await act(() => reveal.switchTo(light))

    expect(cancel).toHaveBeenCalledTimes(1)

    await act(async () => {
      resolveFirst('gpu')
      await first
    })

    expect(getTheme()).toBe(light)
    expect(setTheme).not.toHaveBeenCalled()
    expect(start).not.toHaveBeenCalled()
    expect(view.result.current).toBeNull()
  })

  it('applies the theme at once when prepare returns null during a running animation', async () => {
    const finish = deferStart()
    const onSettled = jest.fn()
    const { reveal, getTheme, mount } = setup({ onSettled })
    const view = mount()

    await act(() => reveal.switchTo(dark))

    expect(start).toHaveBeenCalledTimes(1)
    expect(view.result.current).toBe(light)

    prepare.mockResolvedValueOnce(null)
    await act(() => reveal.switchTo(light))

    expect(getTheme()).toBe(light)
    expect(start).toHaveBeenCalledTimes(1)
    expect(onSettled).toHaveBeenCalledTimes(1)
    expect(onSettled).toHaveBeenLastCalledWith(expect.objectContaining({ transition: null }))

    await finish()

    expect(getTheme()).toBe(light)
    expect(view.result.current).toBeNull()
    expect(onSettled).toHaveBeenCalledTimes(2)
  })
})

describe('transition choice', () => {
  it('prefers the switchTo option over the factory default', async () => {
    const { reveal, mount } = setup({ transition: 'wipe' })
    mount()

    await act(() => reveal.switchTo(dark, { transition: 'crossfade' }))
    await act(() => reveal.switchTo(light))

    expect(start).toHaveBeenNthCalledWith(1, { type: 'crossfade', durationMs: 300 })
    expect(start).toHaveBeenNthCalledWith(2, { type: 'wipe', direction: 'up', durationMs: 400 })
  })

  it('falls back to crossfade for a shape transition without an origin', async () => {
    const { reveal, mount } = setup({ transition: 'inverseReveal' })
    mount()

    await act(() => reveal.switchTo(dark))

    expect(start).toHaveBeenCalledWith({ type: 'crossfade', durationMs: 300 })
  })

  it('applies the shape, durations and wipe direction options', async () => {
    const shape = { path: 'M0 0L1 1Z', anchor: { x: 0.5, y: 0.5 }, inscribedRadius: 0.4 }
    const { reveal, mount } = setup({
      shape,
      durations: { inverseReveal: 600, wipe: 250 },
      wipeDirection: 'left',
    })
    mount()

    reveal.setOrigin({ x: 3, y: 4 })
    await act(() => reveal.switchTo(dark, { transition: 'inverseReveal' }))
    await act(() => reveal.switchTo(light, { transition: 'wipe' }))

    expect(start).toHaveBeenNthCalledWith(1, {
      type: 'inverseReveal',
      origin: { x: 3, y: 4 },
      durationMs: 600,
      shape,
    })
    expect(start).toHaveBeenNthCalledWith(2, { type: 'wipe', direction: 'left', durationMs: 250 })
  })

  it('passes a per-switch shape to reveal and inverseReveal', async () => {
    const shape = { path: 'M0 0L1 1Z', anchor: { x: 0.5, y: 0.5 }, inscribedRadius: 0.4 }
    const { reveal, mount } = setup()
    mount()

    reveal.setOrigin({ x: 3, y: 4 })
    await act(() => reveal.switchTo(dark, { transition: 'reveal', shape }))
    reveal.setOrigin({ x: 5, y: 6 })
    await act(() => reveal.switchTo(light, { transition: 'inverseReveal', shape }))

    expect(start).toHaveBeenNthCalledWith(1, {
      type: 'reveal',
      origin: { x: 3, y: 4 },
      durationMs: 450,
      shape,
    })
    expect(start).toHaveBeenNthCalledWith(2, {
      type: 'inverseReveal',
      origin: { x: 5, y: 6 },
      durationMs: 450,
      shape,
    })
  })

  it('passes a per-switch wipe direction to the wipe', async () => {
    const { reveal, mount } = setup({ wipeDirection: 'left' })
    mount()

    await act(() => reveal.switchTo(dark, { transition: 'wipe', wipeDirection: 'right' }))

    expect(start).toHaveBeenCalledWith({ type: 'wipe', direction: 'right', durationMs: 400 })
  })

  it('uses the factory shape and wipe direction when the switch has none', async () => {
    const shape = { path: 'M0 0L1 1Z', anchor: { x: 0.5, y: 0.5 }, inscribedRadius: 0.4 }
    const { reveal, mount } = setup({ shape, wipeDirection: 'down' })
    mount()

    reveal.setOrigin({ x: 3, y: 4 })
    await act(() => reveal.switchTo(dark, { transition: 'reveal' }))
    await act(() => reveal.switchTo(light, { transition: 'wipe' }))

    expect(start).toHaveBeenNthCalledWith(1, {
      type: 'reveal',
      origin: { x: 3, y: 4 },
      durationMs: 450,
      shape,
    })
    expect(start).toHaveBeenNthCalledWith(2, { type: 'wipe', direction: 'down', durationMs: 400 })
  })
})

describe('held theme', () => {
  it('holds the previous theme until start resolves', async () => {
    const finish = deferStart()
    const { reveal, getTheme, mount } = setup()
    const view = mount()

    await act(() => reveal.switchTo(dark))

    expect(getTheme()).toBe(dark)
    expect(view.result.current).toBe(light)

    await finish()

    expect(view.result.current).toBeNull()
  })

  it('keeps a newer hold when an older start resolves', async () => {
    const finishFirst = deferStart()
    const finishSecond = deferStart()
    const { reveal, mount } = setup()
    const view = mount()

    await act(() => reveal.switchTo(dark))
    await act(() => reveal.switchTo(light))

    expect(view.result.current).toBe(dark)

    await finishFirst()

    expect(view.result.current).toBe(dark)

    await finishSecond()

    expect(view.result.current).toBeNull()
  })
})

describe('useStart', () => {
  it('starts once when StrictMode mounts it with a queued start', async () => {
    const { reveal, mount } = setup()
    await reveal.switchTo(dark)

    mount(StrictMode)
    await act(async () => {})

    expect(start).toHaveBeenCalledTimes(1)
  })

  it('reports a start rejection through onError and releases the hold', async () => {
    const error = new Error('start failed')
    start.mockRejectedValueOnce(error)
    const { reveal, onError, mount } = setup()
    const view = mount()

    await act(() => reveal.switchTo(dark))

    expect(onError).toHaveBeenCalledWith(error)
    expect(view.result.current).toBeNull()
  })
})

describe('warm-up', () => {
  it('warms up native when useStart mounts', async () => {
    const { mount } = setup()

    expect(warmUp).not.toHaveBeenCalled()

    mount()
    await act(async () => {})

    expect(warmUp).toHaveBeenCalledTimes(1)
  })

  it('does not warm up when the warmUp option is false', async () => {
    const { reveal, getTheme, mount } = setup({ warmUp: false })

    mount(StrictMode)
    await act(() => reveal.switchTo(dark))

    expect(warmUp).not.toHaveBeenCalled()
    expect(getTheme()).toBe(dark)
    expect(start).toHaveBeenCalledTimes(1)
  })

  it('warms up once per instance', async () => {
    const { mount } = setup()

    mount(StrictMode)
    const second = mount()
    second.rerender({})
    second.unmount()
    mount()
    await act(async () => {})

    expect(warmUp).toHaveBeenCalledTimes(1)
  })

  it('warms up each instance that has the option on', async () => {
    setup().mount()
    setup({ warmUp: false }).mount()
    setup({ warmUp: true }).mount()
    await act(async () => {})

    expect(warmUp).toHaveBeenCalledTimes(2)
  })

  it('reports a warm-up rejection through onError and still switches', async () => {
    const error = new Error('warm-up failed')
    warmUp.mockRejectedValueOnce(error)
    const { reveal, getTheme, onError, mount } = setup()
    mount()

    await act(() => reveal.switchTo(dark))

    expect(onError).toHaveBeenCalledWith(error)
    expect(getTheme()).toBe(dark)
    expect(start).toHaveBeenCalledWith({ type: 'crossfade', durationMs: 300 })
  })

  it('reports a warm-up that throws at once through onError and still switches', async () => {
    const error = new Error('no warmUp')
    warmUp.mockImplementationOnce(() => {
      throw error
    })
    const { reveal, getTheme, onError, mount } = setup()
    mount()

    await act(() => reveal.switchTo(dark))

    expect(onError).toHaveBeenCalledWith(error)
    expect(getTheme()).toBe(dark)
    expect(start).toHaveBeenCalledTimes(1)
  })
})

describe('failure paths', () => {
  it('applies the theme when onError throws for a failed prepare', async () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {})
    const thrown = new Error('onError failed')
    prepare.mockRejectedValue(new Error('no window'))
    const { reveal, getTheme } = setup({
      onError: () => {
        throw thrown
      },
    })

    await expect(reveal.switchTo(dark)).resolves.toBeUndefined()

    expect(getTheme()).toBe(dark)
    expect(warn).toHaveBeenCalledWith(thrown)
  })

  it('resolves the same-theme switch when onError throws for a failed cancel', async () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {})
    const thrown = new Error('onError failed')
    cancel.mockRejectedValue(new Error('cancel failed'))
    const { reveal } = setup({
      onError: () => {
        throw thrown
      },
    })

    await expect(reveal.switchTo(light)).resolves.toBeUndefined()

    expect(warn).toHaveBeenCalledWith(thrown)
  })

  it('resolves the switch when onSettled throws after a switch with no snapshot', async () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {})
    const thrown = new Error('onSettled failed')
    prepare.mockResolvedValue(null)
    const { reveal, getTheme } = setup({
      onSettled: () => {
        throw thrown
      },
    })

    await expect(reveal.switchTo(dark)).resolves.toBeUndefined()

    expect(getTheme()).toBe(dark)
    expect(warn).toHaveBeenCalledWith(thrown)
  })

  it('catches a throwing onError and onSettled after a start rejection', async () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {})
    const onErrorThrown = new Error('onError failed')
    const onSettledThrown = new Error('onSettled failed')
    start.mockRejectedValueOnce(new Error('start failed'))
    const { reveal, mount } = setup({
      onError: () => {
        throw onErrorThrown
      },
      onSettled: () => {
        throw onSettledThrown
      },
    })
    const view = mount()

    await act(() => reveal.switchTo(dark))

    expect(warn).toHaveBeenCalledWith(onErrorThrown)
    expect(warn).toHaveBeenCalledWith(onSettledThrown)
    expect(view.result.current).toBeNull()
  })

  it('removes the snapshot and releases the hold when setTheme throws', async () => {
    const thrown = new Error('setTheme failed')
    const onSettled = jest.fn()
    const { reveal, mount } = setup({
      setTheme: () => {
        throw thrown
      },
      onSettled,
    })
    const view = mount()

    await act(() => expect(reveal.switchTo(dark)).rejects.toBe(thrown))

    expect(cancel).toHaveBeenCalledTimes(1)
    expect(start).not.toHaveBeenCalled()
    expect(view.result.current).toBeNull()
    expect(onSettled).not.toHaveBeenCalled()
  })

  it('rejects with the setTheme error when there was no snapshot', async () => {
    const thrown = new Error('setTheme failed')
    prepare.mockResolvedValue(null)
    const onSettled = jest.fn()
    const { reveal } = setup({
      setTheme: () => {
        throw thrown
      },
      onSettled,
    })

    await expect(reveal.switchTo(dark)).rejects.toBe(thrown)

    expect(cancel).not.toHaveBeenCalled()
    expect(onSettled).not.toHaveBeenCalled()
  })
})

describe('onSettled', () => {
  it('reports onSettled with prepareMs and totalMs', async () => {
    let now = 100
    jest.spyOn(performance, 'now').mockImplementation(() => now)
    prepare.mockImplementation(async () => {
      now = 130
      return 'pixelcopy'
    })
    const finish = deferStart()
    const onSettled = jest.fn()
    const { reveal, mount } = setup({ onSettled })
    mount()

    await act(() => reveal.switchTo(dark))

    expect(onSettled).not.toHaveBeenCalled()

    now = 600
    await finish()

    expect(onSettled).toHaveBeenCalledTimes(1)
    expect(onSettled).toHaveBeenCalledWith({
      transition: 'crossfade',
      capture: 'pixelcopy',
      prepareMs: 30,
      totalMs: 500,
    })
  })

  it('reports onSettled right after setTheme when there was no snapshot', async () => {
    let now = 100
    jest.spyOn(performance, 'now').mockImplementation(() => now)
    prepare.mockImplementation(async () => {
      now = 120
      return null
    })
    const onSettled = jest.fn()
    const { reveal } = setup({ onSettled })

    await reveal.switchTo(dark)

    expect(onSettled).toHaveBeenCalledTimes(1)
    expect(onSettled).toHaveBeenCalledWith({ transition: null, capture: null, prepareMs: 20, totalMs: 20 })
  })
})

describe('press-in capture', () => {
  const pressIn = (nativeEvent: Record<string, unknown>) =>
    ({ nativeEvent }) as unknown as GestureResponderEvent

  beforeEach(() => {
    jest.useFakeTimers()
  })

  afterEach(() => {
    jest.useRealTimers()
  })

  it('switches with one prepare that uses the held capture and its point', async () => {
    const { reveal, getTheme, mount } = setup()
    mount()

    reveal.capture({ x: 5, y: 6 }, 7)

    expect(capture).toHaveBeenCalledTimes(1)
    expect(capture).toHaveBeenCalledWith(7)

    await act(() => reveal.switchTo(dark))
    jest.runAllTimers()

    expect(prepare).toHaveBeenCalledTimes(1)
    expect(prepare).toHaveBeenCalledWith(7)
    expect(getTheme()).toBe(dark)
    expect(start).toHaveBeenCalledWith({ type: 'reveal', origin: { x: 5, y: 6 }, durationMs: 450 })
    expect(dropCapture).not.toHaveBeenCalled()
  })

  it('captures from the press-in point and target', () => {
    const { reveal } = setup()

    reveal.pressCapture.onPressIn(pressIn({ pageX: 5, pageY: 6, target: 7 }))

    expect(capture).toHaveBeenCalledTimes(1)
    expect(capture).toHaveBeenCalledWith(7)
  })

  it('does not capture a press-in with no page point', () => {
    const { reveal } = setup()

    reveal.pressCapture.onPressIn(pressIn({ target: 7 }))

    expect(capture).not.toHaveBeenCalled()
  })

  it('drops a cancelled press after a deferral and forgets its point', async () => {
    const { reveal, mount } = setup()
    mount()

    reveal.pressCapture.onPressIn(pressIn({ pageX: 5, pageY: 6, target: 7 }))
    reveal.pressCapture.onPressOut()

    expect(dropCapture).not.toHaveBeenCalled()

    jest.advanceTimersByTime(0)

    expect(dropCapture).toHaveBeenCalledTimes(1)

    await act(() => reveal.switchTo(dark))

    expect(prepare).toHaveBeenCalledWith(undefined)
    expect(start).toHaveBeenCalledWith({ type: 'crossfade', durationMs: 300 })
  })

  it('cancels the pending drop when switchTo follows onPressOut', async () => {
    const { reveal, mount } = setup()
    mount()

    reveal.pressCapture.onPressIn(pressIn({ pageX: 5, pageY: 6, target: 7 }))
    reveal.pressCapture.onPressOut()
    await act(() => reveal.switchTo(dark))
    jest.runAllTimers()

    expect(dropCapture).not.toHaveBeenCalled()
    expect(prepare).toHaveBeenCalledWith(7)
    expect(start).toHaveBeenCalledWith({ type: 'reveal', origin: { x: 5, y: 6 }, durationMs: 450 })
  })

  it('ignores an onPressOut that comes after switchTo', async () => {
    const { reveal } = setup()

    reveal.pressCapture.onPressIn(pressIn({ pageX: 5, pageY: 6, target: 7 }))
    await reveal.switchTo(dark)
    reveal.pressCapture.onPressOut()
    jest.runAllTimers()

    expect(dropCapture).not.toHaveBeenCalled()
  })

  it('drops the capture after the timeout and keeps its point', async () => {
    const { reveal, mount } = setup()
    mount()

    reveal.capture({ x: 5, y: 6 }, 7)
    jest.advanceTimersByTime(2999)

    expect(dropCapture).not.toHaveBeenCalled()

    jest.advanceTimersByTime(1)

    expect(dropCapture).toHaveBeenCalledTimes(1)

    await act(() => reveal.switchTo(dark))

    expect(start).toHaveBeenCalledWith({ type: 'reveal', origin: { x: 5, y: 6 }, durationMs: 450 })
  })

  it('replaces the first capture with a second one', async () => {
    const { reveal, mount } = setup()
    mount()

    reveal.capture({ x: 1, y: 2 }, 1)
    jest.advanceTimersByTime(2000)
    reveal.capture({ x: 5, y: 6 }, 7)
    jest.advanceTimersByTime(1500)

    expect(dropCapture).not.toHaveBeenCalled()
    expect(capture).toHaveBeenNthCalledWith(2, 7)

    await act(() => reveal.switchTo(dark))

    expect(prepare).toHaveBeenCalledWith(7)
    expect(start).toHaveBeenCalledWith({ type: 'reveal', origin: { x: 5, y: 6 }, durationMs: 450 })
  })

  it('drops the capture on a same-theme switch', async () => {
    const { reveal, mount } = setup()
    mount()

    reveal.capture({ x: 5, y: 6 }, 7)
    await act(() => reveal.switchTo(light))
    jest.runAllTimers()

    expect(cancel).toHaveBeenCalledTimes(1)
    expect(dropCapture).toHaveBeenCalledTimes(1)
    expect(prepare).not.toHaveBeenCalled()

    await act(() => reveal.switchTo(dark))

    expect(start).toHaveBeenCalledWith({ type: 'crossfade', durationMs: 300 })
  })

  it('reports a capture rejection through onError and still applies the theme', async () => {
    const error = new Error('no window')
    capture.mockRejectedValue(error)
    const { reveal, onError, getTheme, mount } = setup()
    mount()

    reveal.capture({ x: 5, y: 6 }, 7)
    await act(() => reveal.switchTo(dark))

    expect(onError).toHaveBeenCalledTimes(1)
    expect(onError).toHaveBeenCalledWith(error)
    expect(getTheme()).toBe(dark)
  })

  it('forgets the capture point and frees the capture on dropCapture', async () => {
    const { reveal, mount } = setup()
    mount()

    reveal.capture({ x: 5, y: 6 }, 7)
    reveal.dropCapture()

    expect(dropCapture).toHaveBeenCalledTimes(1)

    jest.runAllTimers()
    await act(() => reveal.switchTo(dark))

    expect(dropCapture).toHaveBeenCalledTimes(1)
    expect(start).toHaveBeenCalledWith({ type: 'crossfade', durationMs: 300 })
  })

  it('ignores a late onPressOut from an earlier press', async () => {
    const { reveal, mount } = setup()
    mount()

    reveal.pressCapture.onPressIn(pressIn({ pageX: 1, pageY: 2, target: 3, timestamp: 100 }))
    await act(() => reveal.switchTo(dark))
    reveal.pressCapture.onPressIn(pressIn({ pageX: 5, pageY: 6, target: 7, timestamp: 200 }))
    reveal.pressCapture.onPressOut(pressIn({ pageX: 1, pageY: 2, target: 3, timestamp: 160 }))
    jest.advanceTimersByTime(0)

    expect(dropCapture).not.toHaveBeenCalled()

    await act(() => reveal.switchTo(light))

    expect(prepare).toHaveBeenLastCalledWith(7)
    expect(start).toHaveBeenLastCalledWith({ type: 'reveal', origin: { x: 5, y: 6 }, durationMs: 450 })
  })

  it('drops on an onPressOut from the same press', () => {
    const { reveal } = setup()

    reveal.pressCapture.onPressIn(pressIn({ pageX: 5, pageY: 6, target: 7, timestamp: 200 }))
    reveal.pressCapture.onPressOut(pressIn({ pageX: 5, pageY: 9, target: 7, timestamp: 450 }))
    jest.advanceTimersByTime(0)

    expect(dropCapture).toHaveBeenCalledTimes(1)
  })

  it('forgets the point of a press cancelled after the timeout', async () => {
    const { reveal, mount } = setup()
    mount()

    reveal.pressCapture.onPressIn(pressIn({ pageX: 5, pageY: 6, target: 7 }))
    jest.advanceTimersByTime(3000)

    expect(dropCapture).toHaveBeenCalledTimes(1)

    reveal.pressCapture.onPressOut()
    jest.advanceTimersByTime(0)
    await act(() => reveal.switchTo(dark))

    expect(dropCapture).toHaveBeenCalledTimes(1)
    expect(start).toHaveBeenCalledWith({ type: 'crossfade', durationMs: 300 })
  })

  it('does not free an expired capture again on a same-theme switch', async () => {
    const { reveal, mount } = setup()
    mount()

    reveal.capture({ x: 5, y: 6 }, 7)
    jest.advanceTimersByTime(3000)
    await act(() => reveal.switchTo(light))

    expect(dropCapture).toHaveBeenCalledTimes(1)
  })

  it('keeps a later setOrigin point when the capture drops', async () => {
    const { reveal, mount } = setup()
    mount()

    reveal.pressCapture.onPressIn(pressIn({ pageX: 5, pageY: 6, target: 7 }))
    reveal.pressCapture.onPressOut()
    reveal.setOrigin({ x: 8, y: 9 })
    jest.advanceTimersByTime(0)
    await act(() => reveal.switchTo(dark))

    expect(start).toHaveBeenCalledWith({ type: 'reveal', origin: { x: 8, y: 9 }, durationMs: 450 })
  })
})
