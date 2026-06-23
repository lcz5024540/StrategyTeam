import { useMemo, useState } from 'react'
import ReactECharts from 'echarts-for-react'
import type { EChartsOption } from 'echarts'
import type { OrderPnlCategoryAttribution, OrderPnlCategoryRow, OrderPnlCategoryWindow } from '@/types/attribution'

interface OrderPnlCategoryAttributionChartProps {
  data?: OrderPnlCategoryAttribution
}

const WINDOW_ORDER = ['30d', '7d', 'all']

function shortLabel(value: string) {
  return value.length > 18 ? `${value.slice(0, 18)}...` : value
}

function buildOption(rows: OrderPnlCategoryRow[], emptyText: string): EChartsOption {
  if (rows.length === 0) {
    return {
      backgroundColor: 'transparent',
      graphic: {
        type: 'text',
        left: 'center',
        top: 'middle',
        style: { text: emptyText, fill: '#3a6642', fontSize: 12, fontFamily: 'monospace' },
      },
    }
  }

  const labels = rows.map((item) => item.label || item.category || item.key || '-')
  return {
    backgroundColor: 'transparent',
    grid: { left: 72, right: 18, top: 34, bottom: 28 },
    legend: {
      top: 0,
      right: 8,
      textStyle: { color: '#6ea878', fontFamily: 'monospace', fontSize: 11 },
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(0, 255, 65, 0.05)' } },
      backgroundColor: 'rgba(4, 16, 6, 0.95)',
      borderColor: 'rgba(0, 255, 65, 0.35)',
      textStyle: { color: '#c0f0c0', fontFamily: 'monospace', fontSize: 12 },
    },
    xAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: '#3a6642', fontFamily: 'monospace', fontSize: 11 },
      axisLine: { lineStyle: { color: 'rgba(0, 255, 65, 0.20)' } },
      splitLine: { lineStyle: { color: 'rgba(0, 255, 65, 0.06)' } },
    },
    yAxis: {
      type: 'category',
      data: labels,
      inverse: true,
      axisLabel: {
        formatter: (value: string) => shortLabel(value),
        color: '#3a6642',
        fontFamily: 'monospace',
        fontSize: 11,
      },
      axisLine: { lineStyle: { color: 'rgba(0, 255, 65, 0.20)' } },
      splitLine: { show: false },
    },
    series: [
      {
        name: '盈利订单',
        type: 'bar',
        stack: 'orders',
        data: rows.map((item) => item.profitOrderCount || 0),
        barMaxWidth: 22,
        itemStyle: { color: '#00d455', borderRadius: [0, 3, 3, 0] },
      },
      {
        name: '亏损订单',
        type: 'bar',
        stack: 'orders',
        data: rows.map((item) => item.lossOrderCount || 0),
        barMaxWidth: 22,
        itemStyle: { color: '#ff3158', borderRadius: [0, 3, 3, 0] },
      },
    ],
  }
}

export function OrderPnlCategoryAttributionChart({ data }: OrderPnlCategoryAttributionChartProps) {
  const windows = useMemo(() => {
    const rows = data?.windows || []
    return [...rows].sort((a, b) => WINDOW_ORDER.indexOf(a.window) - WINDOW_ORDER.indexOf(b.window))
  }, [data])
  const [activeWindow, setActiveWindow] = useState('30d')
  const selected = windows.find((item) => item.window === activeWindow) || windows[0]

  if (!selected) {
    return <div className="empty-state">暂无市场类型盈亏订单数据</div>
  }

  const marketOption = buildOption(selected.marketCategories || [], '暂无市场类别数据')
  const walletOption = buildOption(selected.walletCategories || [], '暂无钱包类别数据')

  return (
    <div>
      <div className="chart-controls" style={{ justifyContent: 'flex-end', marginBottom: 12 }}>
        <div className="segmented-control" aria-label="市场类型盈亏订单窗口">
          {windows.map((window: OrderPnlCategoryWindow) => (
            <button
              key={window.window}
              type="button"
              className={window.window === selected.window ? 'active' : ''}
              onClick={() => setActiveWindow(window.window)}
            >
              {window.label || window.window}
            </button>
          ))}
        </div>
      </div>
      <div className="split-grid">
        <div>
          <div style={{ color: 'oklch(0.72 0.005 240)', fontSize: 12, letterSpacing: '0.08em', marginBottom: 8 }}>
            市场类别订单数
          </div>
          <ReactECharts option={marketOption} style={{ height: 340, width: '100%' }} />
        </div>
        <div>
          <div style={{ color: 'oklch(0.72 0.005 240)', fontSize: 12, letterSpacing: '0.08em', marginBottom: 8 }}>
            钱包主要类别订单数
          </div>
          <ReactECharts option={walletOption} style={{ height: 340, width: '100%' }} />
        </div>
      </div>
    </div>
  )
}
