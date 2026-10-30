// No jest.mock here: the jest-expo setup finds mocks/ThemeReveal.js the way it does in a consumer app.
import ThemeRevealModule from '../ThemeRevealModule'
import { createThemeReveal } from '../createThemeReveal'

describe('jest-expo native module mock', () => {
  it('resolves prepare to false and start, cancel and warmUp to undefined', async () => {
    await expect(ThemeRevealModule.prepare(1)).resolves.toBe(false)
    await expect(ThemeRevealModule.warmUp(true)).resolves.toBeUndefined()
    await expect(ThemeRevealModule.start({ type: 'crossfade', durationMs: 300 })).resolves.toBeUndefined()
    await expect(ThemeRevealModule.cancel()).resolves.toBeUndefined()
  })

  it('lets switchTo apply the theme at once', async () => {
    let theme = 'light'
    const onSettled = jest.fn()
    const reveal = createThemeReveal<string>({
      getTheme: () => theme,
      setTheme: (next) => {
        theme = next
      },
      onSettled,
    })

    await reveal.switchTo('dark')

    expect(theme).toBe('dark')
    expect(onSettled).toHaveBeenCalledWith(expect.objectContaining({ transition: null }))
  })
})
