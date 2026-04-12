import type { Position } from '@/types/position'
import { compactAddress, formatAmount, formatMoney, getAmountClass, truncateText } from '@/utils/format'

interface SettlementsTableProps {
  positions: Position[]
}

export function SettlementsTable({ positions }: SettlementsTableProps) {
  if (positions.length === 0) {
    return (
      <div className="table-wrap">
        <table>
          <tbody>
            <tr>
              <td className="empty-state">暂无待结算仓位</td>
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
            <th>成本</th>
            <th>待结算净收益</th>
            <th>可领取</th>
            <th>预计结算</th>
          </tr>
        </thead>
        <tbody>
          {positions.map((position) => (
            <tr key={position.id}>
              <td title={position.executionAddress}>{compactAddress(position.executionAddress)}</td>
              <td title={position.question}>{truncateText(position.question, 36)}</td>
              <td>{position.outcome}</td>
              <td>{formatAmount(position.costBasisUsdc)}</td>
              <td className={getAmountClass(position.pendingSettlementNetPnlUsdc)}>
                {formatMoney(position.pendingSettlementNetPnlUsdc)}
              </td>
              <td>{formatAmount(position.claimableProceedsUsdc)}</td>
              <td>{position.marketEndAtBjt || '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}