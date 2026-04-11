import ReactECharts from 'echarts-for-react'
import type { EChartsOption } from 'echarts'
import type { DailyReport } from '@/types/attribution'

interface WinRateTrendProps {
  data: DailyReport[]
}

export function WinRateTrend({ data }: WinRateTrendProps) {
  const sorted = [...data].sort((a, b) => a.reportDate.localeCompare(b.reportDate))
  const dates = sorted.map((item) => item.reportDate)
  const winRates = sorted.map((item) => Number(((item.winRate || 0) * 100).toFixed(2)))

  const option: EChartsOption = {
    grid: { left: 48, right: 20, top: 24, bottom: 40 },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: dates,
      axisLabel: { hideOverlap: true },
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 100,
      axisLabel: { formatter: (value: number) => `${value.toFixed(0)}%` },
    },
    series: [
      {
        name: '胜率',
        type: 'line',
        smooth: true,
        data: winRates,
        lineStyle: { width: 3, color: '#1f7a4d' },
        itemStyle: { color: '#1f7a4d' },
      },
    ],
  }

  return <ReactECharts option={option} style={{ height: 300, width: '100%' }} />
}