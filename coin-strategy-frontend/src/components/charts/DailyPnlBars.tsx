import ReactECharts from 'echarts-for-react'
import type { EChartsOption } from 'echarts'
import type { DailyReport, TimelinePoint } from '@/types/attribution'

interface DailyPnlBarsProps {
  data: Array<TimelinePoint | DailyReport>
  bucketHours?: number
}

function formatBucketLabel(value: string, bucketHours: number) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value || '-'
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hour = String(date.getHours()).padStart(2, '0')
  return bucketHours >= 24 ? `${month}-${day}` : `${month}-${day} ${hour}:00`
}

function pointTime(item: TimelinePoint | DailyReport) {
  return 'bucketAt' in item ? item.bucketAt : item.reportDate
}

function profitAmount(item: TimelinePoint | DailyReport) {
  return 'profitPnlUsdc' in item ? item.profitPnlUsdc || 0 : Math.max(item.netPnlUsdc || 0, 0)
}

function lossAmount(item: TimelinePoint | DailyReport) {
  return 'lossPnlUsdc' in item ? item.lossPnlUsdc || 0 : Math.min(item.netPnlUsdc || 0, 0)
}

export function DailyPnlBars({ data, bucketHours = 24 }: DailyPnlBarsProps) {
  if (data.length === 0) {
    return <div className="empty-state">暂无分时收益数据</div>
  }

  const sorted = [...data].sort((a, b) => new Date(pointTime(a)).getTime() - new Date(pointTime(b)).getTime())
  const dates = sorted.map((item) => formatBucketLabel(pointTime(item), bucketHours))
  const profitPnl = sorted.map((item) => Number(profitAmount(item).toFixed(2)))
  const lossPnl = sorted.map((item) => Number(lossAmount(item).toFixed(2)))
  const netPnl = sorted.map((item) => Number((item.netPnlUsdc || 0).toFixed(2)))
  const sliderStart = sorted.length > 38 ? Math.max(0, 100 - (38 / sorted.length) * 100) : 0

  const option: EChartsOption = {
    backgroundColor: 'transparent',
    grid: { left: 56, right: 20, top: 24, bottom: 72 },
    legend: {
      top: 0,
      right: 0,
      textStyle: { color: '#6f9f75', fontFamily: 'monospace', fontSize: 11 },
      itemWidth: 14,
      itemHeight: 8,
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(0, 255, 65, 0.05)' } },
      backgroundColor: 'rgba(4, 16, 6, 0.95)',
      borderColor: 'rgba(0, 255, 65, 0.35)',
      textStyle: { color: '#c0f0c0', fontFamily: 'monospace', fontSize: 12 },
    },
    dataZoom: [
      { type: 'inside', start: sliderStart, end: 100 },
      {
        type: 'slider',
        start: sliderStart,
        end: 100,
        height: 18,
        bottom: 18,
        borderColor: 'rgba(0, 255, 65, 0.18)',
        fillerColor: 'rgba(0, 255, 65, 0.10)',
        handleStyle: { color: '#00ff41', borderColor: '#00ff41' },
        textStyle: { color: '#3a6642', fontFamily: 'monospace', fontSize: 10 },
      },
    ],
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
        name: '收益柱',
        type: 'bar',
        data: profitPnl,
        barMaxWidth: 32,
        itemStyle: {
          color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: '#00ff41' }, { offset: 1, color: '#00cc33' }] },
          borderRadius: [3, 3, 0, 0],
        },
      },
      {
        name: '亏损柱',
        type: 'bar',
        data: lossPnl,
        barMaxWidth: 32,
        itemStyle: {
          color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: '#ff5a76' }, { offset: 1, color: '#d7263d' }] },
          borderRadius: [3, 3, 0, 0],
        },
      },
      {
        name: '净收益柱',
        type: 'bar',
        data: netPnl,
        barMaxWidth: 32,
        itemStyle: {
          color: (params) => Number(params.value) >= 0
            ? { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: '#6ce5e8' }, { offset: 1, color: '#2fa7b1' }] }
            : { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: '#ff9f43' }, { offset: 1, color: '#ff6b35' }] },
          borderRadius: [3, 3, 0, 0],
        },
      },
    ],
  }

  return <ReactECharts option={option} style={{ height: 300, width: '100%' }} />
}
