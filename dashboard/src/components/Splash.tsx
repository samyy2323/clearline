import { useEffect, useState } from 'react'

// A real second-price auction resolving, drawn as the loading sequence. Figures are illustrative.
const BIDS = [
  { brand: 'Spotify', cpm: 4.12, w: 100, win: true },
  { brand: 'Samsung', cpm: 3.88, w: 94 },
  { brand: 'Nike', cpm: 3.41, w: 83 },
  { brand: 'Zomato', cpm: 2.97, w: 72 },
  { brand: 'Booking.com', cpm: 2.35, w: 57 },
]

export function useSplash(): [boolean, () => void] {
  const seen = (() => { try { return sessionStorage.getItem('adx.splash') === '1' } catch { return false } })()
  const [show, setShow] = useState(!seen)
  const done = () => {
    try { sessionStorage.setItem('adx.splash', '1') } catch { /* ignore */ }
    setShow(false)
  }
  useEffect(() => {
    if (!show) return
    const t = setTimeout(done, 4600)
    return () => clearTimeout(t)
  }, [show])
  return [show, done]
}

export default function Splash({ onSkip }: { onSkip: () => void }) {
  return (
    <div className="splash fixed inset-0 z-50 flex cursor-pointer items-center justify-center bg-ink" onClick={onSkip} role="presentation">
      <div className="w-full max-w-xl px-8">
        <div className="splash-word font-display text-6xl tracking-tight text-slate-100 md:text-7xl">AdExchange</div>
        <div className="splash-line mt-5 h-px w-full bg-accent/70" />
        <div className="splash-sub mt-3 flex justify-between text-[11px] uppercase tracking-[0.2em] text-slate-400">
          <span>Real-time auction platform</span>
          <span>Impression request 00417</span>
        </div>

        <div className="mt-12 space-y-3">
          {BIDS.map((b, i) => (
            <div key={b.brand} className={`bid-row ${b.win ? '' : 'loser'}`} style={{ animationDelay: `${1.3 + i * 0.28}s, 3.0s` }}>
              <div className="flex items-baseline justify-between text-sm">
                <span className={b.win ? 'text-slate-100' : 'text-slate-300'}>{b.brand}</span>
                <span className="num font-mono text-xs text-slate-400">${b.cpm.toFixed(2)} CPM</span>
              </div>
              <div className="mt-1.5 h-[3px] w-full bg-line">
                <div className="bid-bar h-[3px]" style={{ width: `${b.w}%`, background: b.win ? '#c9a45c' : '#3a414c', animationDelay: `${1.4 + i * 0.28}s` }} />
              </div>
            </div>
          ))}
        </div>

        <div className="clear-note mt-8 flex items-baseline justify-between border-t border-line pt-4 text-sm">
          <span className="text-slate-300">Spotify wins. Cleared at <span className="num text-accent">$3.89</span>, one micro above the runner-up.</span>
          <span className="num font-mono text-xs text-slate-400">38 ms</span>
        </div>
        <div className="mt-6 text-center text-[10px] uppercase tracking-[0.25em] text-slate-500">Click anywhere to skip</div>
      </div>
    </div>
  )
}
