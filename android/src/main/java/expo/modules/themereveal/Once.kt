package expo.modules.themereveal

// Runs action on the first call to run and never again, even if it throws. For an action several triggers race for.
// Main thread only.
internal class Once(private val action: () -> Unit) {
  private var done = false

  fun run() {
    if (done) return
    done = true
    action()
  }
}
