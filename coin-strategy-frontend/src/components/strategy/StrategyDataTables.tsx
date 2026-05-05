import type { StrategyOrderRow, StrategyPositionRow } from '@/types/strategy'
import { formatAmount, formatMoney, getAmountClass, truncateText } from '@/utils/format'

function statusBadgeClass(status?: string) {
  const normalized = String(status || '').toUpperCase()
  if (normalized.includes('OPEN') || normalized === 'FILLED') return 'badge accent'
  if (normalized.includes('FAIL') || normalized.includes('CANCEL') || normalized.includes('LOSS')) return 'badge danger'
  if (normalized.includes('PENDING') || normalized.includes('CLOSING') || normalized.includes('PARTIAL')) return 'badge warn'
  return 'badge'
}

export function StrategyPositionsTable({
  positions,
  loading = false,
  emptyLabel = '暂无持仓数据',
}: {
  positions: StrategyPositionRow[]
  loading?: boolean
  emptyLabel?: string
}) {
  if (loading) return <div className="loading">持仓加载中...</div>

  if (positions.length === 0) {
    return <div className="empty-state">{emptyLabel}</div>
  }

  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>标的</th>
            <th>Outcome / 结构</th>
            <th>状态</th>
            <th>数量</th>
            <th>入场价</th>
            <th>成本</th>
            <th>已实现</th>
            <th>未实现</th>
            <th>到期 / 结算</th>
          </tr>
        </thead>
        <tbody>
          {positions.map((position) => (
            <tr key={`${position.id}-${position.marketId}`}>
              <td>
                <div className="table-primary">{truncateText(position.question || position.marketId, 42)}</div>
                <div className="table-secondary">{position.marketId}</div>
              </td>
              <td>{position.outcome || '-'}</td>
              <td>
                <span className={statusBadgeClass(position.status)}>{position.status || '-'}</span>
              </td>
              <td>{formatAmount(position.quantity)}</td>
              <td>{formatAmount(position.averageEntryPrice)}</td>
              <td>{formatMoney(position.costBasisUsdc)}</td>
              <td className={getAmountClass(position.realizedPnlUsdc)}>{formatMoney(position.realizedPnlUsdc)}</td>
              <td className={getAmountClass(position.unrealizedPnlUsdc)}>{formatMoney(position.unrealizedPnlUsdc)}</td>
              <td>{position.marketEndAtBjt || '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export function StrategyOrdersTable({
  orders,
  loading = false,
}: {
  orders: StrategyOrderRow[]
  loading?: boolean
}) {
  if (loading) return <div className="loading">订单加载中...</div>

  if (orders.length === 0) {
    return <div className="empty-state">暂无订单记录</div>
  }

  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>时间</th>
            <th>标的</th>
            <th>Outcome / 腿</th>
            <th>方向</th>
            <th>状态</th>
            <th>价格</th>
            <th>数量</th>
            <th>名义金额</th>
            <th>收益 / 费用</th>
          </tr>
        </thead>
        <tbody>
          {orders.map((order) => (
            <tr key={`${order.id}-${order.placedAt || ''}`}>
              <td>{order.placedAt || '-'}</td>
              <td>
                <div className="table-primary">{truncateText(order.question || String(order.id), 42)}</div>
                <div className="table-secondary">{order.executionMode || order.orderType || '-'}</div>
              </td>
              <td>{order.outcome || '-'}</td>
              <td>
                <span className={`badge ${order.side === 'BUY' ? 'accent' : order.side === 'SELL' ? 'warn' : ''}`}>
                  {order.side || '-'}
                </span>
              </td>
              <td>
                <span className={statusBadgeClass(order.status)}>{order.status || '-'}</span>
              </td>
              <td>{formatAmount(order.price)}</td>
              <td>{formatAmount(order.size)}</td>
              <td>{formatMoney(order.notionalUsdc)}</td>
              <td>
                <div className={getAmountClass(order.realizedPnlUsdc || 0)}>{formatMoney(order.realizedPnlUsdc || 0)}</div>
                <div className="table-secondary">fee {formatMoney(order.estimatedFeeUsdc || 0)}</div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
