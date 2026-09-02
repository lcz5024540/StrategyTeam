import ReactECharts from 'echarts-for-react'
import type { EChartsOption } from 'echarts'
import type { ArbitragePnlTimePoint } from '@/types/arbitrage-dashboard'
import '@/styles/arbitrage-pnl-charts.css'

interface ArbitragePnlChartsProps {
  data: ArbitragePnlTimePoint[]
}

const money = (value: number) => `${value >= 0 ? '+' : ''}${value.toFixed(2)} U`

export function ArbitragePnlCharts({ data }: ArbitragePnlChartsProps) {
  if (!data.length) return <div className="arb-empty arb-chart-empty">暂无每日盈亏数据</div>

  const dates = data.map(item => item.bucketAt.slice(5))
  const common: EChartsOption = {
    animationDuration: 450,
    grid: { left: 58, right: 22, top: 34, bottom: 42 },
    tooltip: { trigger: 'axis', valueFormatter: value => money(Number(value || 0)) },
    xAxis: { type: 'category', data: dates, axisLabel: { color: '#64748b' }, axisLine: { lineStyle: { color: '#dce4ec' } } },
    yAxis: { type: 'value', axisLabel: { color: '#64748b', formatter: value => `${value}U` }, splitLine: { lineStyle: { color: '#edf1f5' } } },
  }
  const line: EChartsOption = {
    ...common,
    series: [{ name: '累计净盈亏', type: 'line', smooth: true, symbolSize: 7,
      data: data.map(item => item.cumulativeNetPnl), lineStyle: { width: 3, color: '#0f9f87' },
      itemStyle: { color: '#0f9f87' }, areaStyle: { color: 'rgba(15,159,135,.12)' } }],
  }
  const bars: EChartsOption = {
    ...common,
    legend: { top: 0, right: 0, textStyle: { color: '#64748b' } },
    series: [
      { name: '每日净盈亏', type: 'bar', barMaxWidth: 34, data: data.map(item => ({ value: item.netPnl,
        itemStyle: { color: item.netPnl >= 0 ? '#16a36a' : '#dc4055', borderRadius: item.netPnl >= 0 ? [5, 5, 0, 0] : [0, 0, 5, 5] } })) },
      { name: '资金费', type: 'line', smooth: true, data: data.map(item => item.fundingPnl), lineStyle: { color: '#d98b16' }, itemStyle: { color: '#d98b16' } },
    ],
  }
  const latest = data[data.length - 1]
  return <div className="arb-chart-grid">
    <article className="arb-panel arb-chart"><header><div><span>累计净盈亏</span><strong>{money(latest.cumulativeNetPnl)}</strong></div><small>实际交易现金流 + 资金费 − 已记录费用</small></header><ReactECharts option={line} style={{ height: 300 }} /></article>
    <article className="arb-panel arb-chart"><header><div><span>每日收益</span><strong>{money(latest.netPnl)}</strong></div><small>Asia/Shanghai 自然日</small></header><ReactECharts option={bars} style={{ height: 300 }} /></article>
  </div>
}
