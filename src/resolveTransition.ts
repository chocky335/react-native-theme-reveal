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

const MAX_DURATION_MS = 10_000

const isValidDuration = (ms: number | undefined): ms is number =>
  ms !== undefined && Number.isFinite(ms) && ms > 0 && ms <= MAX_DURATION_MS

const isNumber = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value)

const isPoint = (value: unknown): value is Point =>
  typeof value === 'object' && value !== null && isNumber((value as Point).x) && isNumber((value as Point).y)

const isShape = (value: unknown): value is Shape =>
  typeof value === 'object' &&
  value !== null &&
  typeof (value as Shape).path === 'string' &&
  isPoint((value as Shape).anchor) &&
  isNumber((value as Shape).inscribedRadius)

export function resolveTransition(input: ResolveTransitionInput): Transition {
  const { requested, fallback, durations } = input
  // Untyped callers can pass null, undefined or other non-numbers inside origin or shape. Android rejects start for
  // those before its parser runs, so here they fall back to no origin and the circle.
  const origin = isPoint(input.origin) ? input.origin : null
  const type = requested ?? fallback ?? (origin ? 'reveal' : 'crossfade')
  const requestedShape = input.requestedShape ?? input.shape
  const shape = isShape(requestedShape) ? requestedShape : undefined
  const wipeDirection = input.requestedWipeDirection ?? input.wipeDirection
  const durationOf = (kind: TransitionType) => {
    const ms = durations?.[kind]
    return isValidDuration(ms) ? ms : DEFAULT_DURATIONS[kind]
  }
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
