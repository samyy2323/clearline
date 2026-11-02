import { useEffect, useMemo, useState } from 'react'
import { useLive } from '../live'
import { Auction, AuctionSlot, Bidder, useAuctionPlayer } from '../auctionFeed'
import { AdCreative, brandOf } from '../brands'

const COUNTRY: Record<string, string> = { IN: 'India', US: 'United States', GB: 'United Kingdom', DE: 'Germany', BR: 'Brazil', ID: 'Indonesia', JP: 'Japan', FR: 'France' }
const STORY: Record<string, { kicker: string; head: string; dek: string }> = {
  news: { kicker: 'World', head: 'Markets steady as leaders meet for a second day of talks', dek: 'Analysts expect a quiet close after a volatile week.' },
  sports: { kicker: 'Sport', head: 'Late surge lifts the home side in a tense league decider', dek: 'A first-half injury changed the shape of the match.' },
  tech: { kicker: 'Technology', head: 'The next wave of phones leans on on-device intelligence', dek: 'Chipmakers say battery life is the real battleground.' },
  finance: { kicker: 'Money', head: 'What a change in lending rates means for first-time buyers', dek: 'Fixed and floating loans are pulling in different directions.' },
  entertainment: { kicker: 'Culture', head: 'The streaming releases everyone will be talking about this month', dek: 'From slow-burn dramas to a festival favourite.' },
  lifestyle: { kicker: 'Life', head: 'Small changes to your morning routine that actually stick', dek: 'Researchers followed two thousand people for a year.' },
  travel: { kicker: 'Travel', head: 'Ten quiet coastal towns worth a long weekend', dek: 'Fewer crowds, better food, and trains that run on time.' },
  gaming: { kicker: 'Games', head: 'A surprise sequel tops the charts in its opening week', dek: 'Players praise the new movement system.' },
  food: { kicker: 'Food', head: 'The street-food stalls that cooks queue up for', dek: 'A guide to the best late-night plates in the city.' },
  auto: { kicker: 'Motoring', head: 'Electric SUVs are getting cheaper, and better at long trips', dek: 'Charging networks are finally catching up.' },
  health: { kicker: 'Health', head: 'How much exercise is enough? New guidance shifts the numbers', dek: 'Less than you think, but more often.' },
  education: { kicker: 'Learning', head: 'Why more students are choosing short courses over degrees', dek: 'Employers say skills now matter more than titles.' },
}
const OUTCOME: Record<string, string> = {
  WON: 'Won', OUTBID: 'Outbid', BELOW_FLOOR: 'Bid below the publisher floor', SIZE_MISMATCH: 'No creative for this slot size',
  FREQUENCY_CAP: 'User has seen it too often', PACING: 'Throttled to pace the budget', NO_BUDGET: 'Out of budget', WON_OTHER_SLOT: 'Won another slot on this page',
}
const usd = (micros: number, d = 2) => `$${(micros / 1e6).toFixed(d)}`
const keyOf = (s: { w: number; h: number }) => `${s.w}x${s.h}`

interface SlotState { auction: Auction; slot: AuctionSlot; n: number }

function SlotFrame({ label, w, h, state, maxWidth }: { label: string; w: number; h: number; state?: SlotState; maxWidth: number }) {
  const winner = state?.slot.bidders.find((b) => b.campaignId === state.slot.winnerCampaign)
  const brand = winner ? brandOf(winner.campaignName).brand : null
  return (
    <figure style={{ maxWidth, margin: '0 auto', width: '100%' }}>
      <div className={state ? 'slot-flash rounded-md' : 'rounded-md'} key={state?.auction.id ?? 'empty'}>
        {state && winner ? (
          <div className="ad-in"><AdCreative campaignName={winner.campaignName} w={w} h={h} /></div>
        ) : (
          <div className="flex items-center justify-center rounded-md border border-dashed border-line bg-panel2 text-[11px] text-slate-500" style={{ aspectRatio: `${w} / ${h}` }}>
            waiting for an auction
          </div>
        )}
      </div>
      <figcaption className="mt-1.5 flex items-center justify-between gap-2 text-[10.5px] text-slate-400">
        <span>{label}</span>
        {state && winner ? (
          <span className="flex items-center gap-1.5 text-slate-300">
            <i className="inline-block h-1.5 w-1.5 rounded-full" style={{ background: brand?.dot }} />
            {brand?.key} paid {usd(state.slot.priceCpmMicros)} CPM · {state.slot.bidders.length} bidders
          </span>
        ) : <span>no winner yet</span>}
      </figcaption>
    </figure>
  )
}

