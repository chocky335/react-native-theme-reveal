// jest-expo loads this instead of the native module. prepare and capture resolve false, so switchTo applies the
// theme immediately without animating.
module.exports = {
  prepare: async () => false,
  start: async () => {},
  cancel: async () => {},
  warmUp: async () => {},
  capture: async () => false,
  dropCapture: async () => {},
}
