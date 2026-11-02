import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { api, usd } from '../api'
import { useLive } from '../live'
import { LineChart, Section, Stat, timeLabel } from '../components/ui'

const SCENARIOS = ['steady', 'ramp', 'spike', 'soak', 'chaos', 'compressed-day']
const HINT: Record<string, string> = {
  steady: 'constant arrival rate',
  ramp: 'ramps from 10% to 100% over a minute',
  spike: '3x bursts for 10 s every 40 s',
  soak: 'long constant run',
  chaos: 'kills Redis at 30 s, adds 200 ms latency at 70 s',
  'compressed-day': '24 simulated hours in 20 minutes with a diurnal curve and 3x bursts',
}

export default function Simulator() {
  const { snap } = useLive()
  const presets = useQuery({ queryKey: ['presets'], queryFn: () => api<any[]>('/api/sim/presets') })
  const [scenario, setScenario] = useState('compressed-day')
  const [rate, setRate] = useState(600)
  const [bot, setBot] = useState(0.02)
  const [dup, setDup] = useState(0.03)
  const [flood, setFlood] = useState(0.002)

  const choose = (s: string) => {
    setScenario(s)
    const p = presets.data?.find((x) => x.scenario === s)
    if (p) { setRate(p.rate); setBot(p.botFraction); setDup(p.dupFraction); setFlood(p.floodFraction) }
  }

  const start = useMutation({ mutationFn: () => api('/api/sim/start', { method: 'POST', body: JSON.stringify({ scenario, rate, botFraction: bot, dupFraction: dup, floodFraction: flood }) }) })
  const stop = useMutation({ mutationFn: () => api('/api/sim/stop', { method: 'POST' }) })
  const faults = useMutation({ mutationFn: (b: any) => api('/api/sim/faults', { method: 'POST', body: JSON.stringify(b) }) })

  const sim = snap?.sim
  const f = snap?.system?.faults
  const learning: number[][] = sim?.learning ?? []

  return (
    <div className="space-y-4">
      <Section title="Scenario" right={<span className={sim?.running ? 'text-emerald-400' : 'text-slate-500'}>{sim?.running ? `running · ${sim.elapsedSec}s` : 'stopped'}</span>}>
        <div className="grid gap-3 md:grid-cols-3">
          <div>
            <label className="label">Preset</label>
            <select className="input" value={scenario} onChange={(e) => choose(e.target.value)}>{SCENARIOS.map((s) => <option key={s}>{s}</option>)}</select>
            <div className="mt-1 text-xs text-slate-500">{HINT[scenario]}</div>
          </div>
          <div>
            <label className="label">Base rate: {rate} req/s</label>
            <input type="range" min={50} max={5000} step={50} value={rate} onChange={(e) => setRate(+e.target.value)} className="w-full" />
          </div>
          <div className="text-xs text-slate-500">In-process simulator. For HTTP benchmarks use <code>make bench</code> (open-loop generator).</div>
          <div>
            <label className="label">Bot traffic: {(bot * 100).toFixed(1)}%</label>
            <input type="range" min={0} max={0.3} step={0.005} value={bot} onChange={(e) => setBot(+e.target.value)} className="w-full" />
          </div>
          <div>
            <label className="label">Duplicate events: {(dup * 100).toFixed(1)}%</label>
            <input type="range" min={0} max={0.5} step={0.005} value={dup} onChange={(e) => setDup(+e.target.value)} className="w-full" />
          </div>
          <div>
            <label className="label">Click flooders: {(flood * 100).toFixed(2)}%</label>
            <input type="range" min={0} max={0.1} step={0.001} value={flood} onChange={(e) => setFlood(+e.target.value)} className="w-full" />
          </div>
        </div>
        <div className="mt-4 flex gap-2">
          <button className="btn" disabled={start.isPending} onClick={() => start.mutate()}>{sim?.running ? 'Restart with these settings' : 'Start'}</button>
          <button className="btn-ghost" disabled={!sim?.running} onClick={() => stop.mutate()}>Stop</button>
        </div>
      </Section>

      <Section title="Failure injection">
        <div className="flex flex-wrap items-center gap-3">
          <button className={f?.redisDown ? 'btn-danger' : 'btn-ghost'} onClick={() => faults.mutate({ redisDown: !f?.redisDown })}>
            {f?.redisDown ? 'Redis is DOWN: click to restore' : 'Kill Redis'}
          </button>
          {[0, 50, 200, 500].map((ms) => (
            <button key={ms} className={f?.extraLatencyMs === ms ? 'btn' : 'btn-ghost'} onClick={() => faults.mutate({ latencyMs: ms })}>
              {ms === 0 ? 'No added latency' : `+${ms} ms`}
            </button>
          ))}
        </div>
        <p className="mt-2 text-xs text-slate-500">
          Redis down: engines keep spending their local lease, then fail closed (no budget, no bid); frequency caps fail open and are flagged degraded.
          Added latency hits the frequency-cap lookup on the hot path and the lease renewals.
        </p>
      </Section>

      {sim && (
        <>
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <Stat label="Requests sent" value={sim.sent.toLocaleString()} sub={`${sim.dropped} dropped by sim backpressure`} />
            <Stat label="Human impressions" value={sim.humanImpressions.toLocaleString()} sub={`${sim.humanClicks.toLocaleString()} clicks`} />
            <Stat label="Billing drift" value={usd(sim.billing.driftMicros)} tone={sim.billing.driftMicros === 0 ? 'good' : 'bad'}
              sub={`expected ${usd(sim.billing.expectedMicros)} vs billed ${usd(sim.billing.actualMicros)}`} />
            <Stat label="Injected duplicates" value={(sim.injected.duplicateImpressions + sim.injected.duplicateClicks).toLocaleString()}
              sub={`${sim.injected.botEvents} bot, ${sim.injected.floodClicks} flood, ${sim.injected.clickWithoutImpression} no-impression`} />
          </div>
          <Section title="Learning loop: predicted CTR vs hidden true CTR">
            <LineChart x={learning.map((p) => timeLabel(p[0]))} yFormatter={(v) => `${(v * 100).toFixed(2)}%`} series={[
              { name: 'predicted (model)', data: learning.map((p) => p[1]), color: '#1d8f6e' },
              { name: 'true (hidden)', data: learning.map((p) => p[2]), color: '#c8963e', dashed: true },
              { name: 'observed', data: learning.map((p) => p[3]), color: '#4f7cac' },
            ]} />
            <p className="mt-1 text-xs text-slate-500">
              Train offline with <code>make train</code> (reads the impression/click log), the engine hot-reloads the weights, and the predicted line
              moves toward the true line. Before training the model is a flat 2% prior.
            </p>
          </Section>
        </>
      )}
    </div>
  )
}
