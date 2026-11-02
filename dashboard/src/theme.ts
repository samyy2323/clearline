import { useEffect, useState } from 'react'

export type Theme = 'light' | 'dark'
const KEY = 'adx.theme'

export function initialTheme(): Theme {
  try {
    const saved = localStorage.getItem(KEY)
    if (saved === 'light' || saved === 'dark') return saved
  } catch { /* storage blocked: fall through */ }
  return 'light'
}

export function applyTheme(t: Theme) {
  document.documentElement.dataset.theme = t
  try { localStorage.setItem(KEY, t) } catch { /* ignore */ }
  window.dispatchEvent(new Event('adx-theme'))
}

export function currentTheme(): Theme {
  return (document.documentElement.dataset.theme as Theme) || 'light'
}

/** Re-renders the caller when the theme changes (charts need to recompute their colours). */
export function useTheme(): [Theme, () => void] {
  const [theme, setTheme] = useState<Theme>(currentTheme())
  useEffect(() => {
    const on = () => setTheme(currentTheme())
    window.addEventListener('adx-theme', on)
    return () => window.removeEventListener('adx-theme', on)
  }, [])
  return [theme, () => applyTheme(theme === 'light' ? 'dark' : 'light')]
}

/** CSS colour variable as a comma-separated rgb() string, which ECharts parses reliably. */
export function cssRgb(name: string, alpha = 1): string {
  const raw = getComputedStyle(document.documentElement).getPropertyValue(name).trim().split(/\s+/).join(',')
  return alpha === 1 ? `rgb(${raw})` : `rgba(${raw},${alpha})`
}
