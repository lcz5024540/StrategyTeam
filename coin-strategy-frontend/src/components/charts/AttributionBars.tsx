import ReactECharts from 'echarts-for-react'
import type { EChartsOption } from 'echarts'
import type { Attribution } from '@/types/attribution'

interface AttributionBarsProps {
  data: Attribution[]
  type: 'wallet' | 'market'
}

export function AttributionBars({ data, type }: AttributionBarsProps) {
  if (data.length === 0) {
    return <div className="empty-state">暂无归因数据</div>
  }

  const rows = data.slice(0, 10)
  const labels: string[] = rows.map((item) => {
    if (type === 'wallet') {
      return item.address || item.label || '-'
    }
    return item.question || item.label || item.marketId || '-'
  })
  const pnl = rows.map((item) => Number((item.netPnlUsdc || 0).toFixed(2)))

  const option: EChartsOption = {
    backgroundColor: 'transparent',
    grid: { left: 72, right: 18, top: 20, bottom: 20 },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(0, 255, 65, 0.05)' } },
      backgroundColor: 'rgba(4, 16, 6, 0.95)',
      borderColor: 'rgba(0, 255, 65, 0.35)',
      textStyle: { color: '#c0f0c0', fontFamily: 'monospace', fontSize: 12 },
    },
    xAxis: {
      type: 'value',
      axisLabel: { color: '#3a6642', fontFamily: 'monospace', fontSize: 11 },
      axisLine: { lineStyle: { color: 'rgba(0, 255, 65, 0.20)' } },
      splitLine: { lineStyle: { color: 'rgba(0, 255, 65, 0.06)' } },
    },
    yAxis: {
      type: 'category',
      data: labels,
      axisLabel: {
        formatter: (value: string) => (value.length > 18 ? `${value.slice(0, 18)}…` : value),
        color: '#3a6642',
        fontFamily: 'monospace',
        fontSize: 11,
      },
      axisLine: { lineStyle: { color: 'rgba(0, 255, 65, 0.20)' } },
      splitLine: { show: false },
    },
    series: [
      {
        type: 'bar',
        data: pnl,
        barMaxWidth: 24,
        itemStyle: {
          color: (params) => Number(params.value) >= 0
            ? { type: 'linear', x: 0, y: 0, x2: 1, y2: 0, colorStops: [{ offset: 0, color: '#00cc33' }, { offset: 1, color: '#00ff41' }] }
            : { type: 'linear', x: 0, y: 0, x2: 1, y2: 0, colorStops: [{ offset: 0, color: '#cc1133' }, { offset: 1, color: '#ff2244' }] },
          borderRadius: [0, 3, 3, 0],
        },
      },
    ],
  }

  return <ReactECharts option={option} style={{ height: 320, width: '100%' }} />
}