import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { reportsApi } from '@/api'
import { useStrategyStore } from '@/store/useStrategyStore'
import { TimelineChart } from '@/components/charts/TimelineChart'
import { DailyPnlBars } from '@/components/charts/DailyPnlBars'
import { WinRateTrend } from '@/components/charts/WinRateTrend'
import { OutcomeTrendChart } from '@/components/charts/OutcomeTrendChart'
import { OrderPnlCategoryAttributionChart } from '@/components/charts/OrderPnlCategoryAttributionChart'
import { OrdersTable } from '@/components/tables/OrdersTable'
import { FollowAddressDailyTable } from '@/components/tables/FollowAddressDailyTable'
import { TABLE_FETCH_LIMIT } from '@/utils/table'

const BUCKET_OPTIONS = [
  { hours: 8, label: '8 小时' },
  { hours: 12, label: '12 小时' },
  { hours: 24, label: '1 天' },
  { hours: 72, label: '3 天' },
]

function normalizeBucketHours(value: number) {
  return BUCKET_OPTIONS.some((item) => item.hours === value) ? value : 24
}

export function ChartsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { positionMode, selectedDays } = useStrategyStore()

  const mode = (searchParams.get('mode') || positionMode) as 'PAPER' | 'LIVE'
  const days = Number(searchParams.get('days')) || selectedDays
  const bucketHours = normalizeBucketHours(Number(searchParams.get('bucketHours')) || 24)
  const bucketIndex = BUCKET_OPTIONS.findIndex((item) => item.hours === bucketHours)

  const updateBucketHours = (nextBucketHours: number) => {
    const newParams = new URLSearchParams(searchParams)
    newParams.set('bucketHours', String(normalizeBucketHours(nextBucketHours)))
    setSearchParams(newParams)
  }

  const zoomBucket = (direction: 'in' | 'out') => {
    const nextIndex = direction === 'in'
      ? Math.max(0, bucketIndex - 1)
      : Math.min(BUCKET_OPTIONS.length - 1, bucketIndex + 1)
    updateBucketHours(BUCKET_OPTIONS[nextIndex].hours)
  }

  const { data: dailyReports = [], isLoading } = useQuery({
    queryKey: ['dailyReports', days, mode],
    queryFn: () => reportsApi.getDailyReports(Math.max(days, 7), mode),
  })

  const { data: pnlTimeline = [], isLoading: isTimelineLoading } = useQuery({
    queryKey: ['pnlTimeline', days, bucketHours, mode],
    queryFn: () => reportsApi.getPnlTimeline(Math.max(days, 1), bucketHours, mode),
  })

  const { data: orderPnlCategoryAttribution } = useQuery({
    queryKey: ['orderPnlCategoryAttribution', mode],
    queryFn: () => reportsApi.getOrderPnlCategoryAttribution(12, mode),
  })

  const { data: followAddressDailyReports = [] } = useQuery({
    queryKey: ['followAddressDailyReports', mode],
    queryFn: () => reportsApi.getFollowAddressDailyReports(TABLE_FETCH_LIMIT, mode),
  })

  const { data: conflicts = [] } = useQuery({
    queryKey: ['conflicts'],
    queryFn: () => reportsApi.getConflictingMarkets(20, true),
  })

  if (isLoading || isTimelineLoading) {
    return <div className="loading">加载中...</div>
  }

  const showLiveEmptyHint =
    mode === 'LIVE' &&
    pnlTimeline.length === 0 &&
    dailyReports.length === 0 &&
    (orderPnlCategoryAttribution?.windows || []).every((window) =>
      (window.marketCategories || []).length === 0 && (window.walletCategories || []).length === 0
    ) &&
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
        <div className="section-head section-head-row">
          <div>
            <h2>收益时间线</h2>
          </div>
          <div className="chart-controls" aria-label="收益图时间粒度">
            <button
              type="button"
              className="btn btn-secondary chart-zoom-btn"
              onClick={() => zoomBucket('in')}
              disabled={bucketIndex === 0}
              title="缩小时间间隔"
            >
              放大
            </button>
            <div className="segmented-control">
              {BUCKET_OPTIONS.map((item) => (
                <button
                  key={item.hours}
                  type="button"
                  className={item.hours === bucketHours ? 'active' : ''}
                  onClick={() => updateBucketHours(item.hours)}
                >
                  {item.label}
                </button>
              ))}
            </div>
            <button
              type="button"
              className="btn btn-secondary chart-zoom-btn"
              onClick={() => zoomBucket('out')}
              disabled={bucketIndex === BUCKET_OPTIONS.length - 1}
              title="放大时间间隔"
            >
              缩小
            </button>
          </div>
        </div>
        <TimelineChart data={pnlTimeline} bucketHours={bucketHours} />
      </section>

      {/* 日收益和胜率 */}
      <div className="split-grid">
        <section className="panel">
          <div className="section-head">
            <h2>区间收益</h2>
          </div>
          <DailyPnlBars data={pnlTimeline} bucketHours={bucketHours} />
        </section>
        <section className="panel">
          <div className="section-head">
            <h2>胜率趋势</h2>
          </div>
          <WinRateTrend data={dailyReports} />
        </section>
      </div>

      <section className="panel">
        <div className="section-head">
          <h2>胜负趋势</h2>
        </div>
        <OutcomeTrendChart data={dailyReports} />
      </section>

      <section className="panel">
        <div className="section-head">
          <h2>市场类型盈亏订单归因</h2>
        </div>
        <OrderPnlCategoryAttributionChart data={orderPnlCategoryAttribution} />
      </section>

      <section className="panel">
        <div className="section-head">
          <h2>地址分析</h2>
        </div>
        <FollowAddressDailyTable data={followAddressDailyReports} />
      </section>

      {/* 冲突市场 */}
      <section className="panel">
        <div className="section-head">
          <h2>冲突市场</h2>
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
        </div>
        <OrdersTable mode={mode} />
      </section>
    </>
  )
}
