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
})
