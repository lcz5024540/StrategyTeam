import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { reportsApi, strategyApi } from '@/api'
import { TimelineChart } from '@/components/charts/TimelineChart'
import { CardHeader, MiniBar, PrimeCard, StatTile, StatusPill, TypeBadge } from '@/components/ui/PrimePrimitives'
import { useStrategyStore } from '@/store/useStrategyStore'
import { normalizeRunnableStrategyCode, type PositionMode } from '@/utils/constants'
import { formatMoney, formatNumber, formatPercent, getAmountClass, truncateText } from '@/utils/format'
import {
  hasCapability,
  toDashboardMetrics,
  toOperationalSummary,
  toRegistryItems,
} from '@/utils/strategyViewModels'
import type { TimelinePoint } from '@/types/attribution'
import type { StrategyDashboardMetrics, StrategyOrderRow, StrategyPositionRow, StrategyRegistryItem, StrategyTimelinePoint } from '@/types/strategy'

function toChartTimeline(points: StrategyTimelinePoint[]): TimelinePoint[] {
  let fallbackCumulativeLoss = 0
  return points.map((point) => {
    const netPnl = Number(point.netPnlUsdc || 0)
    const lossPnl = point.lossPnlUsdc === undefined ? Math.min(netPnl, 0) : Number(point.lossPnlUsdc || 0)
    fallbackCumulativeLoss += lossPnl
    return {
      bucketAt: point.bucketAt,
      profitPnlUsdc: Math.max(netPnl, 0),
      lossPnlUsdc: lossPnl,
      realizedPnlUsdc: netPnl,
      feeUsdc: 0,
      netPnlUsdc: netPnl,
      cumulativeLossPnlUsdc: point.cumulativeLossPnlUsdc === undefined
        ? fallbackCumulativeLoss
        : Number(point.cumulativeLossPnlUsdc || 0),
      cumulativeRealizedPnlUsdc: Number(point.cumulativeNetPnlUsdc || 0),
      cumulativeFeeUsdc: 0,
      cumulativeNetPnlUsdc: Number(point.cumulativeNetPnlUsdc || 0),
    }
  })
}

function signedCompactMoney(value: number | null | undefined) {
  const num = Number(value || 0)
  const sign = num > 0 ? '+' : num < 0 ? '-' : ''
  const abs = Math.abs(num)
  if (abs >= 1_000_000) return `${sign}$${(abs / 1_000_000).toFixed(2)}M`
  if (abs >= 1_000) return `${sign}$${(abs / 1_000).toFixed(1)}K`
  return `${sign}$${abs.toFixed(0)}`
}

function unsignedCompactMoney(value: number | null | undefined) {
  const num = Number(value || 0)
  const abs = Math.abs(num)
  if (abs >= 1_000_000) return `$${(abs / 1_000_000).toFixed(2)}M`
  if (abs >= 1_000) return `$${(abs / 1_000).toFixed(1)}K`
  return `$${abs.toFixed(0)}`
}

function toneFromAmount(value: number | null | undefined): 'positive' | 'negative' | 'neutral' {
  return getAmountClass(value)
}

function DashboardStats({ metrics }: { metrics?: StrategyDashboardMetrics }) {
  if (!metrics) {
    return (
      <PrimeCard>
        <div className="empty-state">暂无策略总览指标</div>
      </PrimeCard>
    )
  }

  const pendingPnlUsdc = metrics.unrealizedPnlUsdc + metrics.pendingSettlementPnlUsdc

  return (
    <div className="ms-stat-grid six">
      <StatTile
        label="TVL"
        value={unsignedCompactMoney(metrics.tvlUsdc)}
        sub={`资金 ${unsignedCompactMoney(metrics.capitalUsdc)}`}
        tone="neutral"
      />
      <StatTile
        label="总收益"
        value={signedCompactMoney(metrics.netPnlUsdc)}
        sub={`已实现 ${signedCompactMoney(metrics.realizedPnlUsdc)}`}
        tone={toneFromAmount(metrics.netPnlUsdc)}
      />
      <StatTile
        label="浮动收益"
        value={signedCompactMoney(pendingPnlUsdc)}
        sub={`未实现 ${signedCompactMoney(metrics.unrealizedPnlUsdc)} / 待结算 ${signedCompactMoney(metrics.pendingSettlementPnlUsdc)}`}
        tone={toneFromAmount(pendingPnlUsdc)}
      />
      <StatTile
        label="订单"
        value={formatNumber(metrics.totalOrders, 0)}
        sub={`已成交 ${formatNumber(metrics.filledOrders, 0)} 笔`}
        tone="neutral"
      />
      <StatTile
        label="仓位"
        value={formatNumber(metrics.openPositions + metrics.pendingPositions + metrics.closedPositions, 0)}
        sub={`持有 ${formatNumber(metrics.openPositions, 0)} / 待结算 ${formatNumber(metrics.pendingPositions, 0)} / 已结束 ${formatNumber(metrics.closedPositions, 0)} · 剩余 ${formatNumber(metrics.remainingPositionSlots, 0)}`}
        tone="neutral"
      />
      <StatTile
        label="胜率"
        value={formatPercent(metrics.winRate)}
        sub={`${formatNumber(metrics.winTrades, 0)} 盈 / ${formatNumber(metrics.lossTrades, 0)} 亏`}
        tone={metrics.winRate >= 0.6 ? 'positive' : metrics.winRate >= 0.5 ? 'warn' : 'negative'}
      />
    </div>
  )
}

