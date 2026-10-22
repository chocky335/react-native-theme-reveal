import { act, renderHook } from '@testing-library/react-native'
import { StrictMode } from 'react'

import ThemeRevealModule from '../ThemeRevealModule'
import { createThemeReveal, type ThemeRevealOptions } from '../createThemeReveal'

jest.mock('../ThemeRevealModule', () => ({
  __esModule: true,
  default: { prepare: jest.fn(), start: jest.fn(), cancel: jest.fn() },
}))

type Theme = { name: string }

const light: Theme = { name: 'light' }
const dark: Theme = { name: 'dark' }
const dim: Theme = { name: 'dim' }

const prepare = jest.mocked(ThemeRevealModule.prepare)
const start = jest.mocked(ThemeRevealModule.start)
const cancel = jest.mocked(ThemeRevealModule.cancel)

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
  prepare.mockReset().mockResolvedValue(true)
  start.mockReset().mockResolvedValue(undefined)
  cancel.mockReset().mockResolvedValue(undefined)
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

  it('switches without animation when prepare returns false', async () => {
    prepare.mockResolvedValue(false)
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
    let resolveFirst: (shown: boolean) => void = () => {}
    prepare.mockReturnValueOnce(
      new Promise<boolean>((resolve) => {
        resolveFirst = resolve
      }),
    )
    const { reveal, setTheme, getTheme, mount } = setup()
    const view = mount()

    const first = reveal.switchTo(dark)
    reveal.setOrigin({ x: 1, y: 2 })
    await act(() => reveal.switchTo(dim))
    await act(async () => {
      resolveFirst(true)
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

describe('onSettled', () => {
  it('reports onSettled with prepareMs and totalMs', async () => {
    let now = 100
    jest.spyOn(performance, 'now').mockImplementation(() => now)
    prepare.mockImplementation(async () => {
      now = 130
      return true
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
    expect(onSettled).toHaveBeenCalledWith({ transition: 'crossfade', prepareMs: 30, totalMs: 500 })
  })

  it('reports onSettled right after setTheme when there was no snapshot', async () => {
    let now = 100
    jest.spyOn(performance, 'now').mockImplementation(() => now)
    prepare.mockImplementation(async () => {
      now = 120
      return false
    })
    const onSettled = jest.fn()
    const { reveal } = setup({ onSettled })

    await reveal.switchTo(dark)

    expect(onSettled).toHaveBeenCalledTimes(1)
    expect(onSettled).toHaveBeenCalledWith({ transition: null, prepareMs: 20, totalMs: 20 })
  })
})
