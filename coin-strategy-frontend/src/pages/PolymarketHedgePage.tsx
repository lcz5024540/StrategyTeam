import { useQuery } from '@tanstack/react-query'
import { hedgeApi } from '@/api'
import { HedgePairsTable } from '@/components/tables/HedgePairsTable'
import { HedgeSettlingTable } from '@/components/tables/HedgeSettlingTable'
import { TimelineChart } from '@/components/charts/TimelineChart'
import { AttributionBars } from '@/components/charts/AttributionBars'
import type { HedgeDashboard } from '@/types/hedge'
import { formatMoney, formatPercent, formatNumber, getAmountClass } from '@/utils/format'
import type { DailyReport, Attribution } from '@/types/attribution'

// ── 指标卡（复用 MetricGrid 相同 DOM 结构）──────────────────────────────────
function HedgeMetricGrid({ dash }: { dash?: HedgeDashboard }) {
  if (!dash) {
    return (
      <section className="panel">
        <div className="empty-state">暂无 Polymarket 对冲策略指标数据</div>
      </section>
    )
  }

  const metrics = [
    {
      label: '净收益',
      value: formatMoney(dash.netPnlUsdc),
      hint: `已实现 ${formatMoney(dash.realizedPnlUsdc)}`,
      className: getAmountClass(dash.netPnlUsdc),
    },
    {
      label: '预期收益',
      value: formatMoney(dash.expectedProfitUsdc),
      hint: '所有开仓持仓预期利润总和',
      className: getAmountClass(dash.expectedProfitUsdc),
    },
    {
      label: '最大回撤',
      value: formatMoney(dash.maxDrawdownUsdc),
      hint: '历史最大回撤幅度',
      className: getAmountClass(-Math.abs(dash.maxDrawdownUsdc)),
    },
    {
      label: '当前持仓',
      value: formatNumber(dash.openPositionCount, 0),
      hint: `待结算 ${formatNumber(dash.settlingPositionCount, 0)}`,
      className: 'neutral' as const,
    },
    {
      label: '可用资金',
      value: formatMoney(dash.availableCapitalUsdc),
      hint: `总投入 ${formatMoney(dash.totalInvestedUsdc)}`,
      className: getAmountClass(dash.availableCapitalUsdc),
    },
    {
      label: '平均毛利率',
      value: formatPercent(dash.averageGrossEdgeRate),
      hint: '1 − P_YES − P_NO 均值',
      className: getAmountClass(dash.averageGrossEdgeRate),
    },
    {
      label: '手续费合计',
      value: formatMoney(dash.totalFeeUsdc),
      hint: '已扣除手续费总额',
      className: 'negative' as const,
    },
    {
      label: '胜率',
      value: formatPercent(dash.winRate),
      hint: `${formatNumber(dash.winCount, 0)} 胜 / ${formatNumber(dash.lossCount, 0)} 负`,
      className: getAmountClass(dash.winRate - 0.5),
    },
  ]

  return (
    <section className="panel">
      <div className="section-head">
        <h2>核心指标</h2>
      </div>
      <div className="metric-grid">
        {metrics.map((item) => (
          <article key={item.label} className="metric-card">
            <div className="label">{item.label}</div>
            <div className={`value ${item.className ?? 'neutral'}`}>{item.value}</div>
            <div className="hint">{item.hint}</div>
          </article>
        ))}
      </div>
    </section>
  )
}

