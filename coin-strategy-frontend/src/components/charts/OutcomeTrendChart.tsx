import ReactECharts from 'echarts-for-react'
import type { EChartsOption } from 'echarts'
import type { DailyReport } from '@/types/attribution'

interface OutcomeTrendChartProps {
  data: DailyReport[]
}

export function OutcomeTrendChart({ data }: OutcomeTrendChartProps) {
  if (data.length === 0) {
    return <div className="empty-state">暂无胜负订单趋势数据</div>
  }

  const sorted = [...data].sort((a, b) => a.reportDate.localeCompare(b.reportDate))
  const dates = sorted.map((item) => item.reportDate)
  const winRates = sorted.map((item) => Number(((item.winRate || 0) * 100).toFixed(2)))
  const winningPositions = sorted.map((item) => item.winningPositions || 0)
  const losingPositions = sorted.map((item) => item.losingPositions || 0)

  const option: EChartsOption = {
    backgroundColor: 'transparent',
    grid: { left: 56, right: 56, top: 24, bottom: 40 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(4, 16, 6, 0.95)',
      borderColor: 'rgba(0, 255, 65, 0.35)',
      textStyle: { color: '#c0f0c0', fontFamily: 'monospace', fontSize: 12 },
    },
    legend: {
      top: 0,
      right: 0,
      textStyle: { color: '#6f9f75', fontFamily: 'monospace', fontSize: 11 },
      itemWidth: 14,
      itemHeight: 8,
    },
    xAxis: {
      type: 'category',
      data: dates,
      axisLabel: { hideOverlap: true, color: '#3a6642', fontFamily: 'monospace', fontSize: 11 },
      axisLine: { lineStyle: { color: 'rgba(0, 255, 65, 0.20)' } },
      splitLine: { lineStyle: { color: 'rgba(0, 255, 65, 0.06)' } },
    },
    yAxis: [
      {
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
      {
        type: 'value',
        axisLabel: {
          formatter: (value: number) => `${value.toFixed(0)}`,
          color: '#3a6642',
          fontFamily: 'monospace',
          fontSize: 11,
        },
        axisLine: { lineStyle: { color: 'rgba(0, 255, 65, 0.20)' } },
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: '胜率',
        type: 'line',
        smooth: true,
        yAxisIndex: 0,
        data: winRates,
        lineStyle: { width: 2, color: '#00ff41', shadowBlur: 8, shadowColor: 'rgba(0, 255, 65, 0.60)' },
        itemStyle: { color: '#00ff41', borderColor: '#020904', borderWidth: 2 },
      },
      {
        name: '盈利订单数',
        type: 'line',
        smooth: true,
        yAxisIndex: 1,
        data: winningPositions,
        lineStyle: { width: 2, color: '#6ce5e8', shadowBlur: 8, shadowColor: 'rgba(108, 229, 232, 0.45)' },
        itemStyle: { color: '#6ce5e8', borderColor: '#020904', borderWidth: 2 },
      },
      {
        name: '亏损订单数',
        type: 'line',
        smooth: true,
        yAxisIndex: 1,
        data: losingPositions,
        lineStyle: { width: 2, color: '#ff4d6d', shadowBlur: 8, shadowColor: 'rgba(255, 77, 109, 0.45)' },
        itemStyle: { color: '#ff4d6d', borderColor: '#020904', borderWidth: 2 },
      },
    ],
  }

  return <ReactECharts option={option} style={{ height: 300, width: '100%' }} />
}
