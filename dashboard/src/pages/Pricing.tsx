import { useLive } from '../live'
import { BarChart, Section, Stat } from '../components/ui'
import { usd } from '../api'

export default function Pricing() {
  const { snap } = useLive()
  if (!snap) return <div className="card text-slate-400">Waiting for data…</div>
  const arms: any[] = snap.arms ?? []
  const fp = arms.find((a) => a.name === 'first-price')
  const gsp = arms.find((a) => a.name === 'gsp')
  const lift = fp && gsp && fp.revenuePerAuctionMicros > 0 ? gsp.revenuePerAuctionMicros / fp.revenuePerAuctionMicros - 1 : null

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
        <Stat label="First-price rev / auction" value={fp ? usd(fp.revenuePerAuctionMicros) : '—'} sub={fp ? `${fp.auctions.toLocaleString()} auctions` : ''} />
        <Stat label="GSP rev / auction" value={gsp ? usd(gsp.revenuePerAuctionMicros) : '—'} sub={gsp ? `${gsp.auctions.toLocaleString()} auctions` : ''} />
        <Stat label="GSP vs first-price" value={lift === null ? '—' : `${(lift * 100).toFixed(1)}%`} tone={lift !== null && lift < 0 ? 'warn' : undefined}
          sub="revenue per auction, same traffic split by request-id hash" />
      </div>
      <Section title="Revenue by pricing arm">
        <BarChart
          x={arms.map((a) => a.name)}
          yFormatter={(v) => usd(v)}
          series={[
            { name: 'awarded at auction', data: arms.map((a) => a.awardedRevenueMicros), color: '#1d8f6e' },
            { name: 'billed (confirmed impressions)', data: arms.map((a) => a.billedRevenueMicros), color: '#c8963e' },
          ]}
        />
      </Section>
      <Section title="Reading this chart">
        <p className="text-sm text-slate-400">
          Traffic is split deterministically between first-price and generalized second-price (GSP). With a bid-shading-free
          advertiser population, first-price extracts more revenue per auction in the short run; GSP is incentive compatible, which is why
          advertisers can bid their true value. “Awarded” is booked at auction time; “billed” only counts de-duplicated, signed impression
          events, so the gap between them is the share of wins that were never rendered.
        </p>
      </Section>
    </div>
  )
}
