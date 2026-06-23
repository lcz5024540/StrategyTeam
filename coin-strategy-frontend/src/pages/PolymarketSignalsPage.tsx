import { useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { tasksApi } from '@/api'
import { TasksTable } from '@/components/tables/TasksTable'
import { useStrategyStore } from '@/store/useStrategyStore'
import { type PositionMode } from '@/utils/constants'

export function PolymarketSignalsPage() {
  const [searchParams] = useSearchParams()
  const { positionMode } = useStrategyStore()
  const mode = (searchParams.get('mode') || positionMode) as PositionMode

  const { data: executableTasks = [], isLoading: executableLoading } = useQuery({
    queryKey: ['polymarketSignals', 'executable', mode],
    queryFn: () => tasksApi.getExecutable(30),
  })

  const { data: opportunityTasks = [], isLoading: opportunityLoading } = useQuery({
    queryKey: ['polymarketSignals', 'opportunity', mode],
    queryFn: () => tasksApi.getOpportunities(30, 3),
  })

  const { data: allowlistTasks = [], isLoading: allowlistLoading } = useQuery({
    queryKey: ['polymarketSignals', 'allowlist', mode],
    queryFn: () => tasksApi.getManualAllowlist(30, true),
  })

  return (
    <>
      <section className="panel strategy-context-panel">
        <div>
          <p className="eyebrow">Polymarket Copy</p>
          <h2>任务信号</h2>
          <p className="table-secondary">只展示跟单策略的任务队列和机会信号，不混入总览指标、因子或地址监控内容。</p>
        </div>
        <div className="strategy-context-meta">
          <span className="badge accent">POLYMARKET_COPY</span>
          <span className="badge">{mode}</span>
        </div>
      </section>

      <section className="panel">
        <div className="section-head">
          <h2>白名单任务</h2>
          <span className="badge">{allowlistTasks.length} 条</span>
        </div>
        {allowlistLoading ? <div className="loading">白名单任务加载中...</div> : <TasksTable tasks={allowlistTasks} type="executable" />}
      </section>

      <div className="split-grid">
        <section className="panel">
          <div className="section-head">
            <h2>可执行任务</h2>
          </div>
          {executableLoading ? <div className="loading">任务加载中...</div> : <TasksTable tasks={executableTasks} type="executable" />}
        </section>
        <section className="panel">
          <div className="section-head">
            <h2>机会看板</h2>
          </div>
          {opportunityLoading ? <div className="loading">机会加载中...</div> : <TasksTable tasks={opportunityTasks} type="opportunity" />}
        </section>
      </div>
    </>
  )
}
