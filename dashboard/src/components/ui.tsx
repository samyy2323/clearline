import ReactECharts from 'echarts-for-react'
import * as echarts from 'echarts/core'
import { ReactNode } from 'react'

export const PALETTE = { brass: '#c9a45c', sage: '#8fb8a8', steel: '#7d93b2', bad: '#cf6f63', muted: '#8b8f98', grid: '#1a1e24', axis: '#2a2f37' }

export function Stat({ label, value, sub, tone }: { label: string; value: ReactNode; sub?: ReactNode; tone?: 'good' | 'warn' | 'bad' }) {
  const color = tone === 'bad' ? 'text-red-400' : tone === 'warn' ? 'text-amber-400' : tone === 'good' ? 'text-emerald-400' : 'text-slate-100'
  return (
    <div className="card reveal">
      <div className="label">{label}</div>
      <div className={`font-display text-[34px] leading-none tracking-tight num ${color}`}>{value}</div>
      {sub && <div className="mt-2 text-xs text-slate-400">{sub}</div>}
    </div>
  )
}

export function Section({ title, right, children }: { title: string; right?: ReactNode; children: ReactNode }) {
  return (
    <section className="card reveal">
      <div className="mb-4 flex items-center justify-between gap-3">
        <h2 className="text-[11px] font-medium uppercase tracking-[0.14em] text-slate-400">{title}</h2>
        {right}
      </div>
      {children}
    </section>
  )
}

const base = {
  backgroundColor: 'transparent',
  textStyle: { color: PALETTE.muted, fontFamily: 'Inter Tight Variable, sans-serif' },
  grid: { left: 52, right: 12, top: 34, bottom: 26 },
  tooltip: {
    trigger: 'axis',
    backgroundColor: '#14171b',
    borderColor: '#242932',
    textStyle: { color: '#e8e4dc', fontSize: 12 },
    axisPointer: { lineStyle: { color: '#3a414c' } },
  },
  legend: { textStyle: { color: PALETTE.muted, fontSize: 11 }, top: 0, right: 0, itemWidth: 14, itemHeight: 2, icon: 'rect' },
}
const axis = { axisLine: { lineStyle: { color: PALETTE.axis } }, axisTick: { show: false }, splitLine: { lineStyle: { color: PALETTE.grid } } }

export function LineChart({ x, series, yFormatter, height = 240, yName }: {
  x: (string | number)[]
  series: { name: string; data: (number | null)[]; color?: string; area?: boolean; dashed?: boolean }[]
  yFormatter?: (v: number) => string
  height?: number
  yName?: string
}) {
  const option = {
    ...base,
    animation: false,
    xAxis: { type: 'category', data: x, ...axis, axisLabel: { color: '#666a73', hideOverlap: true } },
    yAxis: { type: 'value', name: yName, nameTextStyle: { color: '#666a73' }, ...axis, splitLine: axis.splitLine, axisLine: { show: false }, axisLabel: { color: '#666a73', formatter: yFormatter } },
    series: series.map((s) => ({
      name: s.name, type: 'line', showSymbol: false, data: s.data, smooth: 0.25,
      lineStyle: { width: s.dashed ? 1.5 : 2, type: s.dashed ? 'dashed' : 'solid', color: s.color },
      itemStyle: { color: s.color },
      areaStyle: s.area
        ? { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: `${s.color}40` }, { offset: 1, color: `${s.color}00` }]) }
        : undefined,
    })),
  }
  return <ReactECharts option={option} style={{ height }} notMerge />
}

export function BarChart({ x, series, height = 240, yFormatter }: {
  x: string[]; series: { name: string; data: number[]; color?: string }[]; height?: number; yFormatter?: (v: number) => string
}) {
  const option = {
    ...base,
    animation: false,
    xAxis: { type: 'category', data: x, ...axis, axisLabel: { color: '#8b8f98' } },
    yAxis: { type: 'value', ...axis, axisLine: { show: false }, axisLabel: { color: '#666a73', formatter: yFormatter } },
    series: series.map((s) => ({ name: s.name, type: 'bar', barMaxWidth: 46, data: s.data, itemStyle: { color: s.color, borderRadius: [3, 3, 0, 0] } })),
  }
  return <ReactECharts option={option} style={{ height }} notMerge />
}

export const timeLabel = (t: number) => new Date(t).toLocaleTimeString([], { hour12: false })
export const simClock = (ms: number) => new Date(ms).toISOString().slice(11, 16) + ' UTC'
