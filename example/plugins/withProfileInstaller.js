// Adds androidx.profileinstaller 1.4.0 to the app. The 1.3.x androidx pulls in can't install a Baseline Profile on
// Android 15 and later (API 35+), so a local install wouldn't get the profiles a Play install gets.
// benchmark/android.sh then installs the profile and compiles the app with it before each pass.
const { withAppBuildGradle } = require('expo/config-plugins')

const DEPENDENCY = 'implementation("androidx.profileinstaller:profileinstaller:1.4.0")'

module.exports = (config) =>
  withAppBuildGradle(config, (gradle) => {
    if (!gradle.modResults.contents.includes(DEPENDENCY)) {
      gradle.modResults.contents = gradle.modResults.contents.replace(
        /^dependencies\s*\{/m,
        (match) => `${match}\n    ${DEPENDENCY}`,
      )
    }
    return gradle
  })
