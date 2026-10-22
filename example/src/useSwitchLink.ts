import { useEffect, useRef } from 'react'
import { Linking, Platform, Settings } from 'react-native'
import type { Point, TransitionType } from 'react-native-theme-reveal'

import type { ModalPresentation } from './ModalScreen'
import type { Variant } from './reveals'

export type SwitchLink = { variant: Variant; transition?: TransitionType; point?: Point }

// Opens the modal. Once it's shown, the app measures a switch button inside it and switches from there.
export type ModalLink = {
  presentation: ModalPresentation
  measure: 'page' | 'window'
  variant: Variant
  transition?: TransitionType
}

export type Link = ({ kind: 'switch' } & SwitchLink) | ({ kind: 'modal' } & ModalLink)

const VARIANTS: Variant[] = ['circle', 'star', 'wipeUp', 'wipeDown', 'wipeLeft', 'wipeRight']
const TRANSITIONS: TransitionType[] = ['reveal', 'inverseReveal', 'wipe', 'crossfade']
const PRESENTATIONS: ModalPresentation[] = ['fullScreen', 'pageSheet', 'formSheet', 'transparent']
const LAUNCH_LINK_GAP_MS = 1500

// themereveal-example://switch?variant=star&transition=reveal&x=40&y=520 (x, y in window points).
// themereveal-example://modal?presentation=pageSheet&measure=page|window&variant=circle&transition=reveal
export function parseLink(url: string): Link | null {
  const [base, query = ''] = url.split('?')
  const params = new Map(
    query
      .split('&')
      .filter(Boolean)
      .map((pair) => pair.split('=').map(decodeURIComponent) as [string, string]),
  )
  const variant = VARIANTS.find((value) => value === params.get('variant')) ?? 'circle'
  const transition = TRANSITIONS.find((value) => value === params.get('transition'))
  if (base.endsWith('://modal')) {
    const presentation = PRESENTATIONS.find((value) => value === params.get('presentation')) ?? 'pageSheet'
    const measure = params.get('measure') === 'window' ? 'window' : 'page'
    return { kind: 'modal', presentation, measure, variant, transition }
  }
  if (!base.endsWith('://switch')) return null
  const x = Number(params.get('x'))
  const y = Number(params.get('y'))
  const point = params.has('x') && params.has('y') && isFinite(x) && isFinite(y) ? { x, y } : undefined
  return { kind: 'switch', variant, transition, point }
}

// Runs each link, so a script can drive the example without taps.
export function useSwitchLink(onLink: (link: Link) => void) {
  const onLinkRef = useRef(onLink)
  useEffect(() => {
    onLinkRef.current = onLink
  })

  useEffect(() => {
    const handle = (url: string | null) => {
      const link = url ? parseLink(url) : null
      if (link) onLinkRef.current(link)
    }
    void Linking.getInitialURL().then(handle)
    const subscription = Linking.addEventListener('url', ({ url }) => handle(url))

    // iOS: `simctl launch <device> <app> -switchLinks '<link> <link>'` runs the links 1.5 s apart, with no
    // "Open in" prompt.
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
}
