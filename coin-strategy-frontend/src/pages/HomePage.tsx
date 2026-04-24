import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { strategyApi, walletApi, reportsApi, tasksApi } from '@/api'
import { useStrategyStore } from '@/store/useStrategyStore'
import { MetricGrid } from '@/components/strategy/MetricGrid'
import { PositionsTable } from '@/components/tables/PositionsTable'
import { SettlementsTable } from '@/components/tables/SettlementsTable'
import { OrdersTable } from '@/components/tables/OrdersTable'
import { TasksTable } from '@/components/tables/TasksTable'
import { TimelineChart } from '@/components/charts/TimelineChart'
import { AttributionBars } from '@/components/charts/AttributionBars'
import { FollowAddressDailyTable } from '@/components/tables/FollowAddressDailyTable'
import { TABLE_FETCH_LIMIT } from '@/utils/table'

export function HomePage() {
  const [searchParams] = useSearchParams()
  const { selectedStrategy, positionMode, selectedDays } = useStrategyStore()

  const strategy = searchParams.get('strategy') || selectedStrategy
  const mode = (searchParams.get('mode') || positionMode) as 'PAPER' | 'LIVE'
  const days = Number(searchParams.get('days')) || selectedDays

  // 获取策略详情
  const { data: detail, isLoading: detailLoading } = useQuery({
    queryKey: ['strategyDetail', strategy, mode],
    queryFn: () => strategyApi.getSummary(strategy, mode),
  })

  // 获取持仓
  const { data: positions = [], isLoading: positionsLoading } = useQuery({
    queryKey: ['positions', mode],
    queryFn: () => walletApi.getPositionsLive(mode),
  })

  // 获取待结算
  const { data: settlements = [] } = useQuery({
    queryKey: ['settlements', mode],
    queryFn: () => walletApi.getNextSettlements(TABLE_FETCH_LIMIT, mode),
  })

  // 获取可执行任务
  const { data: executableTasks = [] } = useQuery({
    queryKey: ['executableTasks'],
    queryFn: () => tasksApi.getExecutable(8),
  })

  // 获取机会任务
  const { data: opportunityTasks = [] } = useQuery({
    queryKey: ['opportunityTasks'],
    queryFn: () => tasksApi.getOpportunities(8, 3),
  })

  // 获取日报
  const { data: dailyReports = [] } = useQuery({
    queryKey: ['dailyReports', days, mode],
    queryFn: () => reportsApi.getDailyReports(Math.max(days, 7), mode),
  })

  // 获取归因
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
    queryFn: () => reportsApi.getFollowAddressDailyReports(TABLE_FETCH_LIMIT, mode),
  })

  if (detailLoading) {
    return <div className="loading">加载中...</div>
  }

  const showLiveEmptyHint =
    mode === 'LIVE' &&
    detail &&
    detail.totalTrades === 0 &&
    positions.length === 0 &&
    settlements.length === 0 &&
    dailyReports.length === 0

  return (
    <>
      {showLiveEmptyHint ? (
        <section className="panel">
          <div className="empty-state">
            当前处于 LIVE 口径，但后端暂时没有真实订单、持仓和报表数据。
            切换右上角的“模拟”后，可以查看现有的交易历史、收益折线图和柱状图。
          </div>
        </section>
      ) : null}

      {/* 核心指标 */}
      <MetricGrid detail={detail} />

      {/* 可执行任务和机会 */}
      <div className="split-grid">
        <section className="panel">
          <div className="section-head">
            <h2>可执行任务</h2>
            <p>通过风控的待执行任务</p>
          </div>
          <TasksTable tasks={executableTasks} type="executable" />
        </section>
        <section className="panel">
          <div className="section-head">
            <h2>机会看板</h2>
            <p>最接近可执行但仍被拦住的任务</p>
          </div>
          <TasksTable tasks={opportunityTasks} type="opportunity" />
        </section>
      </div>

      {/* 收益时间线 */}
      <section className="panel">
        <div className="section-head">
          <h2>收益时间线</h2>
          <p>按日查看累计收益变化</p>
        </div>
        <TimelineChart data={dailyReports} />
      </section>

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
          <p>按源地址查看胜负笔数、净利润、手续费和在途风险敞口</p>
        </div>
        <FollowAddressDailyTable data={followAddressDailyReports} />
      </section>

      {/* 持仓 */}
      <div className="split-grid">
        <section className="panel">
          <div className="section-head">
            <h2>当前持仓</h2>
            <p>展示当前 open positions</p>
          </div>
          <PositionsTable positions={positions} loading={positionsLoading} />
        </section>
        <section className="panel">
          <div className="section-head">
            <h2>待结算处理</h2>
            <p>已到结算窗口或待领取的仓位</p>
          </div>
          <SettlementsTable positions={settlements} />
        </section>
      </div>

      {/* 订单历史 */}
      <section className="panel">
        <div className="section-head">
          <h2>交易历史</h2>
          <p>最近的订单记录</p>
        </div>
        <OrdersTable mode={mode} />
      </section>
    </>
  )
}
