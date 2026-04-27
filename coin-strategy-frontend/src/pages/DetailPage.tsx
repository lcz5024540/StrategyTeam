import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
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
import { DEFAULT_STRATEGY_CODE } from '@/utils/constants'

const EXPORT_LIMIT = 5000

function downloadBlob(blob: Blob, filename: string) {
  const url = window.URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  window.URL.revokeObjectURL(url)
}

export function DetailPage() {
  const [searchParams] = useSearchParams()
  const { positionMode, selectedDays } = useStrategyStore()

  const strategy = DEFAULT_STRATEGY_CODE
  const days = Number(searchParams.get('days')) || selectedDays
  const mode = (searchParams.get('mode') || positionMode) as 'PAPER' | 'LIVE'
  const [exportingKey, setExportingKey] = useState<string | null>(null)
  const [exportMessage, setExportMessage] = useState<string>('')
  const [exportError, setExportError] = useState<string>('')

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

  const { data: pnlTimeline = [], isLoading: timelineLoading } = useQuery({
    queryKey: ['pnlTimeline', days, 24, mode],
    queryFn: () => reportsApi.getPnlTimeline(Math.max(days, 1), 24, mode),
  })

  if (isLoading || timelineLoading) {
    return <div className="loading">加载中...</div>
  }

  const showLiveEmptyHint =
    mode === 'LIVE' &&
    detail &&
    detail.totalTrades === 0 &&
    positions.length === 0 &&
    settlements.length === 0

  const exportName = (name: string) => `smart_wallet_${name}_${mode.toLowerCase()}.csv`
  const handleExport = async (key: string, filename: string, run: () => Promise<Blob>) => {
    try {
      setExportingKey(key)
      setExportError('')
      setExportMessage(`正在导出 ${filename} ...`)
      const blob = await run()
      downloadBlob(blob, filename)
      setExportMessage(`导出完成：${filename}`)
    } catch (error) {
      const message = error instanceof Error ? error.message : '导出失败'
      setExportError(message)
      setExportMessage('')
    } finally {
      setExportingKey(null)
    }
  }

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

      {exportMessage ? <section className="export-status">{exportMessage}</section> : null}
      {exportError ? <section className="export-status export-status-error">{exportError}</section> : null}

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

      <div className="split-grid">
        <section className="panel">
          <div className="section-head section-head-row">
            <div>
              <h2>当前持仓</h2>
            </div>
            <button
              type="button"
              className="btn btn-secondary"
              disabled={exportingKey !== null}
              onClick={() => handleExport(
                'live_positions',
                exportName('live_positions'),
                () => walletApi.exportLivePositionsCsv(mode),
              )}
            >
              {exportingKey === 'live_positions' ? '导出中...' : '导出 CSV'}
            </button>
          </div>
          <PositionsTable positions={positions} />
        </section>
        <section className="panel">
          <div className="section-head section-head-row">
            <div>
              <h2>待结算</h2>
            </div>
            <button
              type="button"
              className="btn btn-secondary"
              disabled={exportingKey !== null}
              onClick={() => handleExport(
                'next_settlements',
                exportName('next_settlements'),
                () => walletApi.exportNextSettlementsCsv(EXPORT_LIMIT, mode),
              )}
            >
              {exportingKey === 'next_settlements' ? '导出中...' : '导出 CSV'}
            </button>
          </div>
          <SettlementsTable positions={settlements} />
        </section>
      </div>

      <div className="split-grid">
        <section className="panel">
          <div className="section-head">
            <h2>收益时间线</h2>
          </div>
          <TimelineChart data={pnlTimeline} bucketHours={24} />
        </section>
        <section className="panel">
          <div className="section-head">
            <h2>收益分布</h2>
          </div>
          <DailyPnlBars data={pnlTimeline} bucketHours={24} />
        </section>
      </div>

      <section className="panel">
        <div className="section-head section-head-row">
          <div>
            <h2>交易历史</h2>
          </div>
          <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
            <button
              type="button"
              className="btn btn-secondary"
              disabled={exportingKey !== null}
              onClick={() => handleExport(
                'orders',
                exportName('orders'),
                () => walletApi.exportOrdersCsv(EXPORT_LIMIT, mode),
              )}
            >
              {exportingKey === 'orders' ? '导出中...' : '导出订单'}
            </button>
            <button
              type="button"
              className="btn btn-secondary"
              disabled={exportingKey !== null}
              onClick={() => handleExport(
                'fills',
                exportName('fills'),
                () => walletApi.exportFillsCsv(EXPORT_LIMIT, mode),
              )}
            >
              {exportingKey === 'fills' ? '导出中...' : '导出成交'}
            </button>
            <button
              type="button"
              className="btn btn-secondary"
              disabled={exportingKey !== null}
              onClick={() => handleExport(
                'position_history',
                exportName('position_history'),
                () => walletApi.exportPositionHistoryCsv(false, mode),
              )}
            >
              {exportingKey === 'position_history' ? '导出中...' : '导出持仓历史'}
            </button>
          </div>
        </div>
        <OrdersTable mode={mode} />
      </section>
    </>
  )
}
