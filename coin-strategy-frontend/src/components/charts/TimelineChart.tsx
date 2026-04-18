import ReactECharts from 'echarts-for-react'
import type { EChartsOption } from 'echarts'
import type { DailyReport } from '@/types/attribution'
import { formatMoney } from '@/utils/format'

interface TimelineChartProps {
  data: DailyReport[]
}

export function TimelineChart({ data }: TimelineChartProps) {
  if (data.length === 0) {
    return <div className="empty-state">暂无收益时间线数据</div>
  }

  const sorted = [...data].sort((a, b) => a.reportDate.localeCompare(b.reportDate))
  const dates = sorted.map((item) => item.reportDate)
  const cumulative: number[] = []
  let running = 0
  sorted.forEach((item) => {
    running += item.netPnlUsdc || 0
    cumulative.push(Number(running.toFixed(2)))
  })

  const option: EChartsOption = {
    backgroundColor: 'transparent',
    grid: { left: 56, right: 20, top: 28, bottom: 38 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(4, 16, 6, 0.95)',
      borderColor: 'rgba(0, 255, 65, 0.35)',
      textStyle: { color: '#c0f0c0', fontFamily: 'monospace', fontSize: 12 },
      valueFormatter: (value) => formatMoney(Number(value || 0)),
    },
    xAxis: {
      type: 'category',
      data: dates,
      boundaryGap: false,
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
        name: '累计收益',
        type: 'line',
        smooth: true,
        data: cumulative,
        areaStyle: {
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(0, 255, 65, 0.22)' },
              { offset: 1, color: 'rgba(0, 255, 65, 0.01)' },
            ],
          },
        },
        lineStyle: { width: 2, color: '#00ff41', shadowBlur: 8, shadowColor: 'rgba(0, 255, 65, 0.60)' },
        itemStyle: { color: '#00ff41', borderColor: '#020904', borderWidth: 2 },
        symbol: 'circle',
        symbolSize: 6,
      },
    ],
  }

  return <ReactECharts option={option} style={{ height: 320, width: '100%' }} />
}
