import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { strategyApi, walletApi, tasksApi } from '@/api'
import { useStrategyStore } from '@/store/useStrategyStore'
import { MetricGrid } from '@/components/strategy/MetricGrid'
import { PositionsTable } from '@/components/tables/PositionsTable'
import { SettlementsTable } from '@/components/tables/SettlementsTable'
import { OrdersTable } from '@/components/tables/OrdersTable'
import { TasksTable } from '@/components/tables/TasksTable'
import { TimelineChart } from '@/components/charts/TimelineChart'
import { DailyPnlBars } from '@/components/charts/DailyPnlBars'
import { reportsApi } from '@/api'
import { TABLE_FETCH_LIMIT } from '@/utils/table'

export function DetailPage() {
  const [searchParams] = useSearchParams()
  const { selectedStrategy, positionMode } = useStrategyStore()

  const strategy = searchParams.get('strategy') || selectedStrategy
  const mode = (searchParams.get('mode') || positionMode) as 'PAPER' | 'LIVE'

  const { data: detail, isLoading } = useQuery({
    queryKey: ['strategyDetail', strategy, mode],
    queryFn: () => strategyApi.getSummary(strategy, mode),
  })

  const { data: positions = [] } = useQuery({
    queryKey: ['positions', mode],
    queryFn: () => walletApi.getPositionsLive(mode),
  })

  const { data: settlements = [] } = useQuery({
    queryKey: ['settlements', mode],
    queryFn: () => walletApi.getNextSettlements(TABLE_FETCH_LIMIT, mode),
  })

  const { data: executableTasks = [] } = useQuery({
    queryKey: ['executableTasks'],
    queryFn: () => tasksApi.getExecutable(20),
  })

  const { data: opportunityTasks = [] } = useQuery({
    queryKey: ['opportunityTasks'],
    queryFn: () => tasksApi.getOpportunities(20, 3),
  })

  const { data: dailyReports = [] } = useQuery({
    queryKey: ['dailyReports', 30, mode],
    queryFn: () => reportsApi.getDailyReports(30, mode),
  })

  if (isLoading) {
    return <div className="loading">加载中...</div>
  }

  const showLiveEmptyHint =
    mode === 'LIVE' &&
    detail &&
    detail.totalTrades === 0 &&
    positions.length === 0 &&
    settlements.length === 0

  return (
    <>
      {showLiveEmptyHint ? (
        <section className="panel">
          <div className="empty-state">
            当前处于 LIVE 口径，但还没有真实仓位和订单数据。
            切回“模拟”可以查看 paper-book 的历史记录和策略明细。
          </div>
        </section>
      ) : null}

      <MetricGrid detail={detail} />

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

      <div className="split-grid">
        <section className="panel">
          <div className="section-head">
            <h2>当前持仓</h2>
            <p>展示当前 open positions</p>
          </div>
          <PositionsTable positions={positions} />
        </section>
        <section className="panel">
          <div className="section-head">
            <h2>待结算处理</h2>
            <p>已到结算窗口或待领取的仓位</p>
          </div>
          <SettlementsTable positions={settlements} />
        </section>
      </div>

      <div className="split-grid">
        <section className="panel">
          <div className="section-head">
            <h2>收益时间线</h2>
            <p>按日查看累计收益变化</p>
          </div>
          <TimelineChart data={dailyReports} />
        </section>
        <section className="panel">
          <div className="section-head">
            <h2>订单收益柱状图</h2>
            <p>按日报查看净收益分布</p>
          </div>
          <DailyPnlBars data={dailyReports} />
        </section>
      </div>

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
