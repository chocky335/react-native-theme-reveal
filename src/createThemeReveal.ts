import { useLayoutEffect } from 'react'

import ThemeRevealModule from './ThemeRevealModule'
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
}

export type SwitchOptions = { transition?: TransitionType }

export type ThemeReveal<T> = {
  setOrigin(point: Point, viewTag?: number): void
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

type RevealState<T> = {
  pending: PendingStart | null
  held: { id: number; theme: T } | null
}

export function createThemeReveal<T>(options: ThemeRevealOptions<T>): ThemeReveal<T> {
  const { getTheme, setTheme, onSettled } = options
  const onError = options.onError ?? ((error: unknown) => console.warn(error))
  const store = createStore<RevealState<T>>({ pending: null, held: null })
  let pendingOrigin: { point: Point; viewTag?: number } | null = null
  let latestId = 0

  const setOrigin = (point: Point, viewTag?: number) => {
    pendingOrigin = { point, viewTag }
  }

  const switchTo = async (theme: T, switchOptions?: SwitchOptions) => {
    const origin = pendingOrigin
    pendingOrigin = null
    const id = ++latestId
    const startedAt = performance.now()

    if (Object.is(getTheme(), theme)) {
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
      onSettled?.({ transition: null, prepareMs, totalMs: performance.now() - startedAt })
      return
    }

    const transition = resolveTransition({
      requested: switchOptions?.transition,
      fallback: options.transition,
      origin: origin?.point ?? null,
      shape: options.shape,
      durations: options.durations,
      wipeDirection: options.wipeDirection,
    })
    store.set({ held: { id, theme: getTheme() } })
    setTheme(theme)
    store.set({ pending: { id, transition, startedAt, prepareMs } })
  }

  const useStart = () => {
    const pending = useStore(store, (state) => state.pending)

    useLayoutEffect(() => {
      if (!pending || store.get().pending !== pending) return
      store.set({ pending: null })
      const { id, transition, startedAt, prepareMs } = pending
      ThemeRevealModule.start(transition)
        .catch(onError)
        .finally(() => {
          if (store.get().held?.id === id) store.set({ held: null })
          onSettled?.({ transition: transition.type, prepareMs, totalMs: performance.now() - startedAt })
        })
    }, [pending])
  }

  const useHeldTheme = () => useStore(store, (state) => state.held?.theme ?? null)

  return { setOrigin, switchTo, useStart, useHeldTheme }
}
