import { useSyncExternalStore } from 'react'

export type Store<S> = {
  get(): S
  set(partial: Partial<S>): void
  subscribe(listener: () => void): () => void
}

export function createStore<S>(initial: S): Store<S> {
  let state = initial
  const listeners = new Set<() => void>()
  return {
    get: () => state,
    set: (partial) => {
      state = { ...state, ...partial }
      listeners.forEach((listener) => listener())
    },
    subscribe: (listener) => {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
  }
}

export function useStore<S, V>(store: Store<S>, select: (state: S) => V): V {
  const getSnapshot = () => select(store.get())
  return useSyncExternalStore(store.subscribe, getSnapshot, getSnapshot)
}
