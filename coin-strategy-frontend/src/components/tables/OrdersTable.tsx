import { useQuery } from '@tanstack/react-query'
import { walletApi } from '@/api'
import { compactAddress, formatAmount, formatMoney, truncateText } from '@/utils/format'

export function OrdersTable() {
  const { data: orders = [], isLoading } = useQuery({
    queryKey: ['orders'],
    queryFn: () => walletApi.getOrders(30),
  })

  if (isLoading) {
    return <div className="loading">订单加载中...</div>
  }

  if (orders.length === 0) {
    return (
      <div className="table-wrap">
        <table>
          <tbody>
            <tr>
              <td className="empty-state">暂无订单记录</td>
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
            <th>名义金额</th>
            <th>预期EV</th>
            <th>状态</th>
            <th>下单时间</th>
          </tr>
        </thead>
        <tbody>
          {orders.map((order) => (
            <tr key={order.id}>
              <td>{truncateText(order.id, 14)}</td>
              <td title={order.executionAddress}>{compactAddress(order.executionAddress)}</td>
              <td title={order.question}>{truncateText(order.question, 34)}</td>
              <td>{order.outcome}</td>
              <td>
                <span className={`badge ${order.side === 'BUY' ? 'accent' : 'warn'}`}>{order.side}</span>
              </td>
              <td>{formatAmount(order.notionalUsdc)}</td>
              <td className={order.expectedNetEvUsdc && order.expectedNetEvUsdc >= 0 ? 'positive' : 'negative'}>
                {formatMoney(order.expectedNetEvUsdc || 0)}
              </td>
              <td>
                <span className="badge">{order.status}</span>
              </td>
              <td>{order.placedAt || '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}