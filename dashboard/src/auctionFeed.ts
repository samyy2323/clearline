import { useCallback, useEffect, useRef, useState } from 'react'
import { api, getSession } from './api'

export interface Bidder { campaignId: string; campaignName: string; advertiserId: string; bidCpmMicros: number; pctr: number; ecpm: number; outcome: string }
export interface AuctionSlot { id: string; w: number; h: number; floorCpmMicros: number; winnerCampaign: string | null; priceCpmMicros: number; bidders: Bidder[] }
export interface Auction {
  id: string; tsMs: number; arm: string; publisher: string; category: string; device: string; geo: string
  segments: string[]; tookMicros: number; slots: AuctionSlot[]
}

/**
 * Live auctions from the engine (a few sampled per second, each fully explained) delivered over SSE, then PLAYED BACK at a human pace:
 * real auctions run thousands of times a second, which nobody can follow, so the page shows them one at a time.
 */
export function useAuctionPlayer(intervalMs: number, paused: boolean) {
  const [current, setCurrent] = useState<Auction | null>(null)
  const [history, setHistory] = useState<Auction[]>([])
  const [connected, setConnected] = useState(false)
  const [received, setReceived] = useState(0)
  const queue = useRef<Auction[]>([])
  const alive = useRef(true)

  useEffect(() => {
    alive.current = true
    let ctrl: AbortController | null = null
    api<Auction[]>('/api/auctions/recent').then((r) => { queue.current.push(...r.slice(-6)) }).catch(() => undefined)

    async function run() {
      let delay = 500
      while (alive.current) {
        ctrl = new AbortController()
        try {
          const res = await fetch('/api/auctions/stream', { headers: { Authorization: `Bearer ${getSession()?.token}`, Accept: 'text/event-stream' }, signal: ctrl.signal })
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
                const a = JSON.parse(data) as Auction
                if (queue.current.length > 40) queue.current.shift()   // never fall far behind: drop the oldest
                queue.current.push(a)
                setReceived((n) => n + 1)
              } catch { /* partial frame */ }
            }
          }
        } catch { /* retry below */ }
        setConnected(false)
        if (!alive.current) return
        await new Promise((r) => setTimeout(r, delay))
        delay = Math.min(delay * 2, 5000)
      }
    }
    run()
    return () => { alive.current = false; ctrl?.abort() }
  }, [])

  const step = useCallback(() => {
    const next = queue.current.shift()
    if (!next) return
    setCurrent(next)
    setHistory((h) => [next, ...h].slice(0, 12))
  }, [])

  useEffect(() => {
    if (paused) return
    const t = setInterval(step, intervalMs)
    return () => clearInterval(t)
  }, [paused, intervalMs, step])

  return { current, history, connected, received, step, queued: () => queue.current.length }
}
