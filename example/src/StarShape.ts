import type { Shape } from 'react-native-theme-reveal'

// Original five-point star in a 100 x 100 box: centre (50, 50), outer radius 50, inner radius 22, first point up.
// Vertices alternate between the two radii every 36 degrees, rounded to 0.01.
//
// inscribedRadius: for all ten edges, the foot of the perpendicular from the centre falls outside the edge, so the
// nearest boundary points are the five inner vertices. After rounding they sit 21.997-22.001 from the centre, and
// 21.99 keeps the stated radius at or below the true one.
export const STAR: Shape = {
  path:
    'M50 0 L62.93 32.2 L97.55 34.55 L70.92 56.8 L79.39 90.45 L50 72 ' +
    'L20.61 90.45 L29.08 56.8 L2.45 34.55 L37.07 32.2 Z',
  anchor: { x: 50, y: 50 },
  inscribedRadius: 21.99,
}