// ── 套利日志表（时间线任务板）─────────────────────────────────────────────────
function ArbitrageLogTable() {
  const { data: logs = [], isLoading } = useQuery({
    queryKey: ['hedgeArbitrageLogs'],
    queryFn: () => hedgeApi.getArbitrageLogs(20),
  })

  if (isLoading) return <div className="loading">扫描日志加载中...</div>
  if (logs.length === 0) return <div className="empty-state">暂无套利扫描记录</div>

  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>时间</th>
            <th>市场</th>
            <th>YES价</th>
            <th>NO价</th>
            <th>毛利率</th>
            <th>动作</th>
            <th>备注</th>
          </tr>
        </thead>
        <tbody>
          {logs.map((log) => (
            <tr key={log.id}>
              <td>{log.createdAt ? new Date(log.createdAt).toLocaleString('zh-CN') : '-'}</td>
              <td title={log.question} style={{ maxWidth: 240 }}>
                {log.question?.length > 36 ? `${log.question.slice(0, 36)}…` : (log.question ?? '-')}
              </td>
              <td>{formatNumber(log.yesPrice, 3)}</td>
              <td>{formatNumber(log.noPrice, 3)}</td>
              <td className={log.grossEdgeRate > 0 ? 'positive' : 'neutral'}>
                {formatPercent(log.grossEdgeRate)}
              </td>
              <td>
                <span className={`badge ${log.action === 'OPEN' ? 'accent' : ''}`}>{log.action}</span>
              </td>
              <td title={log.message} style={{ maxWidth: 200, fontSize: '0.8em', color: 'var(--text-muted)' }}>
                {log.message?.length > 40 ? `${log.message.slice(0, 40)}…` : (log.message ?? '-')}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ── 全局风险面板 ──────────────────────────────────────────────────────────────
function RiskPanel() {
  const { data: risk } = useQuery({
    queryKey: ['hedgeRisk'],
    queryFn: () => hedgeApi.getRiskExposure(),
  })

  if (!risk) return <div className="empty-state">暂无风险数据</div>

  return (
    <div className="metric-grid" style={{ gridTemplateColumns: 'repeat(3, 1fr)' }}>
      {[
        { label: '开仓总数', value: formatNumber(risk.totalOpenPositions, 0), hint: '' },
        { label: '总锁定资金', value: formatMoney(risk.totalLockedUsdc), hint: '' },
        { label: '最大单仓', value: formatMoney(risk.largestSinglePositionUsdc), hint: '' },
        { label: '均毛利率', value: formatPercent(risk.averageEdgeRate), hint: '' },
        { label: '24h 到期', value: formatNumber(risk.expiringIn24hCount, 0), hint: '' },
        { label: '7d 到期', value: formatNumber(risk.expiringIn7dCount, 0), hint: '' },
      ].map((item) => (
        <article key={item.label} className="metric-card">
          <div className="label">{item.label}</div>
          <div className="value neutral">{item.value}</div>
          {item.hint && <div className="hint">{item.hint}</div>}
        </article>
      ))}
    </div>
  )
}

// ── 归因：将 hedge pairs 按市场聚合为 Attribution 格式 ─────────────────────────
function buildMarketAttribution(pairs: import('@/types/hedge').HedgePair[]): Attribution[] {
  const map = new Map<string, Attribution>()
  pairs.forEach((pair) => {
    const key = pair.marketId
    const existing = map.get(key)
    if (existing) {
      existing.netPnlUsdc += pair.realizedPnlUsdc
      existing.realizedPnlUsdc += pair.realizedPnlUsdc
      existing.totalVolumeUsdc += pair.totalCostUsdc
      existing.tradeCount += 1
    } else {
      map.set(key, {
        label: pair.question || pair.marketId,
        marketId: pair.marketId,
        question: pair.question,
        netPnlUsdc: pair.realizedPnlUsdc,
        realizedPnlUsdc: pair.realizedPnlUsdc,
        unrealizedPnlUsdc: pair.expectedProfitUsdc,
        pendingSettlementPnlUsdc: 0,
        totalVolumeUsdc: pair.totalCostUsdc,
        tradeCount: 1,
        winRate: 0,
      })
    }
  })
  return Array.from(map.values()).sort((a, b) => Math.abs(b.netPnlUsdc) - Math.abs(a.netPnlUsdc))
}

// ── 主页面 ────────────────────────────────────────────────────────────────────
export function PolymarketHedgePage() {
  const { data: dashboard, isLoading: dashLoading } = useQuery({
    queryKey: ['hedgeDashboard'],
    queryFn: () => hedgeApi.getDashboard(),
  })

  const { data: openPairs = [], isLoading: openLoading } = useQuery({
    queryKey: ['hedgeOpenPairs'],
    queryFn: () => hedgeApi.getOpenPairs(),
  })

  const { data: settlingPairs = [] } = useQuery({
    queryKey: ['hedgeSettlingPairs'],
    queryFn: () => hedgeApi.getSettlingPairs(),
  })

  // 将对冲仓位按日期聚合为伪 DailyReport，用于复用 TimelineChart
  const dailyReports: DailyReport[] = (() => {
    const allPairs = [...openPairs, ...settlingPairs]
    const map = new Map<string, DailyReport>()
    allPairs.forEach((pair) => {
      const date = pair.openedAt ? pair.openedAt.slice(0, 10) : '0000-00-00'
      const existing = map.get(date)
      if (existing) {
        existing.netPnlUsdc += pair.realizedPnlUsdc
        existing.realizedPnlUsdc += pair.realizedPnlUsdc
        existing.totalVolumeUsdc += pair.totalCostUsdc
        existing.openPositions += 1
        existing.winningPositions += pair.realizedPnlUsdc > 0 ? 1 : 0
        existing.losingPositions += pair.realizedPnlUsdc < 0 ? 1 : 0
        existing.profitPnlUsdc += pair.realizedPnlUsdc > 0 ? pair.realizedPnlUsdc : 0
        existing.lossPnlUsdc += pair.realizedPnlUsdc < 0 ? pair.realizedPnlUsdc : 0
      } else {
        map.set(date, {
          reportDate: date,
          executionAddress: '',
          mode: 'LIVE',
          openPositions: 1,
          closedPositions: 0,
          totalVolumeUsdc: pair.totalCostUsdc,
          feeUsdc: pair.feeUsdc,
          profitPnlUsdc: pair.realizedPnlUsdc > 0 ? pair.realizedPnlUsdc : 0,
          lossPnlUsdc: pair.realizedPnlUsdc < 0 ? pair.realizedPnlUsdc : 0,
          realizedPnlUsdc: pair.realizedPnlUsdc,
          unrealizedPnlUsdc: pair.expectedProfitUsdc,
          netPnlUsdc: pair.realizedPnlUsdc,
          winningPositions: pair.realizedPnlUsdc > 0 ? 1 : 0,
          losingPositions: pair.realizedPnlUsdc < 0 ? 1 : 0,
          winRate: 0,
        })
      }
    })
    return Array.from(map.values()).sort((a, b) => a.reportDate.localeCompare(b.reportDate))
  })()

  const marketAttribution = buildMarketAttribution([...openPairs, ...settlingPairs])

  if (dashLoading) {
    return <div className="loading">加载中...</div>
  }

  return (
    <>
      {/* 核心指标 */}
      <HedgeMetricGrid dash={dashboard} />

      {/* 时间线 + 风险敞口 */}
      <div className="split-grid">
        <section className="panel">
          <div className="section-head">
            <h2>收益时间线</h2>
          </div>
          <TimelineChart data={dailyReports} />
        </section>
        <section className="panel">
          <div className="section-head">
            <h2>风险敞口</h2>
          </div>
          <RiskPanel />
        </section>
      </div>

      {/* 任务板：套利扫描日志 */}
      <section className="panel">
        <div className="section-head">
          <h2>扫描日志</h2>
        </div>
        <ArbitrageLogTable />
      </section>

      {/* 归因分析 */}
      <section className="panel">
        <div className="section-head">
          <h2>市场归因</h2>
        </div>
        <AttributionBars data={marketAttribution} type="market" />
      </section>

      {/* 当前开仓对冲持仓 + 待结算 */}
      <div className="split-grid">
        <section className="panel">
          <div className="section-head">
            <h2>对冲持仓</h2>
          </div>
          <HedgePairsTable pairs={openPairs} loading={openLoading} />
        </section>
        <section className="panel">
          <div className="section-head">
            <h2>待结算</h2>
          </div>
          <HedgeSettlingTable pairs={settlingPairs} />
        </section>
      </div>

      {/* 全量订单（开仓 + 待结算合并展示） */}
      <section className="panel">
        <div className="section-head">
          <h2>订单历史</h2>
        </div>
        <HedgePairsTable pairs={[...openPairs, ...settlingPairs]} />
      </section>
    </>
  )
}