function PolymarketModules({ mode }: { mode: PositionMode }) {
  const { data: wallets = [] } = useQuery({
    queryKey: ['dashboardPolymarketWalletAttribution', mode],
    queryFn: () => reportsApi.getWalletAttribution(5, mode),
    retry: false,
  })
  const { data: markets = [] } = useQuery({
    queryKey: ['dashboardPolymarketMarketAttribution', mode],
    queryFn: () => reportsApi.getMarketAttribution(6, mode),
    retry: false,
  })

  const maxWalletPnl = Math.max(1, ...wallets.map((item) => Math.abs(item.netPnlUsdc || 0)))
  const maxMarketPnl = Math.max(1, ...markets.map((item) => Math.abs(item.netPnlUsdc || 0)))

  return (
    <div className="ms-module-grid poly">
      <PrimeCard className="span-2">
        <CardHeader label="跟单钱包表现" right={<span className="ms-muted-mono">已跟踪 {wallets.length} 个钱包</span>} />
        <div className="ms-table-wrap compact">
          <table>
            <thead>
              <tr>
                <th>地址</th>
                <th>标签</th>
                <th>总 PnL</th>
                <th>胜率</th>
                <th>交易</th>
                <th>权重</th>
              </tr>
            </thead>
            <tbody>
              {wallets.length === 0 ? (
                <tr><td colSpan={6}>暂无钱包归因数据</td></tr>
              ) : wallets.map((wallet) => (
                <tr key={wallet.address || wallet.label}>
                  <td className="mono">{wallet.address || '-'}</td>
                  <td>{truncateText(wallet.label, 26)}</td>
                  <td className={toneFromAmount(wallet.netPnlUsdc)}>{signedCompactMoney(wallet.netPnlUsdc)}</td>
                  <td>{formatPercent(wallet.winRate)}</td>
                  <td>{formatNumber(wallet.tradeCount, 0)}</td>
                  <td><MiniBar value={wallet.netPnlUsdc} max={maxWalletPnl} tone={wallet.netPnlUsdc >= 0 ? 'positive' : 'negative'} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </PrimeCard>

      <PrimeCard>
        <CardHeader label="市场分布" right={<span className="ms-muted-mono">{markets.length} 个市场</span>} />
        <div className="ms-list-bars">
          {markets.length === 0 ? <div className="empty-state">暂无市场归因数据</div> : markets.map((market) => (
            <div key={market.marketId || market.label} className="ms-list-bar-row">
              <div>
                <span>{truncateText(market.question || market.label, 34)}</span>
                <strong className={toneFromAmount(market.netPnlUsdc)}>{signedCompactMoney(market.netPnlUsdc)}</strong>
              </div>
              <MiniBar value={market.netPnlUsdc} max={maxMarketPnl} tone={market.netPnlUsdc >= 0 ? 'positive' : 'negative'} />
            </div>
          ))}
        </div>
      </PrimeCard>

      <PrimeCard>
        <CardHeader label="事件 PnL" sub="按净收益排序的活跃市场" />
        <div className="ms-list-bars">
          {markets.slice(0, 5).map((market) => (
            <div key={`cat-${market.marketId || market.label}`} className="ms-list-bar-row">
              <div>
                <span>{truncateText(market.label || market.question, 24)}</span>
                <strong className={toneFromAmount(market.netPnlUsdc)}>{signedCompactMoney(market.netPnlUsdc)}</strong>
              </div>
              <MiniBar value={market.netPnlUsdc} max={maxMarketPnl} tone="accent" />
            </div>
          ))}
          {markets.length === 0 ? <div className="empty-state">暂无事件分类收益数据</div> : null}
        </div>
      </PrimeCard>
    </div>
  )
}

function FundingModules({ mode }: { mode: PositionMode }) {
  const { data: positions = [] } = useQuery({
    queryKey: ['dashboardFundingPositions', mode],
    queryFn: () => strategyApi.getPositions('FUNDING_ARB', mode) as Promise<StrategyPositionRow[]>,
    retry: false,
  })
  const { data: orders = [] } = useQuery({
    queryKey: ['dashboardFundingOrders', mode],
    queryFn: () => strategyApi.getOrders('FUNDING_ARB', 8, mode),
    retry: false,
  })

  const operational = toOperationalSummary(positions, orders)

  return (
    <div className="ms-module-grid funding">
      <PrimeCard>
        <CardHeader label="Arbitrage Exposure" sub="current open funding positions" />
        <div className="ms-stat-grid three">
          <StatTile label="Gross" value={signedCompactMoney(operational.exposureUsdc)} tone="neutral" />
          <StatTile label="Realized" value={signedCompactMoney(operational.realizedPnlUsdc)} tone={toneFromAmount(operational.realizedPnlUsdc)} />
          <StatTile label="Positions" value={formatNumber(operational.positionCount, 0)} tone="neutral" />
        </div>
      </PrimeCard>

      <PrimeCard>
        <CardHeader label="Recent Legs" sub="latest funding-arb orders" />
        <div className="ms-table-wrap compact">
          <table>
            <thead>
              <tr>
                <th>Symbol</th>
                <th>Side</th>
                <th>Status</th>
                <th>Notional</th>
              </tr>
            </thead>
            <tbody>
              {orders.length === 0 ? (
                <tr><td colSpan={4}>暂无订单明细</td></tr>
              ) : orders.map((order) => (
                <tr key={order.id}>
                  <td>{truncateText(order.question || String(order.id), 22)}</td>
                  <td>{order.side || '-'}</td>
                  <td><span className="badge">{order.status || '-'}</span></td>
                  <td>{formatMoney(order.notionalUsdc)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </PrimeCard>

      <PrimeCard className="span-2">
        <CardHeader label="Hedge Structure" sub={`mode ${mode}`} />
        <div className="empty-state">当前模块后端暂未接入 hedge structure 数据，避免展示伪造 long / short 比例。</div>
      </PrimeCard>
    </div>
  )
}

function OperationalOverview({
  strategy,
  mode,
  registry,
}: {
  strategy: string
  mode: PositionMode
  registry?: StrategyRegistryItem
}) {
  const canLoadPositions = hasCapability(registry, 'POSITIONS')
  const canLoadOrders = hasCapability(registry, 'ORDERS')
  const { data: positions = [], isLoading: positionsLoading } = useQuery({
    queryKey: ['strategyDashboardPositions', strategy, mode],
    queryFn: () => strategyApi.getPositions(strategy, mode) as Promise<StrategyPositionRow[]>,
    enabled: canLoadPositions,
    retry: false,
  })
  const { data: orders = [], isLoading: ordersLoading } = useQuery({
    queryKey: ['strategyDashboardOrders', strategy, mode],
    queryFn: () => strategyApi.getOrders(strategy, 6, mode) as Promise<StrategyOrderRow[]>,
    enabled: canLoadOrders,
    retry: false,
  })
  const operational = toOperationalSummary(positions, orders)
  const loading = positionsLoading || ordersLoading

  return (
    <PrimeCard>
      <CardHeader label="交易概览" sub={`${mode} 仓位与近期订单`} />
      {!canLoadPositions && !canLoadOrders ? (
        <div className="empty-state">当前策略未声明 POSITIONS / ORDERS capability，暂不展示交易概览。</div>
      ) : loading ? (
        <div className="loading">交易概览加载中...</div>
      ) : (
        <div className="ms-operational-grid">
          <div className="ms-stat-grid four">
            <StatTile label="仓位" value={formatNumber(operational.positionCount, 0)} tone="neutral" />
            <StatTile label="订单" value={formatNumber(operational.orderCount, 0)} tone="neutral" />
            <StatTile label="敞口" value={signedCompactMoney(operational.exposureUsdc)} tone="neutral" />
            <StatTile label="持仓 PnL" value={signedCompactMoney(operational.unrealizedPnlUsdc)} tone={toneFromAmount(operational.unrealizedPnlUsdc)} />
          </div>

          <div className="ms-table-wrap compact">
            <table>
              <thead>
                <tr>
                  <th>近期订单</th>
                  <th>方向</th>
                  <th>状态</th>
                  <th>名义金额</th>
                </tr>
              </thead>
              <tbody>
                {orders.length === 0 ? (
                  <tr><td colSpan={4}>当前口径无订单数据</td></tr>
                ) : orders.slice(0, 6).map((order) => (
                  <tr key={`${order.id}-${order.placedAt || ''}`}>
                    <td>{truncateText(order.question || String(order.id), 34)}</td>
                    <td>{order.side || '-'}</td>
                    <td><span className="badge">{order.status || '-'}</span></td>
                    <td>{formatMoney(order.notionalUsdc)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </PrimeCard>
  )
}

function StrategyModules({ strategy, mode }: { strategy: string; mode: PositionMode }) {
  if (strategy === 'POLYMARKET_COPY') return <PolymarketModules mode={mode} />
  if (strategy === 'FUNDING_ARB') return <FundingModules mode={mode} />
  return (
    <PrimeCard>
      <CardHeader label="Strategy Modules" />
      <div className="empty-state">当前策略仍在计划中，模块区等待后端能力接入。</div>
    </PrimeCard>
  )
}

export function HomePage() {
  const [searchParams] = useSearchParams()
  const { selectedStrategy, positionMode, selectedDays } = useStrategyStore()

  const strategy = normalizeRunnableStrategyCode(searchParams.get('strategy') || selectedStrategy)
  const mode = (searchParams.get('mode') || positionMode) as PositionMode
  const days = Number(searchParams.get('days')) || selectedDays

  const { data: overview } = useQuery({
    queryKey: ['strategyOverview', mode],
    queryFn: () => strategyApi.getOverview(mode),
    retry: false,
  })

  const activeSummary = useMemo(
    () => toRegistryItems(overview?.strategies).find((item) => item.code === strategy),
    [overview?.strategies, strategy]
  )

  const { data: detail, isLoading: detailLoading } = useQuery({
    queryKey: ['strategyDetail', strategy, mode],
    queryFn: () => strategyApi.getSummary(strategy, mode),
    retry: false,
  })

  const { data: timeline = [], isLoading: timelineLoading } = useQuery({
    queryKey: ['strategyTimeline', strategy, days, mode],
    queryFn: () => strategyApi.getTimeline(strategy, Math.max(days, 1), mode),
    enabled: hasCapability(activeSummary, 'TIMELINE'),
    retry: false,
  })

  const chartTimeline = useMemo(() => toChartTimeline(timeline), [timeline])
  const dashboardMetrics = useMemo(() => toDashboardMetrics(detail), [detail])

  if (detailLoading || timelineLoading) {
    return <div className="loading">加载中...</div>
  }

  return (
    <div className="ms-page">
      <div className="ms-strategy-header">
        <div>
          <div className="ms-strategy-title-row">
            <h1>{activeSummary?.name || strategy}</h1>
            <TypeBadge strategyCode={strategy} />
            <StatusPill status={activeSummary?.status} />
          </div>
          <p>
            资金 <strong>{unsignedCompactMoney(dashboardMetrics?.tvlUsdc ?? dashboardMetrics?.capitalUsdc)}</strong>
            <span>|</span>
            更新 <strong>刚刚</strong>
            {activeSummary?.modeBehavior === 'SHARED' ? <em>Paper / Live shared backend book</em> : null}
          </p>
        </div>
      </div>

      <DashboardStats metrics={dashboardMetrics} />

      <PrimeCard>
        <CardHeader label="Equity Curve" sub={`${days}D · ${mode}`} />
        {chartTimeline.length === 0 ? (
          <div className="empty-state">暂无收益时间线数据</div>
        ) : (
          <TimelineChart data={chartTimeline} bucketHours={24} />
        )}
      </PrimeCard>

      <OperationalOverview strategy={strategy} mode={mode} registry={activeSummary} />

      <div className="ms-module-divider">
        <i />
        <span>{strategy === 'POLYMARKET_COPY' ? '⬡ Polymarket' : strategy === 'FUNDING_ARB' ? '⇌ Funding Arb' : '◈ Strategy'} — Strategy Modules</span>
        <i />
      </div>

      <StrategyModules strategy={strategy} mode={mode} />
    </div>
  )
}
