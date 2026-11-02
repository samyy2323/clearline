import { useState } from 'react'
import { NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api, getSession, setSession, Session } from './api'
import { LiveProvider, useLive } from './live'
import { useTheme } from './theme'
import Splash, { Mark, useSplash } from './components/Splash'
import { AdCreative } from './brands'
import { simClock } from './components/ui'
import Live from './pages/Live'
import Campaigns from './pages/Campaigns'
import Pricing from './pages/Pricing'
import Health from './pages/Health'
import Simulator from './pages/Simulator'
import Reports from './pages/Reports'
import AuctionPage from './pages/Auction'

const Icon = ({ d }: { d: string }) => (
  <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d={d} /></svg>
)
const ICONS = {
  live: 'M3 12h4l3-8 4 16 3-8h4',
  auction: 'M4 7h16M4 12h10M4 17h6M17 14l2 2 3-4',
  campaigns: 'M4 6h16M4 12h16M4 18h10',
  reports: 'M4 20h16M6 16V8m6 8V4m6 12v-6',
  pricing: 'M12 3v18M7 8c0-2 2-3 5-3s5 1 5 3-2 3-5 3-5 1-5 3 2 3 5 3 5-1 5-3',
  health: 'M4 19V9m6 10V5m6 14v-7m4 7H2',
  sim: 'M5 4v16M12 4v16M19 4v16M3 9h4M10 15h4M17 7h4',
}

const TITLES: Record<string, [string, string]> = {
  '/live': ['Performance', 'Spend, pacing and delivery across your campaigns'],
  '/auction': ['Live auction', 'Watch ads win their place on a page, bid by bid'],
  '/campaigns': ['Campaigns', 'Targeting, bids, budgets and flight dates'],
  '/reports': ['Reports', 'Historical delivery and spend from the ClickHouse rollups'],
  '/pricing': ['Pricing experiment', 'First-price against generalized second-price on live traffic'],
  '/health': ['System health', 'Latency, throughput, budget safety and event integrity'],
  '/simulator': ['Traffic simulator', 'Scenario control, failure injection and the learning loop'],
}

function ThemeToggle() {
  const [theme, toggle] = useTheme()
  return (
    <button onClick={toggle} className="btn-ghost !h-9 !w-9 !p-0" aria-label="Toggle light and dark theme" title="Toggle theme">
      {theme === 'light'
        ? <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" /></svg>
        : <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><circle cx="12" cy="12" r="4" /><path d="M12 2v2m0 16v2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M2 12h2m16 0h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></svg>}
    </button>
  )
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
    <div className="grid min-h-full lg:grid-cols-[1.1fr_1fr]">
      <div className="relative hidden overflow-hidden border-r border-line lg:block">
        <div className="absolute inset-0 flex flex-col p-14">
          <div className="flex items-center gap-3"><Mark size={34} /><span className="font-display text-xl font-bold tracking-[-0.03em] text-slate-100">Clearline</span></div>
          <h1 className="mt-14 max-w-lg font-display text-[46px] font-bold leading-[1.04] tracking-[-0.04em] text-slate-100">
            Every impression, cleared in <span className="text-accent">under fifty milliseconds.</span>
          </h1>
          <p className="mt-5 max-w-md text-[15px] leading-relaxed text-slate-400">
            Real auctions, real budgets, real fraud checks. Watch advertisers compete for every slot on the page, live.
          </p>
          <div className="pointer-events-none relative mt-auto h-[290px] min-h-[200px]">
            <div className="absolute left-0 top-0 w-[41%] rotate-[-4deg] shadow-lift" style={{ animation: 'floaty 7s ease-in-out infinite' }}><AdCreative campaignName="Spotify | Premium Family Plan" w={300} h={250} /></div>
            <div className="absolute left-[44%] top-4 w-[41%] rotate-[3deg] shadow-lift" style={{ animation: 'floaty 8s ease-in-out -2s infinite' }}><AdCreative campaignName="Nike | Air Max Day Launch" w={300} h={250} /></div>
            <div className="absolute bottom-0 left-[6%] w-[88%] rotate-[-1deg] shadow-lift" style={{ animation: 'floaty 9s ease-in-out -4s infinite' }}><AdCreative campaignName="Booking.com | Summer Getaways" w={728} h={90} /></div>
          </div>
        </div>
      </div>
      <div className="relative flex items-center justify-center p-8">
        <div className="absolute right-6 top-6"><ThemeToggle /></div>
        <form onSubmit={submit} className="w-full max-w-sm space-y-5">
          <div>
            <h2 className="font-display text-[34px] font-bold tracking-[-0.03em] text-slate-100">Welcome back</h2>
            <p className="mt-1.5 text-sm text-slate-400">Sign in to the exchange console.</p>
          </div>
          <div>
            <label className="label">Username</label>
            <input className="input" value={user} onChange={(e) => setUser(e.target.value)} autoComplete="username" />
          </div>
          <div>
            <label className="label">Password</label>
            <input className="input" type="password" value={pass} onChange={(e) => setPass(e.target.value)} autoComplete="current-password" autoFocus />
          </div>
          {err && <div className="rounded-xl bg-red-500/10 px-3.5 py-2.5 text-sm text-red-400">{err}</div>}
          <button className="btn w-full !py-3" disabled={busy || !pass}>{busy ? 'Signing in' : 'Continue'}</button>
          <div className="hairline pt-4 text-xs leading-relaxed text-slate-500">
            <span className="text-slate-300">admin</span> sees the whole exchange, including the live auction. <span className="text-slate-300">advertiser1</span> to{' '}
            <span className="text-slate-300">advertiser8</span> sign in as Nike, Samsung, Spotify, Zomato, HDFC Bank, Hyundai, Booking.com and Nykaa, and see only their own campaigns.
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
  // Reports exist only in the full edition (reporting service + ClickHouse); probe once and hide the tab otherwise
  const reportsProbe = useQuery({ queryKey: ['reports-available'], queryFn: () => api('/api/reports/summary?minutes=5').then(() => true, () => false), staleTime: 60_000 })
  const hasReports = reportsProbe.data === true
  const link = ({ isActive }: { isActive: boolean }) =>
    `group flex items-center gap-3 rounded-xl px-3 py-2.5 text-[13.5px] font-medium transition ${isActive ? 'bg-panel text-slate-100 shadow-card' : 'text-slate-400 hover:bg-panel/60 hover:text-slate-100'}`
  const initials = (s.company ?? s.user).slice(0, 2).toUpperCase()
  return (
    <div className="min-h-full lg:grid lg:grid-cols-[264px_1fr]">
      <aside className="border-b border-line p-5 lg:sticky lg:top-0 lg:flex lg:h-screen lg:flex-col lg:border-b-0 lg:border-r">
        <div className="flex items-center gap-3 px-1"><Mark size={32} /><div><div className="font-display text-[19px] font-bold leading-none tracking-[-0.03em] text-slate-100">Clearline</div><div className="mt-1 text-[10px] font-medium uppercase tracking-[0.18em] text-slate-500">Exchange console</div></div></div>
        <nav className="mt-8 flex gap-1 overflow-x-auto lg:flex-col">
          <NavLink to="/live" className={link}><Icon d={ICONS.live} />Performance</NavLink>
          {admin && <NavLink to="/auction" className={link}><Icon d={ICONS.auction} />Live auction<span className="ml-auto rounded-full bg-accent px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wider text-[rgb(var(--accent-ink))]">Live</span></NavLink>}
          <NavLink to="/campaigns" className={link}><Icon d={ICONS.campaigns} />Campaigns</NavLink>
          {hasReports && <NavLink to="/reports" className={link}><Icon d={ICONS.reports} />Reports</NavLink>}
          {admin && <NavLink to="/pricing" className={link}><Icon d={ICONS.pricing} />Pricing experiment</NavLink>}
          {admin && <NavLink to="/health" className={link}><Icon d={ICONS.health} />System health</NavLink>}
          {admin && <NavLink to="/simulator" className={link}><Icon d={ICONS.sim} />Simulator</NavLink>}
        </nav>
        <div className="mt-auto hidden pt-6 lg:block">
          <div className="flex items-center gap-3 rounded-2xl border border-line bg-panel p-3 shadow-card">
            <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-accent text-xs font-bold text-[rgb(var(--accent-ink))]">{initials}</div>
            <div className="min-w-0 flex-1"><div className="truncate text-[13px] font-semibold text-slate-100">{s.company ?? s.user}</div><div className="truncate text-[11px] text-slate-400">{admin ? 'Administrator' : `Account ${s.advertiserId}`}</div></div>
          </div>
          <div className="mt-3 flex items-center justify-between px-1"><button className="text-xs font-medium text-slate-400 hover:text-slate-100" onClick={onLogout}>Sign out</button><ThemeToggle /></div>
        </div>
      </aside>
      <main className="min-w-0 px-6 pb-24 pt-9 lg:px-12">
        <header className="mb-8 flex flex-wrap items-end justify-between gap-4">
          <div>
            <h1 className="font-display text-[34px] font-bold leading-none tracking-[-0.035em] text-slate-100">{title}</h1>
            <p className="mt-2.5 text-sm text-slate-400">{sub}</p>
          </div>
          <div className="flex items-center gap-3 text-xs">
            {snap && <span className="chip num font-mono">{simClock(snap.simTime)}{snap.simSpeed > 1 ? `  ${snap.simSpeed}x` : ''}</span>}
            <span className="chip gap-2"><i className={`live-dot inline-block h-2 w-2 rounded-full ${connected ? 'bg-emerald-400' : 'bg-red-500'}`} />{connected ? 'Live' : 'Reconnecting'}</span>
            <span className="lg:hidden"><ThemeToggle /></span>
            <button className="btn-ghost lg:hidden" onClick={onLogout}>Sign out</button>
          </div>
        </header>
        <div className="space-y-6">
          <Routes>
            <Route path="/live" element={<Live />} />
            {admin && <Route path="/auction" element={<AuctionPage />} />}
            <Route path="/campaigns" element={<Campaigns />} />
            {hasReports && <Route path="/reports" element={<Reports />} />}
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
