export type Theme = 'light' | 'dark'

export type Colors = {
  background: string
  card: string
  text: string
  muted: string
  button: string
  accent: string
  backdrop: string
}

export const palette: Record<Theme, Colors> = {
  light: {
    background: '#ffffff',
    card: '#f4f4f5',
    text: '#111111',
    muted: '#52525b',
    button: '#e4e4e7',
    accent: '#2563eb',
    backdrop: 'rgba(0, 0, 0, 0.45)',
  },
  dark: {
    background: '#111111',
    card: '#1c1c1f',
    text: '#f4f4f5',
    muted: '#a1a1aa',
    button: '#3f3f46',
    accent: '#60a5fa',
    backdrop: 'rgba(0, 0, 0, 0.6)',
  },
}

export const opposite = (theme: Theme): Theme => (theme === 'light' ? 'dark' : 'light')
