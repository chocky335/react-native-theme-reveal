// jest-expo loads this instead of the native module. prepare resolves null and capture false, so switchTo applies
// the theme immediately without animating.
module.exports = {
  prepare: async () => null,
  start: async () => {},
  cancel: async () => {},
  warmUp: async () => {},
  capture: async () => false,
  dropCapture: async () => {},
}
