import ReactECharts from 'echarts-for-react'
import type { EChartsOption } from 'echarts'
import type { DailyReport, TimelinePoint } from '@/types/attribution'
import { formatMoney } from '@/utils/format'

interface TimelineChartProps {
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

function lossAmount(item: TimelinePoint | DailyReport) {
  if ('bucketAt' in item) {
    return item.lossPnlUsdc || 0
  }
  return item.lossPnlUsdc ?? Math.min(item.realizedPnlUsdc || 0, 0)
}

function hasChartActivity(item: TimelinePoint | DailyReport) {
  if ('bucketAt' in item) {
    return [
      item.profitPnlUsdc,
      item.lossPnlUsdc,
      item.realizedPnlUsdc,
      item.feeUsdc,
      item.netPnlUsdc,
      item.cumulativeLossPnlUsdc,
      item.cumulativeRealizedPnlUsdc,
      item.cumulativeFeeUsdc,
      item.cumulativeNetPnlUsdc,
    ].some((value) => Number(value || 0) !== 0)
  }
  return [
    item.totalVolumeUsdc,
    item.feeUsdc,
    item.profitPnlUsdc,
    item.lossPnlUsdc,
    item.realizedPnlUsdc,
    item.unrealizedPnlUsdc,
    item.netPnlUsdc,
    item.openPositions,
    item.closedPositions,
    item.winningPositions,
    item.losingPositions,
  ].some((value) => Number(value || 0) !== 0)
}

export function TimelineChart({ data, bucketHours = 24 }: TimelineChartProps) {
  if (data.length === 0) {
    return <div className="empty-state">暂无收益时间线数据</div>
  }

  const sorted = [...data].sort((a, b) => new Date(pointTime(a)).getTime() - new Date(pointTime(b)).getTime())
  const firstActiveIndex = sorted.findIndex(hasChartActivity)
  const visible = firstActiveIndex >= 0 ? sorted.slice(firstActiveIndex) : sorted
  if (visible.length === 0) {
    return <div className="empty-state">暂无收益时间线数据</div>
  }

  const dates = visible.map((item) => formatBucketLabel(pointTime(item), bucketHours))
  const cumulativeNet: number[] = []
  const cumulativeLoss: number[] = []
  let runningNet = 0
  let runningLoss = 0
  visible.forEach((item) => {
    if ('cumulativeNetPnlUsdc' in item) {
      cumulativeNet.push(Number((item.cumulativeNetPnlUsdc || 0).toFixed(2)))
      cumulativeLoss.push(Number((item.cumulativeLossPnlUsdc || 0).toFixed(2)))
      return
    }
    runningNet += item.netPnlUsdc || 0
    runningLoss += lossAmount(item)
    cumulativeNet.push(Number(runningNet.toFixed(2)))
    cumulativeLoss.push(Number(runningLoss.toFixed(2)))
  })
  const sliderStart = visible.length > 45 ? Math.max(0, 100 - (45 / visible.length) * 100) : 0

  const option: EChartsOption = {
    backgroundColor: 'transparent',
    grid: { left: 56, right: 24, top: 34, bottom: 72 },
    legend: {
      top: 0,
      right: 0,
      textStyle: { color: '#6f9f75', fontFamily: 'monospace', fontSize: 11 },
      itemWidth: 14,
      itemHeight: 8,
    },
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(4, 16, 6, 0.95)',
      borderColor: 'rgba(0, 255, 65, 0.35)',
      textStyle: { color: '#c0f0c0', fontFamily: 'monospace', fontSize: 12 },
      valueFormatter: (value) => formatMoney(Number(value || 0)),
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
        name: '累计净收益',
        type: 'line',
        smooth: true,
        data: cumulativeNet,
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
      {
        name: '累计亏损额',
        type: 'line',
        smooth: true,
        data: cumulativeLoss,
        lineStyle: { width: 2, color: '#ff4d6d', type: 'dashed', shadowBlur: 6, shadowColor: 'rgba(255, 77, 109, 0.35)' },
        itemStyle: { color: '#ff4d6d', borderColor: '#020904', borderWidth: 2 },
        symbol: 'circle',
        symbolSize: 5,
      },
    ],
  }

  return <ReactECharts option={option} className="timeline-chart" style={{ width: '100%' }} />
}
