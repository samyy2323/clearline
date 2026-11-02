import ReactECharts from 'echarts-for-react'
import * as echarts from 'echarts/core'
import { ReactNode } from 'react'
import { cssRgb, useTheme } from '../theme'

/** Series colours that read well on both the light and dark theme. */
export const PALETTE = { green: '#1d8f6e', gold: '#c8963e', blue: '#4f7cac', rose: '#d1586a', muted: '#8d8a80' }

export function Stat({ label, value, sub, tone }: { label: string; value: ReactNode; sub?: ReactNode; tone?: 'good' | 'warn' | 'bad' }) {
  const color = tone === 'bad' ? 'text-red-400' : tone === 'warn' ? 'text-amber-400' : tone === 'good' ? 'text-emerald-400' : 'text-slate-100'
  return (
    <div className="card reveal">
      <div className="label">{label}</div>
      <div className={`font-display text-[30px] font-semibold leading-none tracking-[-0.02em] num ${color}`}>{value}</div>
      {sub && <div className="mt-2.5 text-xs text-slate-400">{sub}</div>}
    </div>
  )
}

export function Section({ title, right, children }: { title: string; right?: ReactNode; children: ReactNode }) {
  return (
    <section className="card reveal">
      <div className="mb-5 flex items-center justify-between gap-3">
        <h2 className="font-display text-[15px] font-semibold tracking-[-0.01em] text-slate-100">{title}</h2>
        {right}
      </div>
      {children}
    </section>
  )
}

function useChartTheme() {
  useTheme()   // subscribe, so charts recompute colours when the theme flips
  return {
    text: cssRgb('--fg5'),
    grid: cssRgb('--line'),
    tipBg: cssRgb('--panel'),
    tipText: cssRgb('--fg1'),
    legend: cssRgb('--fg4'),
  }
}

function baseOption(c: ReturnType<typeof useChartTheme>) {
  return {
    backgroundColor: 'transparent',
    textStyle: { color: c.text, fontFamily: 'DM Sans Variable, sans-serif' },
    grid: { left: 52, right: 12, top: 38, bottom: 26 },
    tooltip: {
      trigger: 'axis', backgroundColor: c.tipBg, borderColor: c.grid, borderWidth: 1,
      textStyle: { color: c.tipText, fontSize: 12 }, extraCssText: 'border-radius:12px;box-shadow:0 12px 30px -10px rgba(0,0,0,.3);',
    },
    legend: { textStyle: { color: c.legend, fontSize: 11 }, top: 0, right: 0, itemWidth: 12, itemHeight: 3, icon: 'roundRect' },
  }
}

export function LineChart({ x, series, yFormatter, height = 240, yName }: {
  x: (string | number)[]
  series: { name: string; data: (number | null)[]; color?: string; area?: boolean; dashed?: boolean }[]
  yFormatter?: (v: number) => string
  height?: number
  yName?: string
}) {
  const c = useChartTheme()
  const option = {
    ...baseOption(c),
    animation: false,
    xAxis: { type: 'category', data: x, axisLine: { lineStyle: { color: c.grid } }, axisTick: { show: false }, axisLabel: { color: c.text, hideOverlap: true } },
    yAxis: { type: 'value', name: yName, nameTextStyle: { color: c.text }, axisLine: { show: false }, splitLine: { lineStyle: { color: c.grid } }, axisLabel: { color: c.text, formatter: yFormatter } },
    series: series.map((s) => ({
      name: s.name, type: 'line', showSymbol: false, data: s.data, smooth: 0.3,
      lineStyle: { width: s.dashed ? 1.6 : 2.4, type: s.dashed ? 'dashed' : 'solid', color: s.color },
      itemStyle: { color: s.color },
      areaStyle: s.area ? { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: `${s.color}38` }, { offset: 1, color: `${s.color}00` }]) } : undefined,
    })),
  }
  return <ReactECharts option={option} style={{ height }} notMerge />
}

export function BarChart({ x, series, height = 240, yFormatter }: {
  x: string[]; series: { name: string; data: number[]; color?: string }[]; height?: number; yFormatter?: (v: number) => string
}) {
  const c = useChartTheme()
  const option = {
    ...baseOption(c),
    animation: false,
    xAxis: { type: 'category', data: x, axisLine: { lineStyle: { color: c.grid } }, axisTick: { show: false }, axisLabel: { color: c.text } },
    yAxis: { type: 'value', axisLine: { show: false }, splitLine: { lineStyle: { color: c.grid } }, axisLabel: { color: c.text, formatter: yFormatter } },
    series: series.map((s) => ({ name: s.name, type: 'bar', barMaxWidth: 40, data: s.data, itemStyle: { color: s.color, borderRadius: [8, 8, 0, 0] } })),
  }
  return <ReactECharts option={option} style={{ height }} notMerge />
}

export const timeLabel = (t: number) => new Date(t).toLocaleTimeString([], { hour12: false })
export const simClock = (ms: number) => new Date(ms).toISOString().slice(11, 16) + ' UTC'
