import { useLive } from '../live'
import { LineChart, Section, Stat, timeLabel } from '../components/ui'

const fmt = (n: number, d = 2) => (n ?? 0).toFixed(d)

/** The proof screen: latency percentiles, throughput, error and overspend, lease stats, fraud counters. */
export default function Health() {
  const { snap, history } = useLive()
  if (!snap) return <div className="card text-slate-400">Waiting for data…</div>
  const lat = snap.latencyMs
  const h = history.slice(-180)
  const x = h.map((p) => timeLabel(p.t))
  const leases = snap.leases
  const sys = snap.system
  const stages = snap.stagesMs
  const over = snap.overspendPct as number

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4 lg:grid-cols-6">
        <Stat label="Requests / s" value={snap.rps.toLocaleString()} />
        <Stat label="p50" value={`${fmt(lat.p50)} ms`} />
        <Stat label="p95" value={`${fmt(lat.p95)} ms`} />
        <Stat label="p99" value={`${fmt(lat.p99)} ms`} tone={lat.p99 > 50 ? 'bad' : lat.p99 > 10 ? 'warn' : 'good'} sub="budget: 50 ms" />
        <Stat label="No-bid rate" value={`${fmt(snap.noBidRate * 100, 1)}%`} />
        <Stat label="Error rate" value={`${fmt(snap.errorRate * 100, 2)}%`} tone={snap.errorRate > 0.01 ? 'bad' : 'good'} sub="timeouts + shed" />
        <Stat label="Overspend" value={`${fmt(over, 3)}%`} tone={over > 1 ? 'bad' : 'good'} sub="max over budget" />
        <Stat label="Lease renew failures" value={leases.renewFailures} tone={leases.renewFailures ? 'warn' : 'good'} />
        <Stat label="Spend denied (fail-closed)" value={leases.spendDenied.toLocaleString()} sub="no lease budget left" />
        <Stat label="Freq-cap degraded" value={sys.degradedFreqCap.toLocaleString()} sub="fail-open lookups" tone={sys.degradedFreqCap ? 'warn' : 'good'} />
        <Stat label="Kafka lag" value={snap.kafkaLag ?? 'n/a'} sub="lite mode has no Kafka" />
        <Stat label="In flight / shed" value={`${sys.inFlight} / ${sys.shed}`} />
      </div>

      <Section title="Latency percentiles (gateway-observed)">
        <LineChart x={x} yName="ms" series={[
          { name: 'p50', data: h.map((p) => p.latencyMs.p50), color: '#8fb8a8' },
          { name: 'p95', data: h.map((p) => p.latencyMs.p95), color: '#7d93b2' },
          { name: 'p99', data: h.map((p) => p.latencyMs.p99), color: '#cf6f63' },
        ]} />
      </Section>

      <div className="grid gap-4 lg:grid-cols-2">
        <Section title="Throughput">
          <LineChart x={x} height={200} series={[{ name: 'req/s', data: h.map((p) => p.rps), color: '#c9a45c', area: true }]} />
        </Section>
        <Section title="No-bid and error rate">
          <LineChart x={x} height={200} yFormatter={(v) => `${(v * 100).toFixed(0)}%`} series={[
            { name: 'no-bid', data: h.map((p) => p.noBidRate), color: '#8b8f98' },
            { name: 'error', data: h.map((p) => p.errorRate), color: '#cf6f63' },
          ]} />
        </Section>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Section title="Per-stage latency (last second)">
          <table className="w-full">
            <thead><tr><th>Stage</th><th>p50</th><th>p99</th><th>Budget</th></tr></thead>
            <tbody>
              {Object.entries(stages).map(([k, v]: any) => (
                <tr key={k} className="border-t border-line">
                  <td className="capitalize">{k}</td><td>{fmt(v.p50, 3)} ms</td><td>{fmt(v.p99, 3)} ms</td>
                  <td className="text-slate-500">{k === 'total' ? '35 ms' : k === 'retrieval' || k === 'scoring' || k === 'budget' ? '10 ms' : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Section>
        <Section title="Fraud and rejected events">
          <div className="grid grid-cols-2 gap-x-4 text-sm">
            {Object.entries(snap.fraud.flagged).map(([k, v]: any) => (
              <div key={k} className="flex justify-between border-b border-line py-1"><span>{k}</span><span className="tabular-nums">{v.toLocaleString()}</span></div>
            ))}
            {Object.entries(snap.fraud.rejected).map(([k, v]: any) => (
              <div key={k} className="flex justify-between border-b border-line py-1 text-amber-300"><span>rejected {k}</span><span>{v}</span></div>
            ))}
          </div>
          <p className="mt-2 text-xs text-slate-500">Flagged events are counted and excluded from billing, never silently dropped.</p>
        </Section>
      </div>

      <Section title="Budget leases">
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4 text-sm">
          <div><div className="label">Leases acquired</div>{leases.acquired.toLocaleString()}</div>
          <div><div className="label">Local spends approved</div>{leases.spendApproved.toLocaleString()}</div>
          <div><div className="label">Outbox depth</div>{sys.outboxDepth}</div>
          <div><div className="label">pCTR model</div>v{snap.model.version} ({snap.model.source})</div>
        </div>
      </Section>
    </div>
  )
}
