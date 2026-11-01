export type Session = { token: string; role: 'admin' | 'advertiser'; advertiserId: string; user: string; company?: string }

const KEY = 'adx.session'
let session: Session | null = null
try {
  const raw = sessionStorage.getItem(KEY)
  if (raw) session = JSON.parse(raw)
} catch { /* storage unavailable: stay in memory only */ }

export const getSession = () => session
export function setSession(s: Session | null) {
  session = s
  try {
    if (s) sessionStorage.setItem(KEY, JSON.stringify(s))
    else sessionStorage.removeItem(KEY)
  } catch { /* ignore */ }
}

export async function api<T = any>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(session ? { Authorization: `Bearer ${session.token}` } : {}),
      ...(init.headers || {}),
    },
  })
  if (res.status === 401 && session) {
    setSession(null)
    location.reload()
  }
  if (!res.ok) {
    let msg = res.statusText
    try { const j = await res.json(); msg = j.error || j.message || msg } catch { /* not json */ }
    throw new Error(msg)
  }
  if (res.status === 204) return undefined as T
  return res.json()
}

export const usd = (micros: number) => (micros / 1e6).toLocaleString(undefined, { style: 'currency', currency: 'USD', maximumFractionDigits: 2 })
export const pct = (x: number, d = 2) => `${(x * 100).toFixed(d)}%`
export const toMicros = (dollars: number) => Math.round(dollars * 1e6)