function Ladder({ slot, arm }: { slot: AuctionSlot; arm: string }) {
  const inAuction = slot.bidders.filter((b) => b.outcome === 'WON' || b.outcome === 'OUTBID')
  const filtered = slot.bidders.filter((b) => b.outcome !== 'WON' && b.outcome !== 'OUTBID')
  const max = Math.max(...inAuction.map((b) => b.ecpm), 1e-9)
  const winner = slot.bidders.find((b) => b.campaignId === slot.winnerCampaign)
  const runnerUp = inAuction.find((b) => b.outcome === 'OUTBID')
  return (
    <div>
      <div className="label">In the auction ({inAuction.length})</div>
      <div className="space-y-1.5">
        {inAuction.map((b: Bidder, i) => {
          const { brand, title } = brandOf(b.campaignName)
          const won = b.outcome === 'WON'
          return (
            <div key={b.campaignId} className="bid-row relative overflow-hidden rounded-xl border px-3 py-2.5" style={{ animationDelay: `${i * 70}ms`, borderColor: won ? brand.dot : 'rgb(var(--line))' }}>
              <div className="bar-grow absolute inset-y-0 left-0" style={{ width: `${Math.max(4, (b.ecpm / max) * 100)}%`, background: brand.dot, opacity: won ? 0.22 : 0.1, animationDelay: `${i * 70}ms` }} />
              <div className="relative flex items-center gap-3">
                <i className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: brand.dot }} />
                <div className="min-w-0 flex-1">
                  <div className="truncate text-[13px] font-semibold text-slate-100">{brand.key} <span className="font-normal text-slate-400">· {title}</span></div>
                  <div className="num text-[11px] text-slate-400">
                    bids {usd(b.bidCpmMicros)} × predicted CTR {(b.pctr * 100).toFixed(2)}% = <span className="font-semibold text-slate-200">score {(b.ecpm / 1e6).toFixed(3)}</span>
                  </div>
                </div>
                <span className={`shrink-0 rounded-full px-2.5 py-1 text-[10.5px] font-semibold ${won ? 'bg-accent text-[rgb(var(--accent-ink))]' : 'border border-line text-slate-400'}`}>{won ? 'Winner' : 'Outbid'}</span>
              </div>
            </div>
          )
        })}
        {inAuction.length === 0 && <div className="rounded-xl border border-dashed border-line px-3 py-4 text-center text-xs text-slate-400">Nobody was eligible for this slot.</div>}
      </div>

      <div className="mt-3 rounded-xl bg-panel2 px-4 py-3 text-[12.5px] leading-relaxed text-slate-300">
        {winner ? (
          <>
            <b className="text-slate-100">{brandOf(winner.campaignName).brand.key}</b> wins and pays <b className="text-slate-100">{usd(slot.priceCpmMicros)}</b> per 1,000 impressions
            {arm === 'gsp'
              ? runnerUp
                ? <> (second-price: just enough to beat {brandOf(runnerUp.campaignName).brand.key}'s score, not its own {usd(winner.bidCpmMicros)} bid).</>
                : <> (second-price with no rival: it pays the publisher's floor).</>
              : <> (first-price: it pays exactly what it bid).</>}
          </>
        ) : 'No eligible bidder won this slot.'}
        <span className="text-slate-500"> Publisher floor {usd(slot.floorCpmMicros)}.</span>
      </div>

      {filtered.length > 0 && (
        <div className="mt-4">
          <div className="label">Filtered out before bidding ({filtered.length})</div>
          <ul className="divide-y divide-line rounded-xl border border-line">
            {filtered.map((b) => {
              const { brand, title } = brandOf(b.campaignName)
              return (
                <li key={b.campaignId} className="flex items-center gap-2.5 px-3 py-2 text-[12px]">
                  <i className="h-2 w-2 shrink-0 rounded-full opacity-60" style={{ background: brand.dot }} />
                  <span className="min-w-0 flex-1 truncate text-slate-300">{brand.key} <span className="text-slate-500">· {title}</span></span>
                  <span className="shrink-0 text-slate-400">{OUTCOME[b.outcome] ?? b.outcome}</span>
                </li>
              )
            })}
          </ul>
        </div>
      )}
    </div>
  )
}

const STEPS = [
  ['A page loads', 'The publisher asks the exchange to fill each ad slot, with what it knows about the visitor.'],
  ['Who is eligible', 'Targeting, flight dates, frequency caps and budget filter hundreds of campaigns in memory.'],
  ['Score the bids', 'Each bid is multiplied by the predicted click-through rate, so a modest bid on a likely click can win.'],
  ['Winner pays, ad shows', 'The price is set by the pricing rule, budget is spent from a local lease, the creative is returned. All in a few milliseconds.'],
]

