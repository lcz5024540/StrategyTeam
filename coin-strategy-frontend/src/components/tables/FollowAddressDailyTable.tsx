import type { FollowAddressDailyReport } from '@/types/attribution'
import { compactAddress, formatAmount, formatMoney, formatPercent, getAmountClass } from '@/utils/format'

interface FollowAddressDailyTableProps {
  data: FollowAddressDailyReport[]
}

export function FollowAddressDailyTable({ data }: FollowAddressDailyTableProps) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>跟单地址</th>
            <th>订单/成交</th>
            <th>赢/亏/平</th>
            <th>已平/未平</th>
            <th>胜率</th>
            <th>净盈亏</th>
            <th>已实现</th>
            <th>浮动</th>
            <th>手续费</th>
            <th>均赢/均亏</th>
            <th>最大盈/亏</th>
            <th>成交额</th>
            <th>风险敞口</th>
            <th>市场数</th>
          </tr>
        </thead>
        <tbody>
          {data.length === 0 ? (
            <tr>
              <td colSpan={14} className="empty-state">当日暂无跟单地址分析数据</td>
            </tr>
          ) : (
            data.map((item) => (
              <tr key={`${item.walletAddress}-${item.reportDate}`}>
                <td title={item.walletAddress}>{compactAddress(item.walletAddress, 8, 6)}</td>
                <td>{item.totalOrders}/{item.filledOrders}</td>
                <td>{item.winningPositions}/{item.losingPositions}/{item.breakevenPositions}</td>
                <td>{item.closedPositions}/{item.openPositions}</td>
                <td>{formatPercent(item.winRate)}</td>
                <td className={getAmountClass(item.netPnlUsdc)}>{formatMoney(item.netPnlUsdc)}</td>
                <td className={getAmountClass(item.realizedPnlUsdc)}>{formatMoney(item.realizedPnlUsdc)}</td>
                <td className={getAmountClass(item.unrealizedPnlUsdc)}>{formatMoney(item.unrealizedPnlUsdc)}</td>
                <td className={getAmountClass(-Math.abs(item.feeUsdc || 0))}>{formatMoney(-(item.feeUsdc || 0))}</td>
                <td>{formatAmount(item.averageWinUsdc)} / {formatAmount(item.averageLossUsdc)}</td>
                <td>{formatMoney(item.largestWinUsdc)} / {formatMoney(item.largestLossUsdc)}</td>
                <td>{formatAmount(item.tradedVolumeUsdc)}</td>
                <td>{formatAmount(item.grossExposureUsdc)}</td>
                <td>{item.distinctMarkets}</td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  )
}
