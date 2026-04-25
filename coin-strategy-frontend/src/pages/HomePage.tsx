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

  // 获取收益时间线
  const { data: pnlTimeline = [], isLoading: timelineLoading } = useQuery({
    queryKey: ['pnlTimeline', days, 24, mode],
    queryFn: () => reportsApi.getPnlTimeline(Math.max(days, 1), 24, mode),
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

  if (detailLoading || timelineLoading) {
    return <div className="loading">加载中...</div>
  }

  const showLiveEmptyHint =
    mode === 'LIVE' &&
    detail &&
    detail.totalTrades === 0 &&
    positions.length === 0 &&
    settlements.length === 0 &&
    pnlTimeline.length === 0

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
          </div>
          <TasksTable tasks={executableTasks} type="executable" />
        </section>
        <section className="panel">
          <div className="section-head">
            <h2>机会看板</h2>
          </div>
          <TasksTable tasks={opportunityTasks} type="opportunity" />
        </section>
      </div>

      {/* 收益时间线 */}
      <section className="panel">
        <div className="section-head">
          <h2>收益时间线</h2>
        </div>
        <TimelineChart data={pnlTimeline} bucketHours={24} />
      </section>

      {/* 归因分析 */}
      <div className="split-grid">
        <section className="panel">
          <div className="section-head">
            <h2>钱包归因</h2>
          </div>
          <AttributionBars data={walletAttribution} type="wallet" />
        </section>
        <section className="panel">
          <div className="section-head">
            <h2>市场归因</h2>
          </div>
          <AttributionBars data={marketAttribution} type="market" />
        </section>
      </div>

      <section className="panel">
        <div className="section-head">
          <h2>地址分析</h2>
        </div>
        <FollowAddressDailyTable data={followAddressDailyReports} />
      </section>

      {/* 持仓 */}
      <div className="split-grid">
        <section className="panel">
          <div className="section-head">
            <h2>当前持仓</h2>
          </div>
          <PositionsTable positions={positions} loading={positionsLoading} />
        </section>
        <section className="panel">
          <div className="section-head">
            <h2>待结算</h2>
          </div>
          <SettlementsTable positions={settlements} />
        </section>
      </div>

      {/* 订单历史 */}
      <section className="panel">
        <div className="section-head">
          <h2>交易历史</h2>
        </div>
        <OrdersTable mode={mode} />
      </section>
    </>
  )
}
