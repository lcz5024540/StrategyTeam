import ReactECharts from 'echarts-for-react'
import type { EChartsOption } from 'echarts'
import type { DailyReport } from '@/types/attribution'

interface DailyPnlBarsProps {
  data: DailyReport[]
}

export function DailyPnlBars({ data }: DailyPnlBarsProps) {
  const sorted = [...data].sort((a, b) => a.reportDate.localeCompare(b.reportDate))
  const dates = sorted.map((item) => item.reportDate)
  const pnl = sorted.map((item) => Number((item.netPnlUsdc || 0).toFixed(2)))

  const option: EChartsOption = {
    grid: { left: 48, right: 20, top: 24, bottom: 40 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: {
      type: 'category',
      data: dates,
      axisLabel: { hideOverlap: true },
    },
    yAxis: {
      type: 'value',
      axisLabel: { formatter: (value: number) => `$${value.toFixed(0)}` },
    },
    series: [
      {
        name: '日收益',
        type: 'bar',
        data: pnl,
        itemStyle: {
          color: (params) => (Number(params.value) >= 0 ? '#1f7a4d' : '#b33939'),
        },
      },
    ],
  }

  return <ReactECharts option={option} style={{ height: 300, width: '100%' }} />
}