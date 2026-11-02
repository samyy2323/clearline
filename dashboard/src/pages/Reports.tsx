import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, getSession, pct, usd } from '../api'
import { BarChart, LineChart, Section, Stat } from '../components/ui'

const WINDOWS = [{ m: 15, l: '15 min' }, { m: 60, l: '1 hour' }, { m: 360, l: '6 hours' }, { m: 1440, l: '24 hours' }]
const DIMS = ['campaign', 'geo', 'device', 'publisher', 'arm'] as const

/** Historical analytics from ClickHouse rollups (full edition). Numbers here come from events that went through Kafka. */
export default function Reports() {
  const admin = getSession()?.role === 'admin'
  const [minutes, setMinutes] = useState(60)
  const [by, setBy] = useState<(typeof DIMS)[number]>('geo')
  const opts = { refetchInterval: 4000 }

  const summary = useQuery({ queryKey: ['rep-summary', minutes], queryFn: () => api<any>(`/api/reports/summary?minutes=${minutes}`), ...opts })
  const series = useQuery({ queryKey: ['rep-series', minutes], queryFn: () => api<any[]>(`/api/reports/timeseries?minutes=${minutes}`), ...opts })
  const breakdown = useQuery({ queryKey: ['rep-by', by, minutes], queryFn: () => api<any[]>(`/api/reports/breakdown?by=${by}&minutes=${minutes}`), ...opts })
  const campaigns = useQuery({ queryKey: ['rep-camps', minutes], queryFn: () => api<any[]>(`/api/reports/campaigns?minutes=${minutes}`), ...opts })
  const flags = useQuery({ queryKey: ['rep-flags', minutes], queryFn: () => api<any[]>(`/api/reports/flags?minutes=${minutes}`), enabled: admin, ...opts })

  const s = summary.data
  const t = series.data ?? []
  const label = (sec: number) => new Date(sec * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false })

  if (summary.isError) return <div className="card text-red-400">Reports are unavailable: {(summary.error as Error).message}</div>

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center gap-2">
        {WINDOWS.map((w) => (
          <button key={w.m} className={minutes === w.m ? 'btn' : 'btn-ghost'} onClick={() => setMinutes(w.m)}>{w.l}</button>
        ))}
        <span className="ml-auto text-xs text-slate-500">Source: Kafka to ClickHouse, minute rollups (AggregatingMergeTree)</span>
      </div>

      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <Stat label="Impressions" value={(s?.impressions ?? 0).toLocaleString()} sub={`${(s?.wins ?? 0).toLocaleString()} wins`} />
        <Stat label="Clicks" value={(s?.clicks ?? 0).toLocaleString()} />
        <Stat label="CTR" value={pct(s?.ctr ?? 0)} sub="clean events only" />
        <Stat label="Billed spend" value={usd(s?.spendMicros ?? 0)} sub="de-duplicated impressions" />
      </div>

      <Section title="Per-minute impressions and spend">
        <LineChart
          x={t.map((p) => label(p.t))}
          series={[
            { name: 'impressions / min', data: t.map((p) => p.imps), color: '#c9a45c', area: true },
            { name: 'clicks / min', data: t.map((p) => p.clicks), color: '#8fb8a8' },
          ]}
        />
      </Section>

      <div className="grid gap-5 lg:grid-cols-2">
        <Section title="Breakdown" right={
          <select className="input max-w-[10rem]" value={by} onChange={(e) => setBy(e.target.value as any)}>
            {DIMS.map((d) => <option key={d} value={d}>{d}</option>)}
          </select>}>
          <BarChart x={(breakdown.data ?? []).slice(0, 10).map((r) => String(r.k).replace(/^c\d+-/, '#'))} height={230}
            yFormatter={(v) => usd(v)}
            series={[{ name: 'billed spend', data: (breakdown.data ?? []).slice(0, 10).map((r) => r.spend), color: '#c9a45c' }]} />
        </Section>

        <Section title="Top campaigns">
          <div className="max-h-[230px] overflow-y-auto">
            <table className="w-full">
              <thead><tr><th>Campaign</th><th>Impr.</th><th>Clicks</th><th>CTR</th><th>Spend</th></tr></thead>
              <tbody>
                {(campaigns.data ?? []).slice(0, 12).map((r) => (
                  <tr key={r.campaign} className="border-t border-line">
                    <td className="font-mono text-xs">{r.campaign}</td>
                    <td className="num">{r.imps.toLocaleString()}</td>
                    <td className="num">{r.clicks.toLocaleString()}</td>
                    <td className="num">{pct(r.imps ? r.clicks / r.imps : 0)}</td>
                    <td className="num">{usd(r.spend)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Section>
      </div>

      {admin && (
        <Section title="Flagged traffic (kept in the raw log, excluded from billing)">
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
            {(flags.data ?? []).map((f) => (
              <div key={f.flag} className="rounded-md border border-line px-3 py-2">
                <div className="label">{f.flag}</div>
                <div className="num font-display text-2xl">{Number(f.n).toLocaleString()}</div>
              </div>
            ))}
            {(flags.data ?? []).length === 0 && <div className="text-sm text-slate-500">No flagged events in this window.</div>}
          </div>
        </Section>
      )}
    </div>
  )
}
