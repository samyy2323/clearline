import { useEffect, useState } from 'react'

export function Mark({ size = 36 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 36 36" aria-hidden>
      <rect width="36" height="36" rx="11" fill="rgb(var(--accent))" />
      <path d="M8 24.5h20" stroke="rgb(var(--accent-ink))" strokeOpacity=".5" strokeWidth="2" strokeLinecap="round" />
      <path d="M8.5 21 15 13.5l4.5 4.5 8-9" fill="none" stroke="rgb(var(--accent-ink))" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

// A real second-price auction resolving, drawn as the loading sequence. The figures are illustrative.
const BIDS = [
  { brand: 'Spotify', cpm: 4.12, w: 100, dot: '#1ed760', win: true },
  { brand: 'Samsung', cpm: 3.88, w: 92, dot: '#1647b8' },
  { brand: 'Nike', cpm: 3.41, w: 80, dot: '#ff5a1f' },
  { brand: 'Zomato', cpm: 2.97, w: 68, dot: '#e23744' },
  { brand: 'Booking.com', cpm: 2.35, w: 54, dot: '#0050c8' },
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
    const t = setTimeout(done, 4400)
    return () => clearTimeout(t)
  }, [show])
  return [show, done]
}

export default function Splash({ onSkip }: { onSkip: () => void }) {
  return (
    <div className="splash fixed inset-0 z-50 flex cursor-pointer items-center justify-center bg-ink" style={{ backgroundImage: 'var(--mesh)' }} onClick={onSkip} role="presentation">
      <div className="w-full max-w-md px-8">
        <div className="splash-mark mb-6"><Mark size={52} /></div>
        <div className="splash-word font-display text-[54px] font-bold leading-none tracking-[-0.04em] text-slate-100">Clearline</div>
        <div className="splash-sub mt-3 text-sm text-slate-400">The real-time ad exchange, in under fifty milliseconds.</div>
        <div className="splash-line mt-8 h-px w-full bg-accent/60" />

        <div className="mt-8 space-y-3.5">
          {BIDS.map((b, i) => (
            <div key={b.brand} className="bid-row" style={{ animationDelay: `${1.1 + i * 0.22}s` }}>
              <div className="flex items-baseline justify-between text-[13px]">
                <span className={`flex items-center gap-2 ${b.win ? 'font-semibold text-slate-100' : 'text-slate-300'}`}><i className="h-2 w-2 rounded-full" style={{ background: b.dot }} />{b.brand}</span>
                <span className="num font-mono text-[11px] text-slate-400">${b.cpm.toFixed(2)} CPM</span>
              </div>
              <div className="mt-1.5 h-1 w-full rounded-full bg-line">
                <div className="bar-grow h-1 rounded-full" style={{ width: `${b.w}%`, background: b.win ? b.dot : 'rgb(var(--fg5) / 0.55)', animationDelay: `${1.2 + i * 0.22}s` }} />
              </div>
            </div>
          ))}
        </div>

        <div className="bid-row mt-7 text-[13px] text-slate-300" style={{ animationDelay: '2.7s' }}>
          <b className="text-slate-100">Spotify</b> wins and pays <b className="text-accent">$3.89</b>, just above the runner-up.
        </div>
        <div className="mt-8 text-center text-[10px] uppercase tracking-[0.25em] text-slate-500">Click anywhere to skip</div>
      </div>
    </div>
  )
}
