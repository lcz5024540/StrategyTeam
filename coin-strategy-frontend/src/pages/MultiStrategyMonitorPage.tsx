import { useMemo } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { strategyApi } from '@/api'
import { CardHeader, PrimeCard, StatTile, StatusPill, TypeBadge } from '@/components/ui/PrimePrimitives'
import { normalizeStrategyCode, type PositionMode } from '@/utils/constants'
import { formatMoney, formatNumber, formatPercent, getAmountClass } from '@/utils/format'
import { isEnterableStrategy, toRegistryItems } from '@/utils/strategyViewModels'

export function MultiStrategyMonitorPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const mode = (searchParams.get('mode') || 'PAPER') as PositionMode

  const { data: overview } = useQuery({
    queryKey: ['strategyOverview', mode],
    queryFn: () => strategyApi.getOverview(mode),
  })

  const allStrategies = useMemo(() => toRegistryItems(overview?.strategies), [overview?.strategies])
  const strategies = useMemo(() => allStrategies.filter(isEnterableStrategy), [allStrategies])
  const plannedStrategies = useMemo(() => allStrategies.filter((item) => !isEnterableStrategy(item)), [allStrategies])
  const active = strategies.filter((item) => item.status === 'ACTIVE')
  const totalPnl = strategies.reduce((sum, item) => sum + Number(item.netPnlUsdc || 0), 0)
  const totalOpen = strategies.reduce((sum, item) => sum + Number(item.openPositions || 0), 0)
  const systemTone = 'positive'

  const gotoStrategy = (code: string) => {
    const params = new URLSearchParams(searchParams)
    params.set('strategy', code)
    navigate({ pathname: '/dashboard', search: params.toString() })
  }

  return (
    <div className="ms-page">
      <div className="ms-stat-grid four">
        <StatTile label="System" value="NOMINAL" sub={`${mode} mode`} tone={systemTone} />
        <StatTile label="Total PnL" value={formatMoney(totalPnl)} sub="all strategies" tone={getAmountClass(totalPnl)} />
        <StatTile label="Running" value={`${active.length} / ${strategies.length}`} sub={`${totalOpen} open positions`} tone="neutral" />
        <StatTile label="Active Alerts" value="0" sub="backend alerts not connected" tone={systemTone} />
      </div>

      <PrimeCard pad={false}>
        <div className="ms-table-title">ALL STRATEGIES</div>
        <div className="ms-table-wrap">
          <table>
            <thead>
              <tr>
                <th>Strategy</th>
                <th>Type</th>
                <th>Status</th>
                <th>Open Pos</th>
                <th>Net PnL</th>
                <th>Orders</th>
                <th>Win Rate</th>
                <th>Mode</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {strategies.map((strategy) => {
                const strategyCode = normalizeStrategyCode(strategy.code)
                return (
                  <tr key={strategy.code} onClick={() => gotoStrategy(strategyCode)}>
                    <td>
                      <strong>{strategy.name}</strong>
                      <span>{strategy.description}</span>
                    </td>
                    <td><TypeBadge strategyCode={strategyCode} small /></td>
                    <td><StatusPill status={strategy.status} /></td>
                    <td>{formatNumber(strategy.openPositions, 0)}</td>
                    <td className={getAmountClass(strategy.netPnlUsdc)}>{formatMoney(strategy.netPnlUsdc)}</td>
                    <td>{formatNumber(strategy.totalOrders, 0)}</td>
                    <td>{formatPercent(strategy.winRate)}</td>
                    <td><span className="badge">{strategy.modeBehavior}</span></td>
                    <td><button type="button" className="ms-row-action">Dash</button></td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </PrimeCard>

      <PrimeCard>
        <CardHeader label="Active Alerts" right={<span className="ms-muted-mono">0 unacknowledged</span>} />
        <div className="ms-alert-list">
          <div className="empty-state">当前没有全局告警；后端告警接口暂未接入。</div>
        </div>
      </PrimeCard>

      {plannedStrategies.length > 0 ? (
        <PrimeCard>
          <CardHeader label="Planned Strategies" sub="visible in selector, disabled until backend capability is ready" />
          <div className="ms-planned-grid">
            {plannedStrategies.map((strategy) => {
              const strategyCode = normalizeStrategyCode(strategy.code)
              return (
                <article key={strategy.code} className="ms-planned-card">
                  <TypeBadge strategyCode={strategyCode} small />
                  <strong>{strategy.name}</strong>
                  <span>{strategy.description}</span>
                  <em>{strategy.status} / {strategy.modeBehavior}</em>
                </article>
              )
            })}
          </div>
        </PrimeCard>
      ) : null}
    </div>
  )
}
