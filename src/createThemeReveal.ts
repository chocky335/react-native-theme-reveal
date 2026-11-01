import { useEffect, useLayoutEffect } from 'react'
import type { GestureResponderEvent } from 'react-native'

import ThemeRevealModule from './ThemeRevealModule'
import { pagePointOf } from './originFromPress'
import { resolveTransition } from './resolveTransition'
import { createStore, useStore } from './store'
import type { Point, Shape, Transition, TransitionType, WipeDirection } from './types'

export type SettledInfo = {
  transition: TransitionType | null
  prepareMs: number
  totalMs: number
}

export type ThemeRevealOptions<T> = {
  getTheme: () => T
  setTheme: (theme: T) => void
  shape?: Shape
  durations?: Partial<Record<TransitionType, number>>
  transition?: TransitionType
  wipeDirection?: WipeDirection
  onError?: (error: unknown) => void
  onSettled?: (info: SettledInfo) => void
  // Prepare the native snapshot path when useStart mounts (Android: copy thread, one kept window-size bitmap).
  // Default true.
  warmUp?: boolean
}

export type SwitchOptions = {
  transition?: TransitionType
  shape?: Shape
  wipeDirection?: WipeDirection
}

// Spread on a Pressable whose onPress calls switchTo. The snapshot is taken on press-in, and a press that ends
// without a switch frees it.
export type PressCapture = {
  onPressIn(event: GestureResponderEvent): void
  onPressOut(): void
}

export type ThemeReveal<T> = {
  setOrigin(point: Point, viewTag?: number): void
  capture(point: Point, viewTag?: number): void
  dropCapture(): void
  pressCapture: PressCapture
  switchTo(theme: T, options?: SwitchOptions): Promise<void>
  useStart(): void
  useHeldTheme(): T | null
}

type PendingStart = {
  id: number
  transition: Transition
  startedAt: number
  prepareMs: number
}

type Origin = { point: Point; viewTag?: number }

type Captured = {
  origin: Origin
  expiry: ReturnType<typeof setTimeout>
  drop?: ReturnType<typeof setTimeout>
}

// A capture no switch uses is freed after this long. Native also frees it on its own after the same time.
const CAPTURE_TIMEOUT_MS = 3000

type RevealState<T> = {
  pending: PendingStart | null
  held: { id: number; theme: T } | null
}

export function createThemeReveal<T>(options: ThemeRevealOptions<T>): ThemeReveal<T> {
  const { getTheme, setTheme } = options
  const warn = (error: unknown) => console.warn(error)
  // A throwing user callback must not skip setTheme or leave an unhandled rejection.
  const call = <A>(callback: ((arg: A) => void) | undefined, arg: A) => {
    try {
      callback?.(arg)
    } catch (thrown) {
      warn(thrown)
    }
  }
  const onError = (error: unknown) => call(options.onError ?? warn, error)
  const onSettled = (info: SettledInfo) => call(options.onSettled, info)
  const store = createStore<RevealState<T>>({ pending: null, held: null })
  let pendingOrigin: Origin | null = null
  let captured: Captured | null = null
  let latestId = 0
  let warmedUp = false

  const setOrigin = (point: Point, viewTag?: number) => {
    pendingOrigin = { point, viewTag }
  }

  // Forgets the JS side of the capture and returns it. Native keeps it until prepare, cancel or dropCapture.
  const releaseCapture = () => {
    const current = captured
    captured = null
    if (current) {
      clearTimeout(current.expiry)
      clearTimeout(current.drop)
    }
    return current
  }

  const freeCapture = () => ThemeRevealModule.dropCapture().catch(onError)

  const capture = (point: Point, viewTag?: number) => {
    releaseCapture()
    const origin = { point, viewTag }
    pendingOrigin = origin
    // A long press keeps its point for the switch on release, but not the snapshot.
    const current: Captured = {
      origin,
      expiry: setTimeout(() => {
        if (captured !== current) return
        releaseCapture()
        freeCapture()
      }, CAPTURE_TIMEOUT_MS),
    }
    captured = current
    ThemeRevealModule.capture(viewTag).catch(onError)
  }

  const dropCapture = () => {
    const current = releaseCapture()
    if (current && pendingOrigin === current.origin) pendingOrigin = null
    freeCapture()
  }

  // On release React Native calls onPressOut before onPress in the same dispatch (or up to 130 ms after onPress
  // for a short press), and a cancelled press gets onPressOut without onPress. So onPressOut only drops the
  // capture in a later macrotask, and only if no switchTo has taken it by then.
  const pressCapture: PressCapture = {
    onPressIn: (event) => {
      const origin = pagePointOf(event)
      if (origin) capture(origin.point, origin.viewTag)
    },
    onPressOut: () => {
      const current = captured
      if (!current) return
      clearTimeout(current.drop)
      current.drop = setTimeout(() => {
        if (captured === current) dropCapture()
      }, 0)
    },
  }

  const switchTo = async (theme: T, switchOptions?: SwitchOptions) => {
    const held = releaseCapture()
    const origin = pendingOrigin
    pendingOrigin = null
    const id = ++latestId
    const startedAt = performance.now()

    if (Object.is(getTheme(), theme)) {
      if (held) freeCapture()
      try {
        await ThemeRevealModule.cancel()
      } catch (error) {
        onError(error)
      }
      return
    }

    let shown = false
    try {
      shown = await ThemeRevealModule.prepare(origin?.viewTag)
    } catch (error) {
      onError(error)
    }
    if (id !== latestId) return
    const prepareMs = performance.now() - startedAt

    if (!shown) {
      setTheme(theme)
      onSettled({ transition: null, prepareMs, totalMs: performance.now() - startedAt })
      return
    }

    const transition = resolveTransition({
      requested: switchOptions?.transition,
      fallback: options.transition,
      origin: origin?.point ?? null,
      shape: options.shape,
      durations: options.durations,
      wipeDirection: options.wipeDirection,
      requestedShape: switchOptions?.shape,
      requestedWipeDirection: switchOptions?.wipeDirection,
    })
    store.set({ held: { id, theme: getTheme() } })
    try {
      setTheme(theme)
    } catch (error) {
      if (store.get().held?.id === id) store.set({ held: null })
      ThemeRevealModule.cancel().catch(onError)
      throw error
    }
    store.set({ pending: { id, transition, startedAt, prepareMs } })
  }

  // Once per instance: start the native capture path early so the first switch doesn't pay for it.
  const warmUp = () => {
    if (warmedUp || options.warmUp === false) return
    warmedUp = true
    try {
      ThemeRevealModule.warmUp().catch(onError)
    } catch (error) {
      onError(error)
    }
  }

  const useStart = () => {
    const pending = useStore(store, (state) => state.pending)

    useEffect(warmUp, [])

    useLayoutEffect(() => {
      if (!pending || store.get().pending !== pending) return
      store.set({ pending: null })
      const { id, transition, startedAt, prepareMs } = pending
      ThemeRevealModule.start(transition)
        .catch(onError)
        .finally(() => {
          if (store.get().held?.id === id) store.set({ held: null })
          onSettled({ transition: transition.type, prepareMs, totalMs: performance.now() - startedAt })
        })
    }, [pending])
  }

  const useHeldTheme = () => useStore(store, (state) => state.held?.theme ?? null)

  return { setOrigin, capture, dropCapture, pressCapture, switchTo, useStart, useHeldTheme }
}
