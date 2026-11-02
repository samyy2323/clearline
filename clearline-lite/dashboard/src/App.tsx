import { useState } from 'react'
import { NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { api, getSession, setSession, Session } from './api'
import { LiveProvider, useLive } from './live'
import Splash, { useSplash } from './components/Splash'
import { simClock } from './components/ui'
import Live from './pages/Live'
import Campaigns from './pages/Campaigns'
import Pricing from './pages/Pricing'
import Health from './pages/Health'
import Simulator from './pages/Simulator'

const Icon = ({ d }: { d: string }) => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"><path d={d} /></svg>
)
const ICONS = {
  live: 'M3 12h4l3-8 4 16 3-8h4',
  campaigns: 'M4 6h16M4 12h16M4 18h10',
  pricing: 'M12 3v18M7 8c0-2 2-3 5-3s5 1 5 3-2 3-5 3-5 1-5 3 2 3 5 3 5-1 5-3',
  health: 'M4 19V9m6 10V5m6 14v-7m4 7H2',
  sim: 'M5 4v16M12 4v16M19 4v16M3 9h4M10 15h4M17 7h4',
}

const TITLES: Record<string, [string, string]> = {
  '/live': ['Performance', 'Spend, pacing and delivery across your campaigns'],
  '/campaigns': ['Campaigns', 'Targeting, bids, budgets and flight dates'],
  '/pricing': ['Pricing experiment', 'First-price against generalized second-price on live traffic'],
  '/health': ['System health', 'Latency, throughput, budget safety and event integrity'],
  '/simulator': ['Traffic simulator', 'Scenario control, failure injection and the learning loop'],
}

