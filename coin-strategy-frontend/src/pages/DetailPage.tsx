import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { strategyApi, walletApi, tasksApi } from '@/api'
import { useStrategyStore } from '@/store/useStrategyStore'
import { MetricGrid } from '@/components/strategy/MetricGrid'
import { PositionsTable } from '@/components/tables/PositionsTable'
import { SettlementsTable } from '@/components/tables/SettlementsTable'
import { TasksTable } from '@/components/tables/TasksTable'

export function DetailPage() {
  const [searchParams] = useSearchParams()
  const { selectedStrategy, positionMode } = useStrategyStore()

  const strategy = searchParams.get('strategy') || selectedStrategy
  const mode = (searchParams.get('mode') || positionMode) as 'PAPER' | 'LIVE'

  const { data: detail, isLoading } = useQuery({
    queryKey: ['strategyDetail', strategy, mode],
    queryFn: () => strategyApi.getDetail(strategy, mode),
  })

  const { data: positions = [] } = useQuery({
    queryKey: ['positions', mode],
    queryFn: () => walletApi.getPositionsLive(),
  })

  const { data: settlements = [] } = useQuery({
    queryKey: ['settlements'],
    queryFn: () => walletApi.getNextSettlements(10),
  })

  const { data: executableTasks = [] } = useQuery({
    queryKey: ['executableTasks'],
    queryFn: () => tasksApi.getExecutable(20),
  })

  const { data: opportunityTasks = [] } = useQuery({
    queryKey: ['opportunityTasks'],
    queryFn: () => tasksApi.getOpportunities(20, 3),
  })

  if (isLoading) {
    return <div className="loading">加载中...</div>
  }

  return (
    <>
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
    </>
  )
}
