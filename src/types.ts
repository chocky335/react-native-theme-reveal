export type Point = { x: number; y: number }

export type Shape = { path: string; anchor: Point; inscribedRadius: number }

export type WipeDirection = 'up' | 'down' | 'left' | 'right'

export type Transition =
  | { type: 'reveal'; origin: Point; durationMs: number; shape?: Shape }
  | { type: 'inverseReveal'; origin: Point; durationMs: number; shape?: Shape }
  | { type: 'wipe'; direction: WipeDirection; durationMs: number }
  | { type: 'crossfade'; durationMs: number }

export type TransitionType = Transition['type']
