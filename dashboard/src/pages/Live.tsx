import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, pct, usd } from '../api'
import { useLive } from '../live'
import { LineChart, Section, Stat, simClock } from '../components/ui'

export default function Live() {
  const { snap } = useLive()
  const [sel, setSel] = useState<string>('')
  const camps: any[] = snap?.campaigns ?? []

  useEffect(() => {
    if (!sel && camps.length) setSel([...camps].sort((a, b) => b.spentMicros - a.spentMicros)[0].id)
  }, [camps.length])

  const pacing = useQuery({
    queryKey: ['pacing', sel],
    queryFn: () => api<number[][]>(`/api/campaigns/${sel}/pacing`),
    enabled: !!sel,
    refetchInterval: 3000,
  })

  if (!snap) return <div className="card text-slate-400">Waiting for the first snapshot…</div>

  const imps = camps.reduce((a, c) => a + c.impressions, 0)
  const clicks = camps.reduce((a, c) => a + c.clicks, 0)
  const spent = camps.reduce((a, c) => a + c.spentMicros, 0)
  const budget = camps.reduce((a, c) => a + c.budgetMicros, 0)
  const selected = camps.find((c) => c.id === sel)
  const series = pacing.data ?? []

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
        <Stat label="Spend (leased)" value={usd(spent)} sub={`of ${usd(budget)} budget`} />
        <Stat label="Impressions" value={imps.toLocaleString()} sub={`${clicks.toLocaleString()} clicks`} />
        <Stat label="CTR" value={pct(imps ? clicks / imps : 0)} sub="confirmed events only" />
        <Stat label="Simulated clock" value={simClock(snap.simTime)} sub={`${snap.simSpeed}x speed`} />
      </div>

      <Section title="Pacing: target curve vs actual spend">
        <div className="mb-2 flex items-center gap-2">
          <select className="input max-w-xs" value={sel} onChange={(e) => setSel(e.target.value)}>
            {camps.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
          {selected && (
            <span className="text-xs text-slate-400">
              participation {(selected.participation * 100).toFixed(0)}% · error {((selected.targetFraction - selected.actualFraction) * 100).toFixed(1)} pts
            </span>
          )}
        </div>
        <LineChart
          x={series.map((p) => simClock(p[0]))}
          yFormatter={(v) => `${(v * 100).toFixed(0)}%`}
          series={[
            { name: 'target', data: series.map((p) => p[1]), color: '#8d8a80', dashed: true },
            { name: 'actual', data: series.map((p) => p[2]), color: '#1d8f6e', area: true },
          ]}
        />
      </Section>

      <Section title="Campaigns">
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead><tr><th>Campaign</th><th>Spend</th><th>Budget used</th><th>Target</th><th>Impr.</th><th>CTR</th><th>pCTR</th><th>Awards</th></tr></thead>
            <tbody>
              {camps.map((c) => (
                <tr key={c.id} className="cursor-pointer border-t border-line hover:bg-white/5" onClick={() => setSel(c.id)}>
                  <td>{c.name}</td>
                  <td className="tabular-nums">{usd(c.spentMicros)}</td>
                  <td>
                    <div className="h-1.5 w-28 rounded bg-line"><div className="h-1.5 rounded bg-accent" style={{ width: `${Math.min(100, c.actualFraction * 100)}%` }} /></div>
                  </td>
                  <td className="tabular-nums">{(c.targetFraction * 100).toFixed(1)}%</td>
                  <td className="tabular-nums">{c.impressions.toLocaleString()}</td>
                  <td className="tabular-nums">{pct(c.ctr)}</td>
                  <td className="tabular-nums">{pct(c.predCtr)}</td>
                  <td className="tabular-nums">{c.awards.toLocaleString()}</td>
                </tr>
              ))}
              {camps.length === 0 && <tr><td colSpan={8} className="text-slate-500">No campaigns yet.</td></tr>}
            </tbody>
          </table>
        </div>
      </Section>
    </div>
  )
}