function Login({ onDone }: { onDone: () => void }) {
  const [user, setUser] = useState('admin')
  const [pass, setPass] = useState('')
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setErr('')
    try {
      const r = await api<Session>('/api/auth/login', { method: 'POST', body: JSON.stringify({ username: user, password: pass }) })
      setSession(r)
      onDone()
    } catch (ex: any) {
      setErr(ex.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="grid min-h-full lg:grid-cols-[1.15fr_1fr]">
      <div className="relative hidden flex-col justify-between border-r border-line p-14 lg:flex"
        style={{ background: 'radial-gradient(1200px 600px at 20% 10%, rgba(201,164,92,0.08), transparent 60%), #0a0b0d' }}>
        <div className="font-display text-2xl tracking-tight">Clearline</div>
        <div>
          <h1 className="font-display text-6xl leading-[1.02] tracking-tight text-slate-100">
            Every impression,<br />cleared in under<br /><span className="text-accent">fifty milliseconds.</span>
          </h1>
          <p className="mt-6 max-w-md text-[15px] leading-relaxed text-slate-300">
            A real-time exchange that scores, prices and awards each ad request in memory, spends budget from local leases
            instead of per-bid network calls, and keeps overspend provably bounded.
          </p>
        </div>
        <div className="grid grid-cols-3 gap-6 text-xs text-slate-400">
          <div><div className="mb-1 text-[11px] uppercase tracking-[0.14em] text-slate-300">Auction</div>First-price and GSP with live A/B</div>
          <div><div className="mb-1 text-[11px] uppercase tracking-[0.14em] text-slate-300">Budget</div>Lease-based spend with PID pacing</div>
          <div><div className="mb-1 text-[11px] uppercase tracking-[0.14em] text-slate-300">Integrity</div>Signed events, dedupe, fraud flags</div>
        </div>
      </div>
      <div className="flex items-center justify-center p-8">
        <form onSubmit={submit} className="w-full max-w-sm space-y-4">
          <div>
            <h2 className="font-display text-4xl tracking-tight">Sign in</h2>
            <p className="mt-1 text-sm text-slate-400">Access the exchange console.</p>
          </div>
          <div>
            <label className="label">Username</label>
            <input className="input" value={user} onChange={(e) => setUser(e.target.value)} autoComplete="username" />
          </div>
          <div>
            <label className="label">Password</label>
            <input className="input" type="password" value={pass} onChange={(e) => setPass(e.target.value)} autoComplete="current-password" autoFocus />
          </div>
          {err && <div className="text-sm text-red-400">{err}</div>}
          <button className="btn w-full" disabled={busy || !pass}>{busy ? 'Signing in' : 'Continue'}</button>
          <div className="hairline pt-4 text-xs leading-relaxed text-slate-500">
            <span className="text-slate-300">admin</span> sees the whole exchange and the proof screens.{' '}
            <span className="text-slate-300">advertiser1</span> to <span className="text-slate-300">advertiser8</span> sign in as
            Nike, Samsung, Spotify, Zomato, HDFC Bank, Hyundai, Booking.com and Nykaa, and see only their own campaigns.
          </div>
        </form>
      </div>
    </div>
  )
}

function Shell({ onLogout }: { onLogout: () => void }) {
  const s = getSession()!
  const { connected, snap } = useLive()
  const loc = useLocation()
  const [title, sub] = TITLES[loc.pathname] ?? TITLES['/live']
  const admin = s.role === 'admin'
  const link = ({ isActive }: { isActive: boolean }) =>
    `flex items-center gap-3 rounded-md px-3 py-2 text-[13px] transition ${isActive ? 'bg-white/[0.06] text-slate-100' : 'text-slate-400 hover:bg-white/[0.03] hover:text-slate-200'}`
  return (
    <div className="min-h-full lg:grid lg:grid-cols-[240px_1fr]">
      <aside className="border-b border-line p-5 lg:sticky lg:top-0 lg:flex lg:h-screen lg:flex-col lg:border-b-0 lg:border-r">
        <div className="font-display text-[26px] tracking-tight">Clearline</div>
        <div className="mt-0.5 text-[10px] uppercase tracking-[0.2em] text-slate-500">Console</div>
        <nav className="mt-6 flex gap-1 overflow-x-auto lg:flex-col">
          <NavLink to="/live" className={link}><Icon d={ICONS.live} />Performance</NavLink>
          <NavLink to="/campaigns" className={link}><Icon d={ICONS.campaigns} />Campaigns</NavLink>
          {admin && <NavLink to="/pricing" className={link}><Icon d={ICONS.pricing} />Pricing experiment</NavLink>}
          {admin && <NavLink to="/health" className={link}><Icon d={ICONS.health} />System health</NavLink>}
          {admin && <NavLink to="/simulator" className={link}><Icon d={ICONS.sim} />Simulator</NavLink>}
        </nav>
        <div className="mt-auto hidden pt-6 lg:block">
          <div className="hairline pt-4">
            <div className="text-sm text-slate-200">{s.company ?? s.user}</div>
            <div className="text-xs text-slate-500">{admin ? 'Administrator' : `Advertiser account ${s.advertiserId}`}</div>
            <button className="mt-3 text-xs text-slate-400 underline-offset-4 hover:text-slate-200 hover:underline" onClick={onLogout}>Sign out</button>
          </div>
        </div>
      </aside>
      <main className="min-w-0 px-6 pb-20 pt-8 lg:px-10">
        <header className="mb-7 flex flex-wrap items-end justify-between gap-4">
          <div>
            <h1 className="font-display text-[40px] leading-none tracking-tight">{title}</h1>
            <p className="mt-2 text-sm text-slate-400">{sub}</p>
          </div>
          <div className="flex items-center gap-4 text-xs text-slate-400">
            {snap && <span className="num font-mono">{simClock(snap.simTime)}{snap.simSpeed > 1 ? `  ${snap.simSpeed}x` : ''}</span>}
            <span className="flex items-center gap-2">
              <span className={`inline-block h-1.5 w-1.5 rounded-full ${connected ? 'bg-emerald-400' : 'bg-red-500'}`} />
              {connected ? 'Live' : 'Reconnecting'}
            </span>
            <button className="btn-ghost lg:hidden" onClick={onLogout}>Sign out</button>
          </div>
        </header>
        <div className="space-y-5">
          <Routes>
            <Route path="/live" element={<Live />} />
            <Route path="/campaigns" element={<Campaigns />} />
            {admin && <Route path="/pricing" element={<Pricing />} />}
            {admin && <Route path="/health" element={<Health />} />}
            {admin && <Route path="/simulator" element={<Simulator />} />}
            <Route path="*" element={<Navigate to="/live" replace />} />
          </Routes>
        </div>
      </main>
    </div>
  )
}

export default function App() {
  const [authed, setAuthed] = useState(!!getSession())
  const [splash, skip] = useSplash()
  return (
    <>
      {splash && <Splash onSkip={skip} />}
      {!authed ? (
        <Login onDone={() => setAuthed(true)} />
      ) : (
        <LiveProvider>
          <Shell onLogout={() => { setSession(null); setAuthed(false) }} />
        </LiveProvider>
      )}
    </>
  )
}
