import { resolveTransition } from '../resolveTransition'
import type { Shape } from '../types'

const origin = { x: 10, y: 20 }
const shape: Shape = { path: 'M0 0L1 1Z', anchor: { x: 0.5, y: 0.5 }, inscribedRadius: 0.4 }

describe('resolveTransition', () => {
  it('lets the per-call transition beat the factory default', () => {
    expect(resolveTransition({ requested: 'crossfade', fallback: 'wipe', origin })).toEqual({
      type: 'crossfade',
      durationMs: 300,
    })
  })

  it('lets the factory default beat the origin rule', () => {
    expect(resolveTransition({ fallback: 'wipe', origin })).toEqual({
      type: 'wipe',
      direction: 'up',
      durationMs: 400,
    })
  })

  it('picks reveal with an origin and crossfade without one', () => {
    expect(resolveTransition({ origin })).toEqual({ type: 'reveal', origin, durationMs: 450 })
    expect(resolveTransition({ origin: null })).toEqual({ type: 'crossfade', durationMs: 300 })
  })

  it.each(['reveal', 'inverseReveal'] as const)(
    'falls back to crossfade for %s without an origin',
    (requested) => {
      expect(resolveTransition({ requested, origin: null, shape })).toEqual({
        type: 'crossfade',
        durationMs: 300,
      })
      expect(resolveTransition({ fallback: requested, origin: null })).toEqual({
        type: 'crossfade',
        durationMs: 300,
      })
    },
  )

  it('uses the default durations 450/450/400/300', () => {
    expect(resolveTransition({ requested: 'reveal', origin }).durationMs).toBe(450)
    expect(resolveTransition({ requested: 'inverseReveal', origin }).durationMs).toBe(450)
    expect(resolveTransition({ requested: 'wipe', origin }).durationMs).toBe(400)
    expect(resolveTransition({ requested: 'crossfade', origin }).durationMs).toBe(300)
  })

  it('accepts duration overrides and keeps the defaults for the rest', () => {
    const durations = { reveal: 600, wipe: 250 }
    expect(resolveTransition({ requested: 'reveal', origin, durations }).durationMs).toBe(600)
    expect(resolveTransition({ requested: 'wipe', origin, durations }).durationMs).toBe(250)
    expect(resolveTransition({ requested: 'inverseReveal', origin, durations }).durationMs).toBe(450)
    expect(resolveTransition({ requested: 'crossfade', origin: null, durations }).durationMs).toBe(300)
  })

  it('uses the wipe direction and defaults it to up', () => {
    expect(resolveTransition({ requested: 'wipe', origin: null })).toEqual({
      type: 'wipe',
      direction: 'up',
      durationMs: 400,
    })
    expect(resolveTransition({ requested: 'wipe', origin: null, wipeDirection: 'left' })).toEqual({
      type: 'wipe',
      direction: 'left',
      durationMs: 400,
    })
  })

  it('adds the shape only to reveal and inverseReveal', () => {
    expect(resolveTransition({ requested: 'reveal', origin, shape })).toEqual({
      type: 'reveal',
      origin,
      durationMs: 450,
      shape,
    })
    expect(resolveTransition({ requested: 'inverseReveal', origin, shape })).toEqual({
      type: 'inverseReveal',
      origin,
      durationMs: 450,
      shape,
    })
    expect(resolveTransition({ requested: 'wipe', origin, shape })).toEqual({
      type: 'wipe',
      direction: 'up',
      durationMs: 400,
    })
    expect(resolveTransition({ requested: 'crossfade', origin, shape })).toEqual({
      type: 'crossfade',
      durationMs: 300,
    })
  })

  it('lets the per-call shape and wipe direction beat the factory ones', () => {
    const star: Shape = { path: 'M0 0L2 0L1 2Z', anchor: { x: 1, y: 1 }, inscribedRadius: 0.5 }
    expect(resolveTransition({ requested: 'reveal', origin, shape, requestedShape: star })).toEqual({
      type: 'reveal',
      origin,
      durationMs: 450,
      shape: star,
    })
    expect(resolveTransition({ requested: 'inverseReveal', origin, requestedShape: star })).toEqual({
      type: 'inverseReveal',
      origin,
      durationMs: 450,
      shape: star,
    })
    expect(
      resolveTransition({
        requested: 'wipe',
        origin: null,
        wipeDirection: 'left',
        requestedWipeDirection: 'down',
      }),
    ).toEqual({ type: 'wipe', direction: 'down', durationMs: 400 })
  })

  it.each([NaN, Infinity, -Infinity, 0, -1, 10_001, 450_000])(
    'uses the default duration for an invalid duration (%p)',
    (invalid) => {
      const durations = { reveal: invalid, inverseReveal: invalid, wipe: invalid, crossfade: invalid }
      expect(resolveTransition({ requested: 'reveal', origin, durations })).toEqual({
        type: 'reveal',
        origin,
        durationMs: 450,
      })
      expect(resolveTransition({ requested: 'wipe', origin, durations })).toEqual({
        type: 'wipe',
        direction: 'up',
        durationMs: 400,
      })
      expect(resolveTransition({ requested: 'reveal', origin: null, durations })).toEqual({
        type: 'crossfade',
        durationMs: 300,
      })
    },
  )

  it('keeps a duration up to 10 000 ms', () => {
    expect(resolveTransition({ requested: 'crossfade', origin, durations: { crossfade: 10_000 } })).toEqual({
      type: 'crossfade',
      durationMs: 10_000,
    })
    expect(resolveTransition({ requested: 'crossfade', origin, durations: { crossfade: 0.5 } })).toEqual({
      type: 'crossfade',
      durationMs: 0.5,
    })
  })

  // Untyped callers: a null, undefined or non-number inside origin or shape would make native reject start, so it
  // never leaves JS.
  it.each([
    ['undefined y', { x: 10, y: undefined }],
    ['null x', { x: null, y: 20 }],
    ['a string', { x: '10', y: 20 }],
    ['NaN', { x: Number.NaN, y: 20 }],
    ['Infinity', { x: 10, y: Number.POSITIVE_INFINITY }],
    ['not an object', 5],
  ])('treats an origin with %s as no origin', (_, bad) => {
    const input = { origin: bad as unknown as { x: number; y: number } }
    expect(resolveTransition(input)).toEqual({ type: 'crossfade', durationMs: 300 })
    expect(resolveTransition({ ...input, requested: 'reveal', shape })).toEqual({
      type: 'crossfade',
      durationMs: 300,
    })
  })

  it.each([
    ['undefined anchor', { ...shape, anchor: undefined }],
    ['null anchor y', { ...shape, anchor: { x: 0.5, y: null } }],
    ['undefined inscribedRadius', { ...shape, inscribedRadius: undefined }],
    ['NaN inscribedRadius', { ...shape, inscribedRadius: Number.NaN }],
    ['a non-string path', { ...shape, path: 7 }],
    ['no fields', {}],
  ])('uses the circle for a shape with %s', (_, bad) => {
    const badShape = bad as unknown as Shape
    expect(resolveTransition({ requested: 'reveal', origin, shape: badShape })).toEqual({
      type: 'reveal',
      origin,
      durationMs: 450,
    })
    expect(resolveTransition({ requested: 'inverseReveal', origin, shape, requestedShape: badShape })).toEqual({
      type: 'inverseReveal',
      origin,
      durationMs: 450,
    })
  })
})
