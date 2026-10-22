import type { Point, Shape, Transition, TransitionType, WipeDirection } from './types'

export type ResolveTransitionInput = {
  requested?: TransitionType
  fallback?: TransitionType
  origin: Point | null
  shape?: Shape
  durations?: Partial<Record<TransitionType, number>>
  wipeDirection?: WipeDirection
  requestedShape?: Shape
  requestedWipeDirection?: WipeDirection
}

const DEFAULT_DURATIONS: Record<TransitionType, number> = {
  reveal: 450,
  inverseReveal: 450,
  wipe: 400,
  crossfade: 300,
}

export function resolveTransition(input: ResolveTransitionInput): Transition {
  const { requested, fallback, origin, durations } = input
  const type = requested ?? fallback ?? (origin ? 'reveal' : 'crossfade')
  const shape = input.requestedShape ?? input.shape
  const wipeDirection = input.requestedWipeDirection ?? input.wipeDirection
  const durationOf = (kind: TransitionType) => durations?.[kind] ?? DEFAULT_DURATIONS[kind]
  switch (type) {
    case 'reveal':
    case 'inverseReveal':
      if (!origin) return { type: 'crossfade', durationMs: durationOf('crossfade') }
      return shape
        ? { type, origin, durationMs: durationOf(type), shape }
        : { type, origin, durationMs: durationOf(type) }
    case 'wipe':
      return { type, direction: wipeDirection ?? 'up', durationMs: durationOf(type) }
    case 'crossfade':
      return { type, durationMs: durationOf(type) }
  }
}
