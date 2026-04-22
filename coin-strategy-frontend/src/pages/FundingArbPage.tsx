import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { fundingArbApi, strategyApi } from '@/api'
import { TimelineChart } from '@/components/charts/TimelineChart'
import { useStrategyStore } from '@/store/useStrategyStore'
import { formatMoney, formatNumber, formatPercent, getAmountClass } from '@/utils/format'
import type { DailyReport } from '@/types/attribution'
import type { StrategyDetail } from '@/types/strategy'
import type {
  FundingArbExecuteParams,
  FundingArbExecutionSummary,
  FundingArbOrderView,
  FundingArbPositionView,
  FundingArbTimelinePoint,
} from '@/types/funding-arb'

const STRATEGY_CODE = 'FUNDING_ARB'

const DEFAULT_FORM: FundingArbExecuteParams = {
  symbols: 'BTCUSDT,ETHUSDT',
  amount: 50,
  minRate: 0.0005,
  leverage: 3,
}

function normalizeSymbols(raw: string): string[] {
  return Array.from(
    new Set(
      raw
        .split(',')
        .map((item) => item.trim().toUpperCase())
        .filter(Boolean)
    )
  )
}

function buildPayload(form: FundingArbExecuteParams): FundingArbExecuteParams {
  return {
    symbols: normalizeSymbols(form.symbols).join(','),
    amount: Number(form.amount),
    minRate: Number(form.minRate),
    leverage: Number(form.leverage),
  }
}

function validatePayload(payload: FundingArbExecuteParams): string | null {
  if (!payload.symbols) return '请至少输入一个交易对，多个交易对请用逗号分隔。'
  if (!Number.isFinite(payload.amount) || payload.amount <= 0) return '单币种投入金额必须大于 0。'
  if (!Number.isFinite(payload.minRate) || payload.minRate < 0) return '最小资金费率阈值不能小于 0。'
  if (!Number.isInteger(payload.leverage) || payload.leverage <= 0) return '杠杆倍数必须是正整数。'
  return null
}

function parseExecutionSummary(rawLog: string | null): FundingArbExecutionSummary | null {
  if (!rawLog) return null

  const summaryMarker = '=== Summary ==='
  const markerIndex = rawLog.indexOf(summaryMarker)
  if (markerIndex < 0) return null

  const items = rawLog
    .slice(markerIndex + summaryMarker.length)
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.includes('->'))
    .map((line) => {
      const [symbol, ...statusParts] = line.split('->')
      return {
        symbol: symbol.trim(),
        status: statusParts.join('->').trim(),
      }
    })
    .filter((item) => item.symbol && item.status)

  if (items.length === 0) return null

  const opened = items.filter((item) => /^Opened$/i.test(item.status)).length
  const failed = items.filter((item) => /^Failed/i.test(item.status)).length
  const skipped = items.length - opened - failed

  return {
    total: items.length,
    opened,
    skipped,
    failed,
    items,
  }
}

function buildTimelineReports(points: FundingArbTimelinePoint[], mode: string): DailyReport[] {
  return points
    .filter((point) => point.bucketAt)
    .map((point) => ({
      reportDate: point.bucketAt.slice(0, 10),
      executionAddress: '',
      mode,
      openPositions: 0,
      closedPositions: 0,
      totalVolumeUsdc: 0,
      feeUsdc: 0,
      realizedPnlUsdc: Number(point.netPnlUsdc || 0),
      unrealizedPnlUsdc: 0,
      netPnlUsdc: Number(point.netPnlUsdc || 0),
      winRate: 0,
    }))
}

function getStatusBadge(status: string): string {
  if (/^Opened$/i.test(status) || /^OPEN/i.test(status) || /^FILLED$/i.test(status)) return 'accent'
  if (/^Failed/i.test(status) || /^FAILED$/i.test(status) || /^CANCEL/i.test(status)) return 'danger'
  if (/^CLOSING$/i.test(status) || /^PARTIALLY/i.test(status) || /^Skipped$/i.test(status)) return 'warn'
  return ''
}

