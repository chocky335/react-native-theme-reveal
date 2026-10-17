const { projects, ...preset } = require('expo-module-scripts/jest-preset')

const { platforms } = require('./expo-module.config.json')

module.exports = {
  ...preset,
  projects: projects.filter(({ haste }) => platforms.includes(haste.defaultPlatform)),
}
