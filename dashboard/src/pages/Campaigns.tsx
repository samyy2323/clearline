import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, getSession, toMicros, usd } from '../api'
import { Section } from '../components/ui'

type Campaign = {
  id: string; advertiserId: string; name: string; bidCpmMicros: number; budgetMicros: number
  geos: string[]; devices: string[]; segments: string[]; categories: string[]; hours: number[]; sizes: string[]
  freqCap: number; startMs: number; endMs: number; active: boolean
}

type Form = {
  name: string; bid: string; budget: string; geos: string; devices: string; segments: string; categories: string
  hours: string; sizes: string; freqCap: string; start: string; end: string; active: boolean; advertiserId: string
}

const csv = (s: string) => s.split(',').map((x) => x.trim()).filter(Boolean)
const local = (ms: number) => new Date(ms - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16)

function toForm(c?: Campaign): Form {
  const now = Date.now()
  return {
    name: c?.name ?? '', bid: c ? String(c.bidCpmMicros / 1e6) : '2', budget: c ? String(c.budgetMicros / 1e6) : '100',
    geos: c?.geos.join(', ') ?? '', devices: c?.devices.join(', ') ?? '', segments: c?.segments.join(', ') ?? '',
    categories: c?.categories.join(', ') ?? '', hours: c?.hours.join(', ') ?? '', sizes: c?.sizes.join(', ') ?? '',
    freqCap: String(c?.freqCap ?? 0), start: local(c?.startMs ?? now), end: local(c?.endMs ?? now + 7 * 86400000),
    active: c?.active ?? true, advertiserId: c?.advertiserId ?? 'adv-1',
  }
}

function toBody(f: Form) {
  return {
    name: f.name, advertiserId: f.advertiserId, bidCpmMicros: toMicros(Number(f.bid)), budgetMicros: toMicros(Number(f.budget)),
    geos: csv(f.geos).map((g) => g.toUpperCase()), devices: csv(f.devices), segments: csv(f.segments), categories: csv(f.categories),
    hours: csv(f.hours).map(Number), sizes: csv(f.sizes), freqCap: Number(f.freqCap) || 0,
    startMs: new Date(f.start).getTime(), endMs: new Date(f.end).getTime(), active: f.active,
  }
}

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return <div><label className="label">{label}</label>{children}{hint && <div className="mt-0.5 text-xs text-slate-500">{hint}</div>}</div>
}

function Editor({ initial, id, onClose }: { initial?: Campaign; id?: string; onClose: () => void }) {
  const qc = useQueryClient()
  const admin = getSession()?.role === 'admin'
  const [f, setF] = useState<Form>(toForm(initial))
  const set = (k: keyof Form) => (e: any) => setF({ ...f, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value })
  const save = useMutation({
    mutationFn: () => api(id ? `/api/campaigns/${id}` : '/api/campaigns', { method: id ? 'PUT' : 'POST', body: JSON.stringify(toBody(f)) }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['campaigns'] }); onClose() },
  })
  return (
    <Section title={id ? 'Edit campaign' : 'New campaign'}>
      <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
        <Field label="Name"><input className="input" value={f.name} onChange={set('name')} /></Field>
        <Field label="Max bid (USD CPM)"><input className="input" type="number" step="0.01" value={f.bid} onChange={set('bid')} /></Field>
        <Field label="Total budget (USD)"><input className="input" type="number" step="1" value={f.budget} onChange={set('budget')} /></Field>
        <Field label="Geos" hint="comma separated, e.g. IN, US (empty = all)"><input className="input" value={f.geos} onChange={set('geos')} /></Field>
        <Field label="Devices" hint="mobile, desktop, tablet, ctv"><input className="input" value={f.devices} onChange={set('devices')} /></Field>
        <Field label="Segments" hint="e.g. sports, tech"><input className="input" value={f.segments} onChange={set('segments')} /></Field>
        <Field label="Site categories"><input className="input" value={f.categories} onChange={set('categories')} /></Field>
        <Field label="Hours (UTC)" hint="0-23, e.g. 18, 19, 20 (empty = all day)"><input className="input" value={f.hours} onChange={set('hours')} /></Field>
        <Field label="Creative sizes" hint="e.g. 300x250, 728x90 (empty = any)"><input className="input" value={f.sizes} onChange={set('sizes')} /></Field>
        <Field label="Frequency cap / user / 24h" hint="0 = uncapped"><input className="input" type="number" value={f.freqCap} onChange={set('freqCap')} /></Field>
        <Field label="Flight start"><input className="input" type="datetime-local" value={f.start} onChange={set('start')} /></Field>
        <Field label="Flight end"><input className="input" type="datetime-local" value={f.end} onChange={set('end')} /></Field>
        {admin && !id && <Field label="Advertiser id"><input className="input" value={f.advertiserId} onChange={set('advertiserId')} /></Field>}
        <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={f.active} onChange={set('active')} /> Active</label>
      </div>
      {save.isError && <div className="mt-3 text-sm text-red-400">{(save.error as Error).message}</div>}
      <div className="mt-4 flex gap-2">
        <button className="btn" disabled={save.isPending || !f.name} onClick={() => save.mutate()}>{save.isPending ? 'Saving…' : 'Save'}</button>
        <button className="btn-ghost" onClick={onClose}>Cancel</button>
      </div>
      <p className="mt-2 text-xs text-slate-500">Changes go through the transactional outbox and reach the in-memory index within about a second.</p>
    </Section>
  )
}

export default function Campaigns() {
  const qc = useQueryClient()
  const list = useQuery({ queryKey: ['campaigns'], queryFn: () => api<Campaign[]>('/api/campaigns') })
  const [editing, setEditing] = useState<Campaign | 'new' | null>(null)
  const del = useMutation({
    mutationFn: (id: string) => api(`/api/campaigns/${id}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['campaigns'] }),
  })

  return (
    <div className="space-y-4">
      {editing && <Editor key={editing === 'new' ? 'new' : editing.id} initial={editing === 'new' ? undefined : editing} id={editing === 'new' ? undefined : editing.id} onClose={() => setEditing(null)} />}
      <Section title={`Campaigns (${list.data?.length ?? 0})`} right={<button className="btn" onClick={() => setEditing('new')}>New campaign</button>}>
        {list.isLoading && <div className="text-slate-400">Loading…</div>}
        {list.isError && <div className="text-red-400">{(list.error as Error).message}</div>}
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead><tr><th>Name</th><th>Advertiser</th><th>Bid CPM</th><th>Budget</th><th>Targeting</th><th>Cap</th><th>Status</th><th /></tr></thead>
            <tbody>
              {list.data?.map((c) => (
                <tr key={c.id} className="border-t border-line">
                  <td>{c.name}</td>
                  <td>{c.advertiserId}</td>
                  <td className="tabular-nums">{usd(c.bidCpmMicros)}</td>
                  <td className="tabular-nums">{usd(c.budgetMicros)}</td>
                  <td className="max-w-xs truncate text-xs text-slate-400">
                    {[c.geos.join('/') || 'all geos', c.devices.join('/') || 'all devices', c.segments.length ? `${c.segments.length} segments` : 'all segments', c.hours.length ? `${c.hours.length}h daypart` : '24h'].join(' · ')}
                  </td>
                  <td>{c.freqCap || '—'}</td>
                  <td>{c.active ? <span className="text-emerald-400">active</span> : <span className="text-slate-500">paused</span>}</td>
                  <td className="whitespace-nowrap text-right">
                    <button className="btn-ghost mr-2" onClick={() => setEditing(c)}>Edit</button>
                    <button className="btn-ghost" onClick={() => confirm(`Delete "${c.name}"?`) && del.mutate(c.id)}>Delete</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>
    </div>
  )
}