export default function AuctionPage() {
  const { snap } = useLive()
  const [paused, setPaused] = useState(false)
  const [interval, setIntervalMs] = useState(1800)
  const { current, history, connected, step, queued } = useAuctionPlayer(interval, paused)
  const [slots, setSlots] = useState<Record<string, SlotState>>({})
  const [focus, setFocus] = useState<string | null>(null)
  const [count, setCount] = useState(0)

  useEffect(() => {
    if (!current) return
    setCount((n) => n + 1)
    setSlots((prev) => {
      const next = { ...prev }
      current.slots.forEach((s) => { if (s.winnerCampaign) next[keyOf(s)] = { auction: current, slot: s, n: count } })
      return next
    })
    const first = current.slots.find((s) => s.winnerCampaign) ?? current.slots[0]
    if (first) setFocus(keyOf(first))
  }, [current])   // eslint-disable-line react-hooks/exhaustive-deps

  const focusSlot = useMemo(() => current?.slots.find((s) => keyOf(s) === focus) ?? current?.slots[0], [current, focus])
  const story = STORY[current?.category ?? 'news'] ?? STORY.news

  return (
    <div className="space-y-6">
      <div className="card reveal">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div className="max-w-2xl">
            <div className="label !mb-2">How an ad gets onto a page</div>
            <h2 className="font-display text-[26px] font-semibold leading-tight tracking-[-0.02em] text-slate-100">Every page view runs a real-time auction.</h2>
            <p className="mt-2 text-sm leading-relaxed text-slate-400">
              What you see below is the live exchange: genuine auctions sampled from the engine a few times a second and replayed at a pace you can follow.
              Watch the slots change as different advertisers win.
            </p>
          </div>
          <div className="flex gap-6 text-right">
            <div><div className="label !mb-1">Auctions / second</div><div className="num font-display text-2xl font-semibold text-slate-100">{snap ? snap.rps.toLocaleString() : '-'}</div></div>
            <div><div className="label !mb-1">Engine p99</div><div className="num font-display text-2xl font-semibold text-slate-100">{snap ? `${snap.latencyMs.p99.toFixed(2)} ms` : '-'}</div></div>
          </div>
        </div>
        <ol className="mt-6 grid gap-3 md:grid-cols-4">
          {STEPS.map(([t, d], i) => (
            <li key={t} className="rounded-xl border border-line bg-panel2 p-4">
              <div className="mb-2 flex h-6 w-6 items-center justify-center rounded-full bg-accent text-[11px] font-bold text-[rgb(var(--accent-ink))]">{i + 1}</div>
              <div className="text-[13px] font-semibold text-slate-100">{t}</div>
              <div className="mt-1 text-xs leading-relaxed text-slate-400">{d}</div>
            </li>
          ))}
        </ol>
      </div>

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1.45fr)_minmax(0,1fr)]">
        <section className="card reveal !p-0 overflow-hidden">
          <div className="flex items-center gap-3 border-b border-line bg-panel2 px-4 py-3">
            <span className="flex gap-1.5"><i className="h-2.5 w-2.5 rounded-full bg-slate-500/40" /><i className="h-2.5 w-2.5 rounded-full bg-slate-500/40" /><i className="h-2.5 w-2.5 rounded-full bg-slate-500/40" /></span>
            <div className="flex-1 truncate rounded-full border border-line bg-panel px-4 py-1.5 text-[11.5px] text-slate-400">northline.news / {story.kicker.toLowerCase()} / {current ? current.id.slice(0, 14) : 'live'}</div>
            <span className="flex items-center gap-1.5 text-[11px] font-semibold text-slate-300"><i className={`live-dot h-2 w-2 rounded-full ${connected ? 'bg-emerald-400' : 'bg-red-500'}`} />{connected ? 'live' : 'offline'}</span>
          </div>
          <div className="p-6">
            <div className="flex items-baseline justify-between border-b border-line pb-3">
              <div className="font-display text-[22px] font-bold tracking-[-0.03em] text-slate-100">Northline Daily</div>
              <div className="hidden gap-4 text-xs text-slate-400 sm:flex"><span>World</span><span>Sport</span><span>Tech</span><span>Money</span><span>Travel</span></div>
            </div>
            <div className="py-4"><SlotFrame label="728 × 90 · leaderboard" w={728} h={90} state={slots['728x90']} maxWidth={728} /></div>
            <div className="grid gap-6 md:grid-cols-[minmax(0,1fr)_300px]">
              <article>
                <div className="text-[11px] font-bold uppercase tracking-[0.12em] text-accent">{story.kicker}</div>
                <h3 className="mt-1 font-display text-[24px] font-bold leading-tight tracking-[-0.025em] text-slate-100">{story.head}</h3>
                <p className="mt-2 text-sm text-slate-400">{story.dek}</p>
                <div className="mt-4 space-y-2">{[96, 100, 88, 94].map((w, i) => <div key={i} className="h-2 rounded-full bg-line" style={{ width: `${w}%` }} />)}</div>
                <div className="my-5"><SlotFrame label="320 × 50 · mobile banner" w={320} h={50} state={slots['320x50']} maxWidth={320} /></div>
                <div className="space-y-2">{[100, 92, 97, 60].map((w, i) => <div key={i} className="h-2 rounded-full bg-line" style={{ width: `${w}%` }} />)}</div>
              </article>
              <aside className="space-y-6">
                <SlotFrame label="300 × 250 · rectangle" w={300} h={250} state={slots['300x250']} maxWidth={300} />
                <SlotFrame label="160 × 600 · skyscraper" w={160} h={600} state={slots['160x600']} maxWidth={160} />
              </aside>
            </div>
          </div>
        </section>

        <div className="space-y-6 max-xl:order-first">
          <section className="card reveal">
            <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
              <h2 className="font-display text-[15px] font-semibold tracking-[-0.01em] text-slate-100">The auction right now</h2>
              {current && <span className="chip">{current.arm === 'gsp' ? 'Second-price (GSP)' : 'First-price'}</span>}
            </div>
            {!current || !focusSlot ? (
              <div className="py-10 text-center text-sm text-slate-400">Waiting for the first auction. Start traffic from the Simulator page if nothing appears.</div>
            ) : (
              <>
                <div className="mb-3 flex flex-wrap gap-1.5">
                  <span className="chip">{COUNTRY[current.geo] ?? current.geo}</span>
                  <span className="chip">{current.device}</span>
                  <span className="chip">reading: {current.category}</span>
                  {current.segments.map((s) => <span key={s} className="chip">interest: {s}</span>)}
                </div>
                {current.slots.length > 1 && (
                  <div className="mb-3 flex gap-1.5">
                    {current.slots.map((s) => (
                      <button key={s.id} className={keyOf(s) === focus ? 'btn !px-3 !py-1.5 !text-xs' : 'btn-ghost !px-3 !py-1.5 !text-xs'} onClick={() => setFocus(keyOf(s))}>{s.w} × {s.h}</button>
                    ))}
                  </div>
                )}
                <div className="mb-3 text-xs text-slate-400">
                  Slot {focusSlot.w} × {focusSlot.h} · decided in <span className="num font-semibold text-slate-200">{(current.tookMicros / 1000).toFixed(2)} ms</span> · {focusSlot.bidders.length} eligible bidders shown
                </div>
                <Ladder key={current.id + focusSlot.id} slot={focusSlot} arm={current.arm} />
              </>
            )}
            <div className="mt-5 flex flex-wrap items-center gap-2 border-t border-line pt-4">
              <button className="btn !px-4 !py-2" onClick={() => setPaused((p) => !p)}>{paused ? 'Resume' : 'Pause'}</button>
              <button className="btn-ghost" onClick={step}>Next auction</button>
              <div className="ml-auto flex items-center gap-1 rounded-full border border-line p-1 text-xs">
                {[['Slow', 3200], ['Normal', 1800], ['Fast', 900]].map(([l, ms]) => (
                  <button key={l} onClick={() => setIntervalMs(ms as number)} className={`rounded-full px-3 py-1 font-medium transition ${interval === ms ? 'bg-accent text-[rgb(var(--accent-ink))]' : 'text-slate-400 hover:text-slate-100'}`}>{l}</button>
                ))}
              </div>
            </div>
            <div className="mt-2 text-[11px] text-slate-500">{queued()} auctions waiting in the replay queue. Visitor details are simulated.</div>
          </section>

          <section className="card reveal">
            <h2 className="mb-3 font-display text-[15px] font-semibold tracking-[-0.01em] text-slate-100">Recent auctions</h2>
            <ul className="divide-y divide-line">
              {history.map((a) => {
                const s = a.slots.find((x) => x.winnerCampaign) ?? a.slots[0]
                const w = s?.bidders.find((b) => b.campaignId === s.winnerCampaign)
                const br = w ? brandOf(w.campaignName).brand : null
                return (
                  <li key={a.id} className="ticker-in flex items-center gap-3 py-2.5 text-[13px]">
                    <i className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: br?.dot ?? '#999' }} />
                    <span className="min-w-0 flex-1 truncate font-medium text-slate-100">{br?.key ?? 'No winner'} <span className="font-normal text-slate-400">{w ? brandOf(w.campaignName).title : ''}</span></span>
                    <span className="num text-xs text-slate-400">{s ? `${s.w}×${s.h}` : ''}</span>
                    <span className="num w-14 text-right font-semibold text-slate-200">{s && w ? usd(s.priceCpmMicros) : '-'}</span>
                    <span className="chip !px-2 !py-0.5 !text-[10px]">{a.arm === 'gsp' ? 'GSP' : '1st'}</span>
                  </li>
                )
              })}
              {history.length === 0 && <li className="py-4 text-sm text-slate-400">Nothing yet.</li>}
            </ul>
          </section>
        </div>
      </div>
    </div>
  )
}
