import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { reportsApi } from '@/api'
import { useStrategyStore } from '@/store/useStrategyStore'
import { TimelineChart } from '@/components/charts/TimelineChart'
import { DailyPnlBars } from '@/components/charts/DailyPnlBars'
import { WinRateTrend } from '@/components/charts/WinRateTrend'
import { AttributionBars } from '@/components/charts/AttributionBars'
import { OrdersTable } from '@/components/tables/OrdersTable'
import { FollowAddressDailyTable } from '@/components/tables/FollowAddressDailyTable'

export function ChartsPage() {
  const [searchParams] = useSearchParams()
  const { positionMode, selectedDays } = useStrategyStore()

  const mode = (searchParams.get('mode') || positionMode) as 'PAPER' | 'LIVE'
  const days = Number(searchParams.get('days')) || selectedDays

  const { data: dailyReports = [], isLoading } = useQuery({
    queryKey: ['dailyReports', days, mode],
    queryFn: () => reportsApi.getDailyReports(Math.max(days, 7), mode),
  })

  const { data: walletAttribution = [] } = useQuery({
    queryKey: ['walletAttribution', mode],
    queryFn: () => reportsApi.getWalletAttribution(10, mode),
  })

  const { data: marketAttribution = [] } = useQuery({
    queryKey: ['marketAttribution', mode],
    queryFn: () => reportsApi.getMarketAttribution(10, mode),
  })

  const { data: followAddressDailyReports = [] } = useQuery({
    queryKey: ['followAddressDailyReports', mode],
    queryFn: () => reportsApi.getFollowAddressDailyReports(20, mode),
  })

  const { data: conflicts = [] } = useQuery({
    queryKey: ['conflicts'],
    queryFn: () => reportsApi.getConflictingMarkets(20, true),
  })

  if (isLoading) {
    return <div className="loading">加载中...</div>
  }

  const showLiveEmptyHint =
    mode === 'LIVE' &&
    dailyReports.length === 0 &&
    walletAttribution.length === 0 &&
    marketAttribution.length === 0 &&
    conflicts.length === 0

  return (
    <>
      {showLiveEmptyHint ? (
        <section className="panel">
          <div className="empty-state">
            当前处于 LIVE 口径，但后端还没有真实收益报表，所以折线图和柱状图会为空。
            切换右上角的“模拟”可以查看现有 PAPER 数据。
          </div>
        </section>
      ) : null}

      {/* 累计收益时间线 */}
      <section className="panel">
        <div className="section-head">
          <h2>累计收益时间线</h2>
          <p>按日查看累计收益变化趋势</p>
        </div>
        <TimelineChart data={dailyReports} />
      </section>

      {/* 日收益和胜率 */}
      <div className="split-grid">
        <section className="panel">
          <div className="section-head">
            <h2>日收益</h2>
            <p>每日收益柱状图</p>
          </div>
          <DailyPnlBars data={dailyReports} />
        </section>
        <section className="panel">
          <div className="section-head">
            <h2>胜率趋势</h2>
            <p>滚动胜率变化</p>
          </div>
          <WinRateTrend data={dailyReports} />
        </section>
      </div>

      {/* 归因分析 */}
      <div className="split-grid">
        <section className="panel">
          <div className="section-head">
            <h2>钱包归因</h2>
            <p>按执行钱包维度的收益归因</p>
          </div>
          <AttributionBars data={walletAttribution} type="wallet" />
        </section>
        <section className="panel">
          <div className="section-head">
            <h2>市场归因</h2>
            <p>按市场维度的收益归因</p>
          </div>
          <AttributionBars data={marketAttribution} type="market" />
        </section>
      </div>

      <section className="panel">
        <div className="section-head">
          <h2>今日跟单地址分析</h2>
          <p>按跟单源地址拆解胜率结构、净盈亏与未平仓暴露</p>
        </div>
        <FollowAddressDailyTable data={followAddressDailyReports} />
      </section>

      {/* 冲突市场 */}
      <section className="panel">
        <div className="section-head">
          <h2>冲突市场</h2>
          <p>同一市场持有多个 outcome 的冲突</p>
        </div>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>市场</th>
                <th>Outcomes</th>
                <th>持仓数</th>
                <th>总成本</th>
                <th>净盈亏</th>
                <th>预计结算</th>
              </tr>
            </thead>
            <tbody>
              {conflicts.length === 0 ? (
                <tr>
                  <td colSpan={6} className="empty-state">暂无冲突市场</td>
                </tr>
              ) : (
                conflicts.map((conflict) => (
                  <tr key={conflict.marketId}>
                    <td title={conflict.question}>{conflict.question?.slice(0, 40)}...</td>
                    <td>{conflict.outcomes?.join(', ')}</td>
                    <td>{conflict.positionCount}</td>
                    <td>${conflict.totalCostUsdc?.toFixed(2)}</td>
                    <td className={conflict.netPnlUsdc >= 0 ? 'positive' : 'negative'}>
                      {conflict.netPnlUsdc >= 0 ? '+' : ''}${conflict.netPnlUsdc?.toFixed(2)}
                    </td>
                    <td>{conflict.marketEndAtBjt || '-'}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </section>

      <section className="panel">
        <div className="section-head">
          <h2>交易历史</h2>
          <p>最近的订单记录，便于对照图表回看策略行为</p>
        </div>
        <OrdersTable mode={mode} />
      </section>
    </>
  )
}
