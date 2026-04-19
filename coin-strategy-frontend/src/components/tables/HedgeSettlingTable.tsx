import type { HedgePair } from '@/types/hedge'
import { formatAmount, formatMoney, getAmountClass, truncateText, compactAddress } from '@/utils/format'

interface HedgeSettlingTableProps {
  pairs: HedgePair[]
}

function formatEndAt(ts: number | null): string {
  if (!ts) return '-'
  return new Date(ts).toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

export function HedgeSettlingTable({ pairs }: HedgeSettlingTableProps) {
  if (pairs.length === 0) {
    return (
      <div className="table-wrap">
        <table>
          <tbody>
            <tr>
              <td className="empty-state">暂无待结算对冲仓位</td>
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
            <th>订单ID</th>
            <th>钱包</th>
            <th>市场</th>
            <th>最终结果</th>
            <th>总成本</th>
            <th>已领取</th>
            <th>手续费</th>
            <th>已实现</th>
            <th>状态</th>
            <th>到期</th>
          </tr>
        </thead>
        <tbody>
          {pairs.map((pair) => (
            <tr key={pair.positionId}>
              <td>{`PAIR-${pair.positionId}`}</td>
              <td title={pair.executionAddress}>{compactAddress(pair.executionAddress)}</td>
              <td title={pair.question}>{truncateText(pair.question, 32)}</td>
              <td>
                {pair.finalOutcome ? (
                  <span className={`badge ${pair.finalOutcome === 'YES' ? 'accent' : 'warn'}`}>
                    {pair.finalOutcome}
                  </span>
                ) : (
                  <span className="badge">待定</span>
                )}
              </td>
              <td>{formatAmount(pair.totalCostUsdc)}</td>
              <td className={getAmountClass(pair.claimedUsdc)}>{formatMoney(pair.claimedUsdc)}</td>
              <td className="negative">{formatAmount(pair.feeUsdc)}</td>
              <td className={getAmountClass(pair.realizedPnlUsdc)}>{formatMoney(pair.realizedPnlUsdc)}</td>
              <td>
                <span className={`badge ${pair.status === 'SETTLING' ? 'warn' : ''}`}>{pair.status}</span>
              </td>
              <td>{formatEndAt(pair.marketEndAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
