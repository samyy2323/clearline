import { createContext, useContext, useEffect, useRef, useState, ReactNode } from 'react'
import { getSession } from './api'

export type Snapshot = any

type Live = { snap: Snapshot | null; history: Snapshot[]; connected: boolean }
const Ctx = createContext<Live>({ snap: null, history: [], connected: false })
export const useLive = () => useContext(Ctx)

/**
 * SSE over fetch() so the JWT travels in an Authorization header (EventSource cannot set headers and a token in
 * the URL would leak into logs). Reconnects with backoff.
 */
export function LiveProvider({ children }: { children: ReactNode }) {
  const [snap, setSnap] = useState<Snapshot | null>(null)
  const [history, setHistory] = useState<Snapshot[]>([])
  const [connected, setConnected] = useState(false)
  const alive = useRef(true)

  useEffect(() => {
    alive.current = true
    let ctrl: AbortController | null = null
    let delay = 500

    async function run() {
      while (alive.current) {
        ctrl = new AbortController()
        try {
          const s = getSession()
          const res = await fetch('/api/stream', {
            headers: { Authorization: `Bearer ${s?.token}`, Accept: 'text/event-stream' },
            signal: ctrl.signal,
          })
          if (!res.ok || !res.body) throw new Error(String(res.status))
          setConnected(true)
          delay = 500
          const reader = res.body.getReader()
          const dec = new TextDecoder()
          let buf = ''
          for (;;) {
            const { done, value } = await reader.read()
            if (done) break
            buf += dec.decode(value, { stream: true })
            let i
            while ((i = buf.indexOf('\n\n')) >= 0) {
              const block = buf.slice(0, i)
              buf = buf.slice(i + 2)
              const data = block.split('\n').filter((l) => l.startsWith('data:')).map((l) => l.slice(5).trim()).join('')
              if (!data) continue
              try {
                const j = JSON.parse(data)
                setSnap(j)
                setHistory((h) => [...h.slice(-299), j])
              } catch { /* partial frame */ }
            }
          }
        } catch { /* fall through to retry */ }
        setConnected(false)
        if (!alive.current) return
        await new Promise((r) => setTimeout(r, delay))
        delay = Math.min(delay * 2, 5000)
      }
    }
    run()
    return () => { alive.current = false; ctrl?.abort() }
  }, [])

  return <Ctx.Provider value={{ snap, history, connected }}>{children}</Ctx.Provider>
}
