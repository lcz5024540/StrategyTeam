import ReactECharts from 'echarts-for-react'
import type { EChartsOption } from 'echarts'
import type { DailyReport } from '@/types/attribution'

interface DailyPnlBarsProps {
  data: DailyReport[]
}

export function DailyPnlBars({ data }: DailyPnlBarsProps) {
  if (data.length === 0) {
    return <div className="empty-state">暂无日收益数据</div>
  }

  const sorted = [...data].sort((a, b) => a.reportDate.localeCompare(b.reportDate))
  const dates = sorted.map((item) => item.reportDate)
  const pnl = sorted.map((item) => Number((item.netPnlUsdc || 0).toFixed(2)))

  const option: EChartsOption = {
    backgroundColor: 'transparent',
    grid: { left: 56, right: 20, top: 24, bottom: 40 },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(0, 255, 65, 0.05)' } },
      backgroundColor: 'rgba(4, 16, 6, 0.95)',
      borderColor: 'rgba(0, 255, 65, 0.35)',
      textStyle: { color: '#c0f0c0', fontFamily: 'monospace', fontSize: 12 },
    },
    xAxis: {
      type: 'category',
      data: dates,
      axisLabel: { hideOverlap: true, color: '#3a6642', fontFamily: 'monospace', fontSize: 11 },
      axisLine: { lineStyle: { color: 'rgba(0, 255, 65, 0.20)' } },
      splitLine: { lineStyle: { color: 'rgba(0, 255, 65, 0.06)' } },
    },
    yAxis: {
      type: 'value',
      axisLabel: {
        formatter: (value: number) => `$${value.toFixed(0)}`,
        color: '#3a6642',
        fontFamily: 'monospace',
        fontSize: 11,
      },
      axisLine: { lineStyle: { color: 'rgba(0, 255, 65, 0.20)' } },
      splitLine: { lineStyle: { color: 'rgba(0, 255, 65, 0.06)' } },
    },
    series: [
      {
        name: '日收益',
        type: 'bar',
        data: pnl,
        barMaxWidth: 32,
        itemStyle: {
          color: (params) => Number(params.value) >= 0
            ? { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: '#00ff41' }, { offset: 1, color: '#00cc33' }] }
            : { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: '#ff2244' }, { offset: 1, color: '#cc1133' }] },
          borderRadius: [3, 3, 0, 0],
        },
      },
    ],
  }

  return <ReactECharts option={option} style={{ height: 300, width: '100%' }} />
}
