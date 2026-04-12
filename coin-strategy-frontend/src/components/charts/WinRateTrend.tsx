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
    backgroundColor: 'transparent',
    grid: { left: 56, right: 20, top: 24, bottom: 40 },
    tooltip: {
      trigger: 'axis',
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
      min: 0,
      max: 100,
      axisLabel: {
        formatter: (value: number) => `${value.toFixed(0)}%`,
        color: '#3a6642',
        fontFamily: 'monospace',
        fontSize: 11,
      },
      axisLine: { lineStyle: { color: 'rgba(0, 255, 65, 0.20)' } },
      splitLine: { lineStyle: { color: 'rgba(0, 255, 65, 0.06)' } },
    },
    series: [
      {
        name: '胜率',
        type: 'line',
        smooth: true,
        data: winRates,
        lineStyle: { width: 2, color: '#00ff41', shadowBlur: 8, shadowColor: 'rgba(0, 255, 65, 0.60)' },
        itemStyle: { color: '#00ff41', borderColor: '#020904', borderWidth: 2 },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(0, 255, 65, 0.18)' },
              { offset: 1, color: 'rgba(0, 255, 65, 0.01)' },
            ],
          },
        },
        markLine: {
          silent: true,
          symbol: 'none',
          data: [{ yAxis: 50 }],
          lineStyle: { color: 'rgba(255, 170, 0, 0.30)', type: 'dashed', width: 1 },
          label: { formatter: '50%', color: '#ffaa00', fontFamily: 'monospace', fontSize: 10 },
        },
      },
    ],
  }

  return <ReactECharts option={option} style={{ height: 300, width: '100%' }} />
}