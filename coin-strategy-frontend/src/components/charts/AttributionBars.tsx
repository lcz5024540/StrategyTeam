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
    grid: { left: 64, right: 18, top: 20, bottom: 20 },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
    },
    xAxis: { type: 'value' },
    yAxis: {
      type: 'category',
      data: labels,
      axisLabel: {
        formatter: (value: string) => (value.length > 20 ? `${value.slice(0, 20)}...` : value),
      },
    },
    series: [
      {
        type: 'bar',
        data: pnl,
        itemStyle: {
          color: (params) => (Number(params.value) >= 0 ? '#1f7a4d' : '#b33939'),
        },
      },
    ],
  }

  return <ReactECharts option={option} style={{ height: 320, width: '100%' }} />
}