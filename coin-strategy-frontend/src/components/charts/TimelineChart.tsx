import ReactECharts from 'echarts-for-react'
import type { EChartsOption } from 'echarts'
import type { DailyReport } from '@/types/attribution'
import { formatMoney } from '@/utils/format'

interface TimelineChartProps {
  data: DailyReport[]
}

export function TimelineChart({ data }: TimelineChartProps) {
  const sorted = [...data].sort((a, b) => a.reportDate.localeCompare(b.reportDate))
  const dates = sorted.map((item) => item.reportDate)
  const cumulative: number[] = []
  let running = 0
  sorted.forEach((item) => {
    running += item.netPnlUsdc || 0
    cumulative.push(Number(running.toFixed(2)))
  })

  const option: EChartsOption = {
    grid: { left: 48, right: 20, top: 28, bottom: 38 },
    tooltip: {
      trigger: 'axis',
      valueFormatter: (value) => formatMoney(Number(value || 0)),
    },
    xAxis: {
      type: 'category',
      data: dates,
      boundaryGap: false,
      axisLabel: { hideOverlap: true },
    },
    yAxis: {
      type: 'value',
      axisLabel: {
        formatter: (value: number) => `$${value.toFixed(0)}`,
      },
    },
    series: [
      {
        name: '累计收益',
        type: 'line',
        smooth: true,
        data: cumulative,
        areaStyle: {},
        lineStyle: { width: 3 },
        symbol: 'circle',
        symbolSize: 6,
      },
    ],
  }

  return <ReactECharts option={option} style={{ height: 320, width: '100%' }} />
}