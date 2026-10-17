// Learn more https://docs.expo.dev/guides/customizing-metro
const { getDefaultConfig } = require('expo/metro-config')
const path = require('path')

const root = path.resolve(__dirname, '..')
const escape = (value) => value.replace(/[/\-\\^$*+?.()|[\]{}]/g, '\\$&')

const config = getDefaultConfig(__dirname)

// Read the module source from the repo root.
config.watchFolders = [root]

// Only one copy of react and react-native, the example's. The root copies are only there for the root jest
// run.
config.resolver.blockList = [
  ...[].concat(config.resolver.blockList ?? []),
  new RegExp(`^${escape(path.join(root, 'node_modules', 'react'))}/.*$`),
  new RegExp(`^${escape(path.join(root, 'node_modules', 'react-native'))}/.*$`),
]
config.resolver.nodeModulesPaths = [path.join(__dirname, 'node_modules')]

const defaultResolveRequest = config.resolver.resolveRequest
const exampleEntry = path.join(__dirname, 'index.ts')

config.resolver.resolveRequest = (context, moduleName, platform) => {
  const resolve = defaultResolveRequest ?? context.resolveRequest
  if (moduleName === 'react-native-theme-reveal') {
    return { type: 'sourceFile', filePath: path.join(root, 'src', 'index.ts') }
  }
  // Resolve the module source's imports (react, expo, ...) from the example, so they share its copies.
  const fromModuleSource = context.originModulePath.startsWith(path.join(root, 'src') + path.sep)
  if (fromModuleSource && !moduleName.startsWith('.')) {
    return resolve({ ...context, originModulePath: exampleEntry }, moduleName, platform)
  }
  return resolve(context, moduleName, platform)
}

module.exports = config