function FundingArbMetricGrid({
  detail,
  positions,
  orders,
  dailyReports,
  loading,
}: {
  detail?: StrategyDetail
  positions: FundingArbPositionView[]
  orders: FundingArbOrderView[]
  dailyReports: DailyReport[]
  loading: boolean
}) {
  if (loading && !detail) {
    return <div className="loading">FundingArb 监控指标加载中...</div>
  }

  if (!detail) {
    return <div className="empty-state">暂无资金费套利监控数据</div>
  }

  const latestDaily = dailyReports[dailyReports.length - 1]
  const openCostBasis = positions.reduce((sum, row) => sum + Number(row.costBasisUsdc || 0), 0)
  const metrics = [
    {
      label: '已实现 PnL',
      value: formatMoney(detail.realizedPnlUsdc),
      hint: '已合并资金费结算与平仓收益',
      className: getAmountClass(detail.realizedPnlUsdc),
    },
    {
      label: '累计净收益',
      value: formatMoney(detail.netPnlUsdc),
      hint: latestDaily ? `最新日收益 ${formatMoney(latestDaily.netPnlUsdc)}` : '暂无日级收益增量',
      className: getAmountClass(detail.netPnlUsdc),
    },
    {
      label: '当前持仓',
      value: formatNumber(detail.openPositions, 0),
      hint: `在途资金 ${formatMoney(openCostBasis)}`,
      className: 'neutral' as const,
    },
    {
      label: '已关闭仓位',
      value: formatNumber(detail.closedPositions, 0),
      hint: `胜 ${formatNumber(detail.winTrades, 0)} / 负 ${formatNumber(detail.lossTrades, 0)}`,
      className: 'neutral' as const,
    },
    {
      label: '累计订单数',
      value: formatNumber(detail.totalTrades, 0),
      hint: `当前面板展示最近 ${formatNumber(orders.length, 0)} 条`,
      className: 'neutral' as const,
    },
    {
      label: '胜率',
      value: formatPercent(detail.winRate),
      hint: '仅对已关闭仓位统计',
      className: getAmountClass(detail.winRate - 0.5),
    },
    {
      label: '最大回撤',
      value: formatMoney(-Math.abs(detail.maxDrawdownUsdc)),
      hint: '按收益时间线累计曲线估算',
      className: getAmountClass(-Math.abs(detail.maxDrawdownUsdc)),
    },
    {
      label: '当前未实现',
      value: formatMoney(detail.unrealizedPnlUsdc),
      hint: '后端暂未提供实时 MTM，当前通常为 0',
      className: getAmountClass(detail.unrealizedPnlUsdc),
    },
  ]

  return (
    <div className="metric-grid">
      {metrics.map((item) => (
        <article key={item.label} className="metric-card">
          <div className="label">{item.label}</div>
          <div className={`value ${item.className}`}>{item.value}</div>
          <div className="hint">{item.hint}</div>
        </article>
      ))}
    </div>
  )
}

