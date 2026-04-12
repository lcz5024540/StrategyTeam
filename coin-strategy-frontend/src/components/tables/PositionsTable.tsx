import type { Position } from '@/types/position'
import { compactAddress, formatAmount, formatMoney, getAmountClass, truncateText } from '@/utils/format'

interface PositionsTableProps {
  positions: Position[]
  loading?: boolean
}

export function PositionsTable({ positions, loading = false }: PositionsTableProps) {
  if (loading) {
    return <div className="loading">持仓加载中...</div>
  }

  if (positions.length === 0) {
    return (
      <div className="table-wrap">
        <table>
          <tbody>
            <tr>
              <td className="empty-state">暂无持仓数据</td>
            </tr>
          </tbody>
        </table>
      </div>
    )
  }

  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>钱包</th>
            <th>市场</th>
            <th>Outcome</th>
            <th>数量</th>
            <th>成本</th>
            <th>未实现</th>
            <th>状态</th>
            <th>到期</th>
          </tr>
        </thead>
        <tbody>
          {positions.map((position) => (
            <tr key={position.id}>
              <td title={position.executionAddress}>{compactAddress(position.executionAddress)}</td>
              <td title={position.question}>{truncateText(position.question, 36)}</td>
              <td>{position.outcome}</td>
              <td>{formatAmount(position.quantity)}</td>
              <td>{formatAmount(position.costBasisUsdc)}</td>
              <td className={getAmountClass(position.unrealizedPnlUsdc)}>{formatMoney(position.unrealizedPnlUsdc)}</td>
              <td>
                <span className={`badge ${position.status === 'OPEN' ? 'accent' : ''}`}>
                  {position.status}
                </span>
              </td>
              <td>{position.marketEndAtBjt || '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}