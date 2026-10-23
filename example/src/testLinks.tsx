// Device-check hooks, not part of the demo. Scripts drive the example through these links, no taps needed:
//   themereveal-example://switch?transition=reveal&shape=star&direction=left&x=40&y=150   (x, y: window points)
//   themereveal-example://modal?presentation=pageSheet&measure=page|window&transition=reveal
//   themereveal-example://keyboard?transition=reveal&x=40&y=150
// iOS: `simctl launch <device> <app> -switchLinks '<link> <link>'` runs the links 1.5 s apart, with no "Open in"
// prompt. `modal` opens the modal, measures its first switch button and switches from 10% of its width with the
// button's view tag. `keyboard` focuses the text field and switches once the keyboard is up.
import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'
import type { ReactNode, RefObject } from 'react'
import { findNodeHandle, Keyboard, Linking, Platform, Settings } from 'react-native'
import type { TextInput, View } from 'react-native'
import type { Point, SwitchOptions, ThemeReveal, TransitionType, WipeDirection } from 'react-native-theme-reveal'

import type { ModalPresentation } from './ModalScreen'
import { STAR } from './StarShape'
import { opposite } from './theme'
import type { Theme } from './theme'

type Link =
  | { kind: 'switch' | 'keyboard'; options: SwitchOptions; point?: Point }
  | { kind: 'modal'; options: SwitchOptions; presentation: ModalPresentation; measure: 'page' | 'window' }

const TRANSITIONS: TransitionType[] = ['reveal', 'inverseReveal', 'wipe', 'crossfade']
const DIRECTIONS: WipeDirection[] = ['up', 'down', 'left', 'right']
const PRESENTATIONS: ModalPresentation[] = ['fullScreen', 'pageSheet', 'formSheet', 'transparent']
const LAUNCH_LINK_GAP_MS = 1500
const MODAL_SETTLE_MS = 500
const KEYBOARD_SETTLE_MS = 400

function parseLink(url: string): Link | null {
  const [base, query = ''] = url.split('?')
  const params = new Map(
    query
      .split('&')
      .filter(Boolean)
      .map((pair) => pair.split('=').map(decodeURIComponent) as [string, string]),
  )
  const options: SwitchOptions = {
    transition: TRANSITIONS.find((value) => value === params.get('transition')),
    shape: params.get('shape') === 'star' ? STAR : undefined,
    wipeDirection: DIRECTIONS.find((value) => value === params.get('direction')),
  }
  if (base.endsWith('://modal')) {
    const presentation = PRESENTATIONS.find((value) => value === params.get('presentation')) ?? 'pageSheet'
    const measure = params.get('measure') === 'window' ? 'window' : 'page'
    return { kind: 'modal', options, presentation, measure }
  }
  const x = Number(params.get('x'))
  const y = Number(params.get('y'))
  const point = params.has('x') && params.has('y') && isFinite(x) && isFinite(y) ? { x, y } : undefined
  if (base.endsWith('://keyboard')) return { kind: 'keyboard', options, point }
  if (base.endsWith('://switch')) return { kind: 'switch', options, point }
  return null
}

type TestLinks = {
  pending: Link | null
  run: (options: SwitchOptions, point?: Point, viewTag?: number) => void
  clear: () => void
}

const TestLinksContext = createContext<TestLinks>({ pending: null, run: () => {}, clear: () => {} })

export function TestLinksProvider({
  reveal,
  getTheme,
  children,
}: {
  reveal: ThemeReveal<Theme>
  getTheme: () => Theme
  children: ReactNode
}) {
  const [pending, setPending] = useState<Link | null>(null)
  const clear = useCallback(() => setPending(null), [])
  const run = (options: SwitchOptions, point?: Point, viewTag?: number) => {
    if (point) reveal.setOrigin(point, viewTag)
    void reveal.switchTo(opposite(getTheme()), options)
  }
  const runRef = useRef(run)
  useEffect(() => {
    runRef.current = run
  })

  useEffect(() => {
    const handle = (url: string | null) => {
      const link = url ? parseLink(url) : null
      if (link?.kind === 'switch') runRef.current(link.options, link.point)
      else if (link) setPending(link)
    }
    void Linking.getInitialURL().then(handle)
    const subscription = Linking.addEventListener('url', ({ url }) => handle(url))
    const launchLinks = Platform.OS === 'ios' ? Settings.get('switchLinks') : null
    const timers =
      typeof launchLinks === 'string'
        ? launchLinks
            .split(' ')
            .filter(Boolean)
            .map((url, index) => setTimeout(() => handle(url), (index + 1) * LAUNCH_LINK_GAP_MS))
        : []
    return () => {
      subscription.remove()
      timers.forEach(clearTimeout)
    }
  }, [])

  return (
    <TestLinksContext.Provider value={{ pending, run, clear }}>
      {children}
    </TestLinksContext.Provider>
  )
}

// Opens the modal for a pending modal link.
export function useModalLink(open: (presentation: ModalPresentation) => void) {
  const { pending } = useContext(TestLinksContext)
  const openRef = useRef(open)
  useEffect(() => {
    openRef.current = open
  })
  useEffect(() => {
    if (pending?.kind === 'modal') openRef.current(pending.presentation)
  }, [pending])
}

// The ref goes on the modal's first switch button; onShow measures it and switches for a pending modal link.
export function useModalLinkProbe(): { probeRef: RefObject<View | null>; onShow: () => void } {
  const { pending, run, clear } = useContext(TestLinksContext)
  const probeRef = useRef<View>(null)
  const onShow = () => {
    if (pending?.kind !== 'modal') return
    const link = pending
    clear()
    setTimeout(() => {
      const view = probeRef.current
      if (!view) return
      const viewTag = findNodeHandle(view) ?? undefined
      const switchAt = (x: number, y: number, width: number, height: number) =>
        run(link.options, { x: x + 0.1 * width, y: y + height / 2 }, viewTag)
      if (link.measure === 'window') view.measureInWindow(switchAt)
      else view.measure((_x, _y, width, height, pageX, pageY) => switchAt(pageX, pageY, width, height))
    }, MODAL_SETTLE_MS)
  }
  return { probeRef, onShow }
}

// Focuses the text field for a pending keyboard link and switches once the keyboard is up.
export function useKeyboardLink(inputRef: RefObject<TextInput | null>) {
  const { pending, run, clear } = useContext(TestLinksContext)
  const runRef = useRef(run)
  useEffect(() => {
    runRef.current = run
  })
  useEffect(() => {
    if (pending?.kind !== 'keyboard') return
    const link = pending
    let timer: ReturnType<typeof setTimeout> | undefined
    const subscription = Keyboard.addListener('keyboardDidShow', () => {
      subscription.remove()
      timer = setTimeout(() => {
        runRef.current(link.options, link.point)
        clear()
      }, KEYBOARD_SETTLE_MS)
    })
    inputRef.current?.focus()
    return () => {
      subscription.remove()
      clearTimeout(timer)
    }
  }, [pending, inputRef, clear])
}