function FundingArbPositionsTable({ rows, loading }: { rows: FundingArbPositionView[]; loading: boolean }) {
  if (loading) return <div className="loading">持仓列表加载中...</div>
  if (rows.length === 0) return <div className="empty-state">当前没有 OPENING / OPENED / CLOSING 持仓</div>

  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>持仓</th>
            <th>结构</th>
            <th>状态</th>
            <th>数量</th>
            <th>开仓价</th>
            <th>成本</th>
            <th>已实现 PnL</th>
            <th>下一结算窗口</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id}>
              <td>
                <div className="funding-arb-cell-title">{row.question}</div>
                <div className="funding-arb-cell-subtitle">{row.marketId}</div>
              </td>
              <td>{row.outcome}</td>
              <td>
                <span className={`badge ${getStatusBadge(row.status)}`}>{row.status}</span>
              </td>
              <td>{formatNumber(row.quantity, 6)}</td>
              <td>{formatNumber(row.averageEntryPrice, 4)}</td>
              <td>{formatMoney(row.costBasisUsdc)}</td>
              <td className={getAmountClass(row.realizedPnlUsdc)}>{formatMoney(row.realizedPnlUsdc)}</td>
              <td>{row.marketEndAtBjt || '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function FundingArbOrdersTable({ rows, loading }: { rows: FundingArbOrderView[]; loading: boolean }) {
  if (loading) return <div className="loading">订单明细加载中...</div>
  if (rows.length === 0) return <div className="empty-state">暂无订单明细</div>

  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>时间</th>
            <th>Symbol</th>
            <th>腿</th>
            <th>方向</th>
            <th>状态</th>
            <th>价格</th>
            <th>数量</th>
            <th>名义金额</th>
            <th>手续费</th>
            <th>备注</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id}>
              <td>{row.placedAt || '-'}</td>
              <td>
                <div className="funding-arb-cell-title">{row.question}</div>
                <div className="funding-arb-cell-subtitle">{row.executionMode}</div>
              </td>
              <td>{row.outcome}</td>
              <td>{row.side || '-'}</td>
              <td>
                <span className={`badge ${getStatusBadge(row.status)}`}>{row.status}</span>
              </td>
              <td>{formatNumber(row.price, 4)}</td>
              <td>{formatNumber(row.size, 6)}</td>
              <td>{formatMoney(row.notionalUsdc)}</td>
              <td className={getAmountClass(-Math.abs(row.estimatedFeeUsdc || 0))}>
                {formatMoney(-(Math.abs(row.estimatedFeeUsdc || 0)))}
              </td>
              <td title={row.exitReason || ''}>{row.exitReason || '-'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export function FundingArbPage() {
  const queryClient = useQueryClient()
  const { setSelectedStrategy, positionMode, selectedDays } = useStrategyStore()
  const [form, setForm] = useState<FundingArbExecuteParams>(DEFAULT_FORM)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [rawLog, setRawLog] = useState<string | null>(null)

  useEffect(() => {
    setSelectedStrategy(STRATEGY_CODE)
  }, [setSelectedStrategy])

  const normalizedSymbols = useMemo(() => normalizeSymbols(form.symbols), [form.symbols])
  const executionSummary = useMemo(() => parseExecutionSummary(rawLog), [rawLog])

  const { data: summary, isLoading: summaryLoading, error: summaryError } = useQuery({
    queryKey: ['fundingArb', 'summary', positionMode],
    queryFn: () => strategyApi.getSummary(STRATEGY_CODE, positionMode) as Promise<StrategyDetail>,
  })

  const { data: positions = [], isLoading: positionsLoading, error: positionsError } = useQuery({
    queryKey: ['fundingArb', 'positions', positionMode],
    queryFn: async () =>
      (await strategyApi.getPositions(STRATEGY_CODE, positionMode)) as unknown as FundingArbPositionView[],
  })

  const { data: orders = [], isLoading: ordersLoading, error: ordersError } = useQuery({
    queryKey: ['fundingArb', 'orders', positionMode],
    queryFn: async () =>
      (await strategyApi.getOrders(STRATEGY_CODE, 30, positionMode)) as unknown as FundingArbOrderView[],
  })

  const { data: timeline = [], isLoading: timelineLoading, error: timelineError } = useQuery({
    queryKey: ['fundingArb', 'timeline', positionMode, selectedDays],
    queryFn: async () =>
      (await strategyApi.getTimeline(STRATEGY_CODE, selectedDays, positionMode)) as unknown as FundingArbTimelinePoint[],
  })

  const dailyReports = useMemo(
    () => buildTimelineReports(timeline, positionMode),
    [positionMode, timeline]
  )

  const monitorErrorMessage = [summaryError, positionsError, ordersError, timelineError]
    .find((item) => item instanceof Error)
  const monitorLoading = summaryLoading || positionsLoading || ordersLoading || timelineLoading

  const handleFieldChange = <K extends keyof FundingArbExecuteParams>(key: K, value: FundingArbExecuteParams[K]) => {
    setForm((current) => ({
      ...current,
      [key]: value,
    }))
  }

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()

    const payload = buildPayload(form)
    const validationError = validatePayload(payload)
    if (validationError) {
      setError(validationError)
      return
    }

    setSubmitting(true)
    setError(null)

    try {
      const log = await fundingArbApi.execute(payload)
      setForm(payload)
      setRawLog(log)
      await queryClient.invalidateQueries({ queryKey: ['fundingArb'] })
    } catch (submitError) {
      setError(submitError instanceof Error ? submitError.message : '执行请求失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      <section className="panel">
        <div className="section-head">
          <h2>策略定位</h2>
          <p>页面上半区负责人工执行，下半区直接展示后端已落库的持仓、订单、收益时间线和已实现 PnL 聚合。</p>
        </div>
        <div className="metric-grid">
          <article className="metric-card">
            <div className="label">支持交易所</div>
            <div className="value neutral">BINANCE</div>
            <div className="hint">单交易所现货多 + 合约空</div>
          </article>
          <article className="metric-card">
            <div className="label">执行入口</div>
            <div className="value neutral">MANUAL TRIGGER</div>
            <div className="hint">支持批量 symbol 输入并触发后端执行</div>
          </article>
          <article className="metric-card">
            <div className="label">监控能力</div>
            <div className="value neutral">POSITIONS / ORDERS / PNL</div>
            <div className="hint">页面已接通持仓、订单、时间线和已实现收益聚合</div>
          </article>
          <article className="metric-card">
            <div className="label">当前口径</div>
            <div className="value neutral">{positionMode}</div>
            <div className="hint">当前展示最近 {formatNumber(selectedDays, 0)} 天收益时间线</div>
          </article>
        </div>
      </section>

      <div className="split-grid funding-arb-main-grid">
        <section className="panel">
          <div className="section-head">
            <h2>执行控制台</h2>
            <p>按资金费率阈值手动触发 Binance 资金费套利，支持单次批量评估多个交易对。</p>
          </div>
          <form className="funding-arb-form" onSubmit={handleSubmit}>
            <label className="funding-arb-field">
              <span>交易对列表</span>
              <textarea
                value={form.symbols}
                onChange={(event) => handleFieldChange('symbols', event.target.value)}
                placeholder="BTCUSDT,ETHUSDT"
                rows={4}
              />
              <small>多个 symbol 用英文逗号分隔；提交前会自动大写、去重、去空格。</small>
            </label>

            <div className="funding-arb-form-grid">
              <label className="funding-arb-field">
                <span>单币种投入金额</span>
                <input
                  type="number"
                  min="0"
                  step="0.01"
                  value={form.amount}
                  onChange={(event) => handleFieldChange('amount', Number(event.target.value))}
                />
                <small>单位 USDT，按每个 symbol 单独下发。</small>
              </label>

              <label className="funding-arb-field">
                <span>最小资金费率阈值</span>
                <input
                  type="number"
                  min="0"
                  step="0.0001"
                  value={form.minRate}
                  onChange={(event) => handleFieldChange('minRate', Number(event.target.value))}
                />
                <small>低于该阈值时后端会直接跳过。</small>
              </label>

              <label className="funding-arb-field">
                <span>杠杆倍数</span>
                <input
                  type="number"
                  min="1"
                  step="1"
                  value={form.leverage}
                  onChange={(event) => handleFieldChange('leverage', Number(event.target.value))}
                />
                <small>仅作用于期货腿；非正整数会被前端拦截。</small>
              </label>
            </div>

            <div className="funding-arb-inline-summary">
              <span className="badge accent">{normalizedSymbols.length || 0} 个标的</span>
              <span className="badge">{normalizedSymbols.join(', ') || '等待输入'}</span>
              <span className="badge">Amount {formatNumber(form.amount, 2)} USDT</span>
              <span className="badge">MinRate {formatNumber(form.minRate, 4)}</span>
              <span className="badge">Leverage {formatNumber(form.leverage, 0)}x</span>
            </div>

            {error ? <div className="funding-arb-error">{error}</div> : null}

            <div className="funding-arb-actions">
              <button type="submit" className="btn btn-primary" disabled={submitting}>
                {submitting ? '执行中...' : '执行套利'}
              </button>
              <button
                type="button"
                className="btn btn-secondary"
                disabled={submitting}
                onClick={() => {
                  setForm(DEFAULT_FORM)
                  setError(null)
                }}
              >
                重置参数
              </button>
            </div>
          </form>
        </section>

        <section className="panel">
          <div className="section-head">
            <h2>监控说明</h2>
            <p>监控面板以后端落库记录为准，当前已接通持仓、订单、资金费收益时间线与已实现 PnL 聚合。</p>
          </div>
          <div className="funding-arb-note-list">
            <article className="funding-arb-note-card">
              <h3>数据来源</h3>
              <p>持仓来自单交易所套利持仓表，订单来自下单明细表，收益时间线按资金费结算与平仓收益按日聚合。</p>
            </article>
            <article className="funding-arb-note-card">
              <h3>PnL 口径</h3>
              <p>已实现 PnL = 资金费结算累计 + 已关闭仓位 realizedPnl；未实现 MTM 当前未接入，因此通常为 0。</p>
            </article>
            <article className="funding-arb-note-card">
              <h3>模式说明</h3>
              <p>当前 FundingArb 页面复用同一套套利库表，PAPER / LIVE 切换不会切分数据范围，但时间线天数选择仍会生效。</p>
            </article>
            <article className="funding-arb-note-card">
              <h3>执行刷新</h3>
              <p>每次手动执行成功后，页面会自动刷新摘要、持仓、订单和收益时间线，不需要手动刷新浏览器。</p>
            </article>
          </div>
          {monitorErrorMessage instanceof Error ? (
            <div className="funding-arb-error" style={{ marginTop: '1rem' }}>
              监控数据加载失败：{monitorErrorMessage.message}
            </div>
          ) : null}
        </section>
      </div>

      <section className="panel">
        <div className="section-head">
          <h2>已实现 PnL 聚合</h2>
          <p>聚合后端记录的资金费结算与平仓收益，快速查看当前套利组合的收益结构。</p>
        </div>
        <FundingArbMetricGrid
          detail={summary}
          positions={positions}
          orders={orders}
          dailyReports={dailyReports}
          loading={monitorLoading}
        />
      </section>

      <div className="split-grid funding-arb-monitor-grid">
        <section className="panel">
          <div className="section-head">
            <h2>收益时间线</h2>
            <p>按日聚合资金费结算与平仓收益，展示 FundingArb 的累计收益变化。</p>
          </div>
          {timelineLoading && timeline.length === 0 ? (
            <div className="loading">收益时间线加载中...</div>
          ) : (
            <TimelineChart data={dailyReports} />
          )}
        </section>

        <section className="panel">
          <div className="section-head">
            <h2>执行摘要</h2>
            <p>解析最近一次手动执行返回的 Summary 段，快速对照本次触发结果。</p>
          </div>
          {executionSummary ? (
            <>
              <div className="metric-grid funding-arb-summary-grid">
                <article className="metric-card">
                  <div className="label">总标的数</div>
                  <div className="value neutral">{executionSummary.total}</div>
                  <div className="hint">本次 Summary 返回的 symbol 总数</div>
                </article>
                <article className="metric-card">
                  <div className="label">Opened</div>
                  <div className="value positive">{executionSummary.opened}</div>
                  <div className="hint">后端确认已开仓</div>
                </article>
                <article className="metric-card">
                  <div className="label">Skipped</div>
                  <div className="value neutral">{executionSummary.skipped}</div>
                  <div className="hint">阈值不足、风控拒绝等未执行情形</div>
                </article>
                <article className="metric-card">
                  <div className="label">Failed</div>
                  <div className="value negative">{executionSummary.failed}</div>
                  <div className="hint">执行异常或下单失败</div>
                </article>
              </div>
              <div className="table-wrap" style={{ marginTop: '1rem' }}>
                <table>
                  <thead>
                    <tr>
                      <th>Symbol</th>
                      <th>结果</th>
                    </tr>
                  </thead>
                  <tbody>
                    {executionSummary.items.map((item) => (
                      <tr key={`${item.symbol}-${item.status}`}>
                        <td>{item.symbol}</td>
                        <td>
                          <span className={`badge ${getStatusBadge(item.status)}`}>{item.status}</span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          ) : (
            <div className="empty-state">尚未产生可解析的执行摘要，先提交一次执行请求。</div>
          )}
        </section>
      </div>

      <div className="split-grid funding-arb-monitor-grid">
        <section className="panel">
          <div className="section-head">
            <h2>持仓列表</h2>
            <p>展示当前 OPENING、OPENED、CLOSING 状态的套利持仓。</p>
          </div>
          <FundingArbPositionsTable rows={positions} loading={positionsLoading} />
        </section>

        <section className="panel">
          <div className="section-head">
            <h2>订单明细</h2>
            <p>展示最近 30 条套利订单，便于排查各腿的下单状态、手续费和异常信息。</p>
          </div>
          <FundingArbOrdersTable rows={orders} loading={ordersLoading} />
        </section>
      </div>

      <section className="panel">
        <div className="section-head">
          <h2>原始执行日志</h2>
          <p>保留后端原始返回，便于核对资金费率、计划价格、风控拒绝原因和最终执行结果。</p>
        </div>
        {rawLog ? (
          <pre className="funding-arb-log">
            <code>{rawLog}</code>
          </pre>
        ) : (
          <div className="empty-state">执行日志为空，提交请求后会在这里展示后端原文。</div>
        )}
      </section>
    </>
  )
}
