import type { StrategyDetail } from '@/types/strategy'
import { formatMoney, formatNumber, formatPercent, getAmountClass } from '@/utils/format'

interface MetricGridProps {
  detail?: StrategyDetail
}

interface MetricItem {
  label: string
  value: string
  hint: string
  className?: 'positive' | 'negative' | 'neutral'
}

export function MetricGrid({ detail }: MetricGridProps) {
  if (!detail) {
    return (
      <section className="panel">
        <div className="empty-state">暂无策略指标数据</div>
      </section>
    )
  }

  const metrics: MetricItem[] = [
    {
      label: '净收益',
      value: formatMoney(detail.netPnlUsdc),
      hint: 'realized + unrealized + pending',
      className: getAmountClass(detail.netPnlUsdc),
    },
    {
      label: '复合收益',
      value: formatMoney(detail.compositePnlUsdc),
      hint: '策略综合收益指标',
      className: getAmountClass(detail.compositePnlUsdc),
    },
    {
      label: '全部提前退出收益',
      value: formatMoney(detail.earlyExitRealizedPnlUsdc),
      hint: `共 ${formatNumber(detail.earlyExitOrderCount, 0)} 笔，含止盈 / 止损 / 手动`,
      className: getAmountClass(detail.earlyExitRealizedPnlUsdc),
    },
    {
      label: '自动止盈收益',
      value: formatMoney(detail.takeProfitRealizedPnlUsdc),
      hint: `自动止盈 ${formatNumber(detail.takeProfitOrderCount, 0)} 笔`,
      className: getAmountClass(detail.takeProfitRealizedPnlUsdc),
    },
    {
      label: '自动止损收益',
      value: formatMoney(detail.stopLossRealizedPnlUsdc),
      hint: `自动止损 ${formatNumber(detail.stopLossOrderCount, 0)} 笔`,
      className: getAmountClass(detail.stopLossRealizedPnlUsdc),
    },
    {
      label: '最大回撤',
      value: formatMoney(detail.maxDrawdownUsdc),
      hint: '历史最大回撤幅度',
      className: getAmountClass(-Math.abs(detail.maxDrawdownUsdc)),
    },
    {
      label: '当前持仓',
      value: formatNumber(detail.openPositions, 0),
      hint: `已平仓 ${formatNumber(detail.closedPositions, 0)}`,
      className: 'neutral',
    },
    {
      label: '可用资金',
      value: formatMoney(detail.availableCapitalUsdc),
      hint: `预算余额 ${formatMoney(detail.remainingCapitalBudgetUsdc)}`,
      className: getAmountClass(detail.availableCapitalUsdc),
    },
    {
      label: '剩余仓位槽',
      value: formatNumber(detail.remainingPositionSlots, 0),
      hint: `冲突市场 ${formatNumber(detail.openConflictingMarkets, 0)}`,
      className: 'neutral',
    },
    {
      label: '锁定待领收益',
      value: formatMoney(detail.lockedClaimProceedsUsdc),
      hint: `待结算 ${formatMoney(detail.pendingSettlementPnlUsdc)}`,
      className: getAmountClass(detail.lockedClaimProceedsUsdc),
    },
    {
      label: '提前退出订单',
      value: formatNumber(detail.earlyExitOrderCount, 0),
      hint: `止盈 ${formatNumber(detail.takeProfitOrderCount, 0)} / 止损 ${formatNumber(detail.stopLossOrderCount, 0)}`,
      className: 'neutral',
    },
    {
      label: '胜率',
      value: formatPercent(detail.winRate),
      hint: `${formatNumber(detail.winTrades, 0)} 胜 / ${formatNumber(detail.lossTrades, 0)} 负`,
      className: getAmountClass(detail.winRate - 0.5),
    },
  ]

  return (
    <section className="panel">
      <div className="section-head">
        <h2>核心指标</h2>
        <p>策略收益、仓位与风险概览</p>
      </div>
      <div className="metric-grid">
        {metrics.map((item) => (
          <article key={item.label} className="metric-card">
            <div className="label">{item.label}</div>
            <div className={`value ${item.className || 'neutral'}`}>{item.value}</div>
            <div className="hint">{item.hint}</div>
          </article>
        ))}
      </div>
    </section>
  )
}
