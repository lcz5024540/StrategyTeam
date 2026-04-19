import type { HedgePair } from '@/types/hedge'
import { formatAmount, formatMoney, formatPercent, formatNumber, getAmountClass, truncateText, compactAddress } from '@/utils/format'

interface HedgePairsTableProps {
  pairs: HedgePair[]
  loading?: boolean
}

function pairStatusBadge(status: HedgePair['status']) {
  const map: Record<string, string> = {
    OPEN: 'accent',
    SETTLING: 'warn',
    SETTLED: '',
    CANCELLED: '',
  }
  return map[status] ?? ''
}

function orderStatusClass(status: string | undefined) {
  if (!status) return ''
  if (status === 'FILLED') return 'positive'
  if (status === 'FAILED' || status === 'CANCELLED') return 'negative'
  return 'neutral'
}

function formatEndAt(ts: number | null): string {
  if (!ts) return '-'
  return new Date(ts).toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

export function HedgePairsTable({ pairs, loading = false }: HedgePairsTableProps) {
  if (loading) {
    return <div className="loading">对冲持仓加载中...</div>
  }

  if (pairs.length === 0) {
    return (
      <div className="table-wrap">
        <table>
          <tbody>
            <tr>
              <td className="empty-state">暂无对冲持仓</td>
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
            <th>Outcome</th>
            <th>方向</th>
            <th>YES价</th>
            <th>NO价</th>
            <th>毛利率</th>
            <th>总成本</th>
            <th>预期收益</th>
            <th>已实现</th>
            <th>状态</th>
            <th>到期</th>
          </tr>
        </thead>
        <tbody>
          {pairs.map((pair) => {
            const yesStatus = pair.yesOrder?.status
            const noStatus = pair.noOrder?.status
            const pairLabel = `PAIR-${pair.positionId}`

            // 合并 YES/NO 订单状态为一条配对状态
            let pairedOrderStatus = '待撮合'
            if (yesStatus === 'FILLED' && noStatus === 'FILLED') pairedOrderStatus = '双边成交'
            else if (yesStatus === 'FILLED' || noStatus === 'FILLED') pairedOrderStatus = '单边成交'
            else if (yesStatus === 'FAILED' || noStatus === 'FAILED') pairedOrderStatus = '下单失败'
            else if (yesStatus === 'PENDING' || noStatus === 'PENDING') pairedOrderStatus = '挂单中'

            return (
              <tr key={pair.positionId}>
                <td>
                  <span title={`YES订单: ${pair.yesOrder?.externalOrderId ?? '-'} | NO订单: ${pair.noOrder?.externalOrderId ?? '-'}`}>
                    {pairLabel}
                  </span>
                </td>
                <td title={pair.executionAddress}>{compactAddress(pair.executionAddress)}</td>
                <td title={pair.question}>{truncateText(pair.question, 32)}</td>
                <td>
                  <span className="badge accent" style={{ marginRight: 4 }}>YES</span>
                  <span className="badge warn">NO</span>
                </td>
                <td>
                  <span className="badge">双边对冲</span>
                </td>
                <td>{formatNumber(pair.yesEntryPrice, 3)}</td>
                <td>{formatNumber(pair.noEntryPrice, 3)}</td>
                <td className={pair.grossEdgeRate > 0 ? 'positive' : 'neutral'}>
                  {formatPercent(pair.grossEdgeRate)}
                </td>
                <td>{formatAmount(pair.totalCostUsdc)}</td>
                <td className={getAmountClass(pair.expectedProfitUsdc)}>
                  {formatMoney(pair.expectedProfitUsdc)}
                </td>
                <td className={getAmountClass(pair.realizedPnlUsdc)}>
                  {formatMoney(pair.realizedPnlUsdc)}
                </td>
                <td>
                  <span className={`badge ${pairStatusBadge(pair.status)}`} style={{ marginRight: 4 }}>
                    {pair.status}
                  </span>
                  <span className={`badge ${orderStatusClass(pairedOrderStatus)}`} style={{ fontSize: '0.75em' }}>
                    {pairedOrderStatus}
                  </span>
                </td>
                <td>{formatEndAt(pair.marketEndAt)}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
