import { useEffect, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { arbitrageDashboardApi } from '@/api'
import type {
  ArbitrageMode,
  FundingRateMatrixRow,
  HtxTrendDashboardResponse,
  TrendAccountSummary,
  TrendDimensionPnl,
  TrendLedgerEvent,
  TrendCandidateSignal,
  TrendOrder,
  TrendPosition,
} from '@/types/arbitrage-dashboard'

const REFRESH_MS = 10_000
const MODES: Array<{ value: ArbitrageMode; label: string; tag: string }> = [
  { value: 'CROSS_PRICE', label: '跨所套利', tag: '观察' },
  { value: 'CROSS_FUNDING', label: '对冲套利', tag: '观察' },
  { value: 'HTX_TREND', label: 'HTX 趋势交易', tag: '只读' },
  { value: 'HTX_GRID', label: '网格交易', tag: '暂停' },
]
const TREND_VIEWS = [
  { value: 'overview', label: '驾驶舱' },
  { value: 'history', label: '交易历史' },
  { value: 'quality', label: '收益归因' },
  { value: 'candidates', label: '候选观察' },
] as const

type TrendView = typeof TREND_VIEWS[number]['value']
type PositionSortKey = 'updatedAt' | 'pnl' | 'turnover' | 'fee' | 'symbol'
type OrderSortKey = 'updatedAt' | 'notional' | 'fee' | 'state' | 'symbol'
type AccountFilter = 'ALL' | 'ACTIVE' | 'TRADED' | 'WATCH' | 'MIRROR'
type AccountSortKey = 'priority' | 'pnl' | 'turnover' | 'orders' | 'latest' | 'name'

const EMPTY_FUNDING_MATRIX: { exchanges: string[]; items: FundingRateMatrixRow[] } = {
  exchanges: ['BINANCE', 'OKX', 'HTX', 'BITGET', 'HYPERLIQUID'],
  items: [],
}

const UNSUPPORTED_MODES: Record<Exclude<ArbitrageMode, 'CROSS_FUNDING' | 'HTX_TREND'>, { title: string; description: string; next: string }> = {
  CROSS_PRICE: {
    title: '跨所价格真实读模型未接入',
    description: '当前前端已隐藏示例 ETH/SOL 价差，不再把 demo VWAP、容量或收益展示成真实机会。',
    next: '后续需要后端提供跨所 order book/VWAP 快照、成本模型、稳定窗口和机会列表后再开放。',
  },
  SINGLE_EXCHANGE: {
    title: '同所期现 / 永续扫描未接入当前看板',
    description: '当前前端已隐藏示例 BTC 期现机会，不再展示“可执行”demo 行。',
    next: '同所期现真实持仓请先以资金费套利专页和策略持仓接口为准，等读模型补齐后再汇总到本页。',
  },
  HTX_GRID: {
    title: '网格交易已暂停展示',
    description: '网格策略当前未接入与趋势账本一致的真实读模型；为避免将旧数据或模拟数据误认为实盘，不展示订单与收益数字。',
    next: '待补齐网格成交账本、库存与日度盈亏读模型后，在此页签开放真实指标。',
  },
}

function valueTone(value: number) { return value > 0 ? 'positive' : value < 0 ? 'negative' : 'neutral' }
function parseMode(value: string | null): ArbitrageMode { return MODES.some((mode) => mode.value === value) ? value as ArbitrageMode : 'CROSS_FUNDING' }
function parseTrendView(value: string | null): TrendView { return TREND_VIEWS.some((item) => item.value === value) ? value as TrendView : 'overview' }

export function ArbitrageScannerPage() {
  const [params, setParams] = useSearchParams()
  const mode = parseMode(params.get('mode'))
  const executionMode = params.get('executionMode') === 'PAPER' ? 'PAPER' : 'LIVE'
  const trendView = parseTrendView(params.get('view'))
  const [symbol, setSymbol] = useState(params.get('symbol') || '')
  const [exchange, setExchange] = useState(params.get('exchange') || 'ALL')
  const [minCapacity, setMinCapacity] = useState(Number(params.get('minCapacity') || 0))
  const [minYield, setMinYield] = useState(Number(params.get('minYield') || 0))
  const [qualifiedOnly, setQualifiedOnly] = useState(params.get('qualified') === '1')
  const { data: matrixResponse, isError, refetch, isFetching } = useQuery({ queryKey: ['fundingRateMatrix'], queryFn: arbitrageDashboardApi.getFundingRateMatrix, refetchInterval: REFRESH_MS, retry: false })
  const { data: trendResponse, isError: trendError, refetch: refetchTrend, isFetching: isFetchingTrend } = useQuery({ queryKey: ['htxTrendDashboard', executionMode], queryFn: () => arbitrageDashboardApi.getHtxTrendDashboard(executionMode), refetchInterval: REFRESH_MS, retry: false, enabled: mode === 'HTX_TREND' })
  const fundingMatrix = matrixResponse?.data || EMPTY_FUNDING_MATRIX
  const isFundingMode = mode === 'CROSS_FUNDING'
  const isTrendMode = mode === 'HTX_TREND'
  const fundingRows = useMemo(() => fundingMatrix.items
    .filter((row) => (!symbol || row.symbol.includes(symbol.toUpperCase()))
      && (exchange === 'ALL' || row.ratesByExchange[exchange] != null)
      && (row.executableNotional || 0) >= minCapacity
      && (minYield <= 0 || (row.estimatedNetYield || 0) >= minYield / 100)
      && (!qualifiedOnly || isExecutableStatus(row.status)))
    .sort(compareFundingOpportunityDescending),
  [exchange, fundingMatrix.items, minCapacity, minYield, qualifiedOnly, symbol])
  const executableFundingRows = fundingRows.filter((row) => isExecutableStatus(row.status))
  const bestFunding = executableFundingRows[0] || fundingRows[0]
  const largestFunding = fundingRows.reduce<FundingRateMatrixRow | undefined>((current, row) => !current || (row.executableNotional || 0) > (current.executableNotional || 0) ? row : current, undefined)
  const trend = normalizeTrendDashboardResponse(trendResponse)
  const sourceStatus = isTrendMode ? trendSourceStatus(trend) : isFundingMode && matrixResponse ? '跨所资金费读模型 · 在线' : '未接入该模式真实读模型'
  const sourceDetail = isTrendMode ? trendSourceDetail(trend) : isFundingMode
    ? `${matrixResponse?.source || '等待 funding-rate-matrix'}${(matrixResponse?.partial ?? false) ? ` · 部分数据：${(matrixResponse?.missingExchanges || []).join('、') || '未知'}` : ''}`
    : '已隐藏前端示例数据，等待后端只读接口'
  const exchangeCoverage = fundingMatrix.exchanges.map((name) => ({
    name,
    count: fundingMatrix.items.filter((row) => row.ratesByExchange[name] != null).length,
  }))

  const updateMode = (next: ArbitrageMode) => {
    setParams((current) => {
      const nextParams = new URLSearchParams(current)
      nextParams.set('mode', next)
      if (next === 'HTX_TREND') nextParams.set('view', parseTrendView(nextParams.get('view')))
      else nextParams.delete('view')
      return nextParams
    }, { replace: true })
  }
  const updateTrendView = (next: TrendView) => {
    setParams((current) => {
      const nextParams = new URLSearchParams(current)
      nextParams.set('mode', 'HTX_TREND')
      nextParams.set('view', next)
      return nextParams
    }, { replace: true })
  }
  const resetFilters = () => {
    setSymbol('')
    setExchange('ALL')
    setMinCapacity(0)
    setMinYield(0)
    setQualifiedOnly(false)
    setParams({ mode })
  }
  const copyView = async () => { await navigator.clipboard?.writeText(location.href) }

  return <div className="ms-page arb-page">
    <section className="arb-hero">
      <div>
        <h1>{isTrendMode ? 'HTX 趋势策略' : '策略控制台'}</h1>
        {!isTrendMode ? <p>{isFundingMode ? '跨所资金费按标准币对聚合，按真实接口展示可执行机会。' : UNSUPPORTED_MODES[mode].description}</p> : null}
      </div>
      {!isTrendMode ? <div className="arb-source"><span>数据来源</span><strong>{sourceStatus}</strong><small>{sourceDetail}</small></div> : null}
    </section>
    {!isTrendMode ? <section className="arb-metrics">
      <Metric label={isTrendMode ? '今日交易量' : '可执行机会'} value={isTrendMode ? formatNotional(trend?.summary.todayTurnover) : isFundingMode ? String(executableFundingRows.length) : '0'} detail={isTrendMode ? `累计 ${formatNotional(trend?.summary.totalTurnover)}` : isFundingMode ? '满足 L2、成本和同步结算条件' : '未接入真实读模型，不展示 demo'} tone={isTrendMode ? 'neutral' : isFundingMode ? 'positive' : 'neutral'} />
      <Metric label={isTrendMode ? '今日净盈亏' : isFundingMode ? '最大原始费差' : '最佳单次净收益'} value={isTrendMode ? formatUsdt(trend?.summary.todayNetRealizedProfit) : isFundingMode ? formatOpportunitySpread(bestFunding) : '—'} detail={isTrendMode ? `累计 ${formatUsdt(trend?.summary.netRealizedProfit)}` : isFundingMode ? '主展示不做按小时换算；跨周期另标累计判断' : '等待真实机会接口'} tone={isTrendMode ? valueTone(trend?.summary.todayNetRealizedProfit || 0) : isFundingMode ? 'positive' : 'neutral'} />
      <Metric label={isTrendMode ? '活动仓位' : '最大可成交容量'} value={isTrendMode ? String(trend?.summary.activePositions || 0) : isFundingMode ? formatNotional(largestFunding?.executableNotional) : '—'} detail={isTrendMode ? `今日平仓 ${trend?.summary.todayClosedPositions || 0} · 今日胜 ${trend?.summary.todayWinningPositions || 0}` : isFundingMode ? '共同基础币数量对应的单腿名义金额' : '等待真实 L2/VWAP 容量'} />
      <Metric label={isTrendMode ? '今日手续费' : isFundingMode ? '覆盖币对' : '数据状态'} value={isTrendMode ? formatUsdtNoSign(trend?.summary.todayFees) : isFundingMode ? String(fundingRows.length) : '未接入'} detail={isTrendMode ? `累计手续费 ${formatUsdtNoSign(trend?.summary.totalFees)}` : isFundingMode ? `${fundingMatrix.exchanges.length} 个交易所横向比对 · 缺失以 — 显示` : '已移除前端硬编码样例'} tone="warn" />
    </section> : null}
    <section className="arb-card arb-mode-panel">
      <div className="arb-tabs" role="tablist">{MODES.map((item) => <button key={item.value} type="button" role="tab" aria-selected={mode === item.value} className={mode === item.value ? 'active' : ''} onClick={() => updateMode(item.value)}>{item.label}<span>{item.tag}</span></button>)}</div>
      {isFundingMode ? <div className="arb-filters">
        <label>币种<input value={symbol} onChange={(event) => setSymbol(event.target.value)} placeholder="BTC、ETH" /></label>
        <label>交易所<select value={exchange} onChange={(event) => setExchange(event.target.value)}><option value="ALL">全部交易所</option>{fundingMatrix.exchanges.map((item) => <option key={item} value={item}>{item}</option>)}</select></label>
        <label>最小容量<input type="number" min="0" value={minCapacity || ''} onChange={(event) => setMinCapacity(Number(event.target.value) || 0)} placeholder="USDT" /></label>
        <label>最小净收益<input type="number" min="0" step="0.01" value={minYield || ''} onChange={(event) => setMinYield(Number(event.target.value) || 0)} placeholder="%" /></label>
        <label className="arb-toggle"><input type="checkbox" checked={qualifiedOnly} onChange={(event) => setQualifiedOnly(event.target.checked)} /><span />仅可执行</label>
        <button className="arb-text-button" type="button" onClick={resetFilters}>重置</button><button className="arb-text-button" type="button" onClick={() => void refetch()}>{isFetching ? '刷新中' : '刷新'}</button><button className="arb-text-button" type="button" onClick={() => void copyView()}>复制视图</button>
      </div> : null}
      {!isTrendMode ? <div className="arb-meta"><span>{MODES.find((item) => item.value === mode)?.label} · {isFundingMode ? `显示 ${fundingRows.length} 个币对 · 可执行 ${executableFundingRows.length} 个` : '真实读模型未接入'}</span><span>{isFundingMode ? '原始差 = 空头费率 - 多头费率' : '不展示 Demo 数据'}</span></div> : null}
      {isTrendMode ? <><label>HTX 数据口径 <select aria-label="HTX 数据口径" value={executionMode} onChange={(event) => { const next = new URLSearchParams(params); next.set('executionMode', event.target.value); setParams(next) }}><option value="LIVE">LIVE 实盘 · 只读</option><option value="PAPER">PAPER 模拟 · 只读</option></select></label><TrendDashboard trend={trend} loading={isFetchingTrend} refresh={() => void refetchTrend()} view={trendView} onViewChange={updateTrendView} /></> : isFundingMode ? <div className="arb-table-wrap"><table><thead><tr>{fundingTableHeaders(fundingMatrix.exchanges).map((item) => <th key={item}>{item}</th>)}</tr></thead><tbody>{fundingRows.length ? fundingRows.map((row) => <FundingRateMatrixTableRow key={row.symbol} row={row} exchanges={fundingMatrix.exchanges} />) : <tr><td className="arb-empty" colSpan={fundingMatrix.exchanges.length + 5}>没有满足当前筛选条件的币对</td></tr>}</tbody></table></div> : <UnsupportedModePanel mode={mode as Exclude<ArbitrageMode, 'CROSS_FUNDING' | 'HTX_TREND'>} />}
    </section>
    {isFundingMode && <section className="arb-bottom-grid"><article className="arb-card arb-health"><header><div><p className="arb-eyebrow">DATA COVERAGE</p><h2>资金费矩阵覆盖</h2></div><span>{matrixResponse?.asOf || '等待接口'}</span></header>{exchangeCoverage.length ? exchangeCoverage.map((item) => <div className="arb-health-row" key={item.name}><strong className={item.count > 0 ? 'good' : 'bad'}>● {item.name}</strong><i><b style={{ width: `${fundingMatrix.items.length ? Math.round(item.count / fundingMatrix.items.length * 100) : 0}%` }} /></i><span>{item.count || '—'}</span><small>{item.count ? '有资金费样本' : '当前矩阵无样本'}</small></div>) : <p className="arb-compact-note">行情读模型尚未返回资金费样本；不会将空数据呈现为可交易机会。</p>}</article>
      <article className="arb-card arb-ranking"><p className="arb-eyebrow">DATA RULES</p><h2>排序口径</h2><ol><li>仅保留具备可执行深度的市场。</li><li>资金费统一折算到共同结算窗口。</li><li>排序前已扣除手续费与 L2 滑点。</li></ol></article></section>}
    {((isFundingMode && isError && !matrixResponse) || (isTrendMode && trendError && !trend)) ? <div className="arb-notice">读模型接口暂不可用；为避免误导，当前不展示 Demo 数据。</div> : null}
  </div>
}

function Metric({ label, value, detail, tone = 'neutral' }: { label: string; value: string; detail: string; tone?: string }) { return <article className={`arb-metric ${tone}`}><span>{label}</span><strong>{value}</strong><small>{detail}</small></article> }
function isExecutableStatus(status: string) { return ['QUALIFIED', 'APPROVED', 'PAPER_READY'].includes(status.toUpperCase()) }
function formatPercent(value: number | null | undefined) { return value == null ? '—' : `${value >= 0 ? '+' : ''}${(value * 100).toFixed(4)}%` }
function formatFundingRate(value: number | null | undefined, intervalHours: number | null | undefined) {
  if (value == null) return '—'
  const period = intervalHours != null && intervalHours > 0 && intervalHours !== 8 ? ` · ${intervalHours}h` : ''
  return `${formatPercent(value)}${period}`
}
function formatNotional(value: number | null | undefined) { return value == null ? '—' : value >= 1000 ? `$${(value / 1000).toFixed(1)}K` : `$${value.toFixed(0)}` }
function formatUsdt(value: number | null | undefined) { return value == null ? '—' : `${value >= 0 ? '+' : ''}${value.toFixed(4)} U` }
function formatUsdtNoSign(value: number | null | undefined) { return value == null ? '—' : `${value.toFixed(4)} U` }
function formatRatio(value: number | null | undefined) { return value == null ? '—' : value >= 100 ? '∞' : value.toFixed(2) }
function formatDecimal(value: number | null | undefined, digits = 6) { return value == null ? '—' : Number(value).toFixed(digits).replace(/\.0+$/, '').replace(/(\.\d*?)0+$/, '$1') }
function directionText(value: string | null | undefined) {
  const upper = (value || '').toUpperCase()
  if (upper === 'LONG') return '多'
  if (upper === 'SHORT') return '空'
  if (upper === 'FLAT') return '观望'
  if (upper === 'BUY') return '买入'
  if (upper === 'SELL') return '卖出'
  if (upper === 'OPEN_BUY') return '开多'
  if (upper === 'OPEN_SELL') return '开空'
  if (upper === 'CLOSE_BUY') return '平空'
  if (upper === 'CLOSE_SELL') return '平多'
  return value || '—'
}
function trendBucketText(value: string | null | undefined) {
  const raw = value || '—'
  const upper = raw.toUpperCase()
  if (upper === 'BREAKOUT') return '突破'
  if (upper === 'FIBONACCI CONTINUATION') return '回踩延续'
  if (upper === 'FUNDING CARRY') return '资金费辅助'
  if (upper === 'ENSEMBLE') return '组合信号'
  if (upper === 'BROAD UP') return '普涨'
  if (upper === 'BROAD DOWN') return '普跌'
  if (upper === 'COUNTER MARKET') return '逆市场'
  if (upper === 'MIXED') return '混合'
  if (upper === 'UNKNOWN') return '未知'
  if (upper === 'UNCLASSIFIED') return '未分类'
  return directionText(raw)
}
function ledgerEventText(value: string | null | undefined) {
  const upper = (value || '').toUpperCase()
  if (upper === 'POSITION_INTENT') return '仓位意图'
  if (upper === 'OPEN_ORDER') return '开仓委托'
  if (upper === 'CLOSE_ORDER') return '平仓委托'
  if (upper === 'OPEN_FILL') return '开仓成交'
  if (upper === 'CLOSE_FILL') return '平仓成交'
  if (upper === 'FEE') return '手续费'
  if (upper === 'REALIZED_PNL') return '已实现盈亏'
  return value || '—'
}
function orderLegText(value: string | null | undefined) {
  const upper = (value || '').toUpperCase()
  if (upper === 'ENTRY') return '开仓'
  if (upper === 'CLOSE') return '平仓'
  return value || '—'
}
function StatusText(status: string | null | undefined) {
  const upper = (status || '').toUpperCase()
  if (upper === 'ACTIVE') return '持仓中'
  if (upper === 'DONE') return '已完成'
  if (upper === 'FILLED' || upper === 'ORDER_FILLED') return '已成交'
  if (upper === 'ENTRY_SENT') return '开仓已发出'
  if (upper === 'CLOSE_SENT') return '平仓已发出'
  if (upper === 'INTENT') return '意图已记录'
  if (upper === 'UNKNOWN') return '需人工复核'
  if (upper === 'REJECTED') return '已拒绝'
  return upper || '未知'
}
function accountName(value: string | null | undefined) {
  const name = (value || '').trim()
  return name || 'primary'
}
function accountRoleText(row: TrendAccountSummary) {
  const role = (row.accountRole || '').toUpperCase()
  if (role === 'PRIMARY') return '主账户'
  if (row.mirrorEnabled || role === 'FOLLOWER') return '跟随账户'
  return role || '账户'
}
function accountOptions(positions: TrendPosition[], orders: TrendOrder[], summaries: TrendAccountSummary[] = []) {
  return Array.from(new Set([
    ...summaries.map((item) => accountName(item.accountLabel)),
    ...positions.map((item) => accountName(item.accountLabel)),
    ...orders.map((item) => accountName(item.accountLabel)),
  ])).sort()
}
function hasTodayFill(row: TrendAccountSummary) {
  return row.todayClosedPositions > 0 || row.todayTurnover > 0 || row.todayOrderNotional > 0
}
function hasTodayOrder(row: TrendAccountSummary) {
  return row.todayOrders > 0
}
function latestClosedOrRecentPosition(rows: TrendPosition[]) { return rows.find((position) => position.status.toUpperCase() === 'DONE') || rows[0] }
function latestRuntimeEventAt(trend: HtxTrendDashboardResponse) {
  const events = [trend.runtime.latestOrderAt, trend.runtime.latestPositionAt, trend.runtime.latestCandidateAt, trend.runtime.latestSignalAt].filter(Boolean).sort()
  return events.length ? events[events.length - 1] : trend.asOf
}
function positionManagementLabel(position: TrendPosition) {
  if (position.status === 'CLOSE_SENT') return '等待平仓成交'
  if (position.status === 'ENTRY_SENT') return '等待开仓成交'
  if (position.status === 'ACTIVE') return '追踪止损管理中'
  return StatusText(position.status)
}
function exitSnapshotStateText(value: string | null | undefined) {
  const upper = (value || '').toUpperCase()
  if (upper === 'LOSS' || upper === 'LOSS_ZONE') return '亏损区'
  if (upper === 'FEE_BREAKEVEN' || upper === 'FEE_BREAKEVEN_ZONE') return '费用线附近'
  if (upper === 'RUNNER' || upper === 'RUNNER_ZONE') return '趋势奔跑'
  if (upper === 'EXHAUSTION' || upper === 'EXHAUSTION_ZONE') return '回吐观察'
  if (upper === 'PROFIT' || upper === 'PROFIT_ZONE') return '盈利区'
  return value || '—'
}
function formatGiveback(value: number | null | undefined) {
  return value == null ? '—' : `${Math.max(0, value * 100).toFixed(1)}%`
}
function compactExitSnapshot(position: TrendPosition) {
  if (!position.exitSnapshotState && position.exitSnapshotNet == null && position.exitSnapshotMfe == null) return '—'
  const parts = [
    exitSnapshotStateText(position.exitSnapshotState),
    `净值 ${formatUsdt(position.exitSnapshotNet)}`,
    `MFE ${formatUsdt(position.exitSnapshotMfe)}`,
    `回吐 ${formatGiveback(position.exitSnapshotGiveback)}`,
  ]
  if (position.exitSnapshotBars != null) parts.push(`${position.exitSnapshotBars} 根K`)
  const actionHint = exitActionHint(position.exitSnapshotCandidates)
  if (actionHint !== '—') parts.push(actionHint)
  return parts.join(' · ')
}
function exitActionHint(candidates: string | null | undefined) {
  const value = candidates || ''
  const hints = []
  if (value.includes('PARTIAL_TAKE_PROFIT:simulate_trigger')) hints.push('分批止盈待评估')
  if (value.includes('PROFIT_PROTECTION:simulate_trigger')) hints.push('利润保护待评估')
  return hints.length ? hints.join(' / ') : '—'
}
function botState(trend: HtxTrendDashboardResponse) {
  if (trend.source === 'HTX_TREND_EXECUTION_LEDGER_UNAVAILABLE') return { status: 'REJECTED', detail: '数据库账本不可用', tone: 'negative' } as const
  if (trend.summary.todayNetRealizedProfit <= -1) return { status: 'WARN', detail: `今日净亏 ${formatUsdt(trend.summary.todayNetRealizedProfit)}`, tone: 'warn' } as const
  if (trend.summary.activePositions > 0) return { status: 'ACTIVE', detail: '持仓管理中', tone: 'positive' } as const
  return { status: 'OBSERVING', detail: '等待有效候选', tone: 'neutral' } as const
}
function comparePositions(sort: PositionSortKey) {
  return (left: TrendPosition, right: TrendPosition) => {
    if (sort === 'symbol') return left.contractCode.localeCompare(right.contractCode)
    if (sort === 'pnl') return (right.netRealizedProfit ?? right.realizedProfit ?? 0) - (left.netRealizedProfit ?? left.realizedProfit ?? 0)
    if (sort === 'turnover') return (right.turnover || 0) - (left.turnover || 0)
    if (sort === 'fee') return ((right.entryFee || 0) + (right.closeFee || 0)) - ((left.entryFee || 0) + (left.closeFee || 0))
    return String(right.updatedAt || '').localeCompare(String(left.updatedAt || ''))
  }
}
function compareOrders(sort: OrderSortKey) {
  return (left: TrendOrder, right: TrendOrder) => {
    if (sort === 'symbol') return left.contractCode.localeCompare(right.contractCode)
    if (sort === 'state') return left.status.localeCompare(right.status)
    if (sort === 'notional') return (right.notional || 0) - (left.notional || 0)
    if (sort === 'fee') return (right.fee || 0) - (left.fee || 0)
    return String(right.updatedAt || right.createdAt || '').localeCompare(String(left.updatedAt || left.createdAt || ''))
  }
}
function trendSourceStatus(trend?: HtxTrendDashboardResponse) {
  if (!trend) return '等待数据'
  return trend.source === 'HTX_TREND_EXECUTION_LEDGER_UNAVAILABLE' ? '不可用' : '在线'
}
function trendSourceDetail(trend?: HtxTrendDashboardResponse) {
  if (!trend) return '—'
  if (trend.source === 'HTX_TREND_EXECUTION_LEDGER_UNAVAILABLE') return '账本暂不可读'
  return trend.asOf || '—'
}
function formatOpportunitySpread(row: FundingRateMatrixRow | undefined) {
  if (!row) return '—'
  const spread = bestRawFundingSpread(row)
  if (spread == null) return '—'
  return formatPercent(spread)
}
function fundingTableHeaders(exchanges: string[]) { return ['币对', ...exchanges.map((exchange) => `${exchange} 资金费`), '最佳套利机会 · 原始差 ↓', '可执行容量', '策略状态'] }
function compareFundingOpportunityDescending(left: FundingRateMatrixRow, right: FundingRateMatrixRow) {
  const leftSpread = rankFundingOpportunity(left)
  const rightSpread = rankFundingOpportunity(right)
  if (leftSpread == null) return rightSpread == null ? left.symbol.localeCompare(right.symbol) : 1
  if (rightSpread == null) return -1
  return rightSpread - leftSpread || left.symbol.localeCompare(right.symbol)
}
function bestRawFundingSpread(row: FundingRateMatrixRow) {
  if (row.rawFundingSpread != null) return row.rawFundingSpread
  if (!row.longExchange || !row.shortExchange) return null
  const longRate = row.ratesByExchange[row.longExchange]
  const shortRate = row.ratesByExchange[row.shortExchange]
  return longRate == null || shortRate == null ? null : shortRate - longRate
}
function rankFundingOpportunity(row: FundingRateMatrixRow) { return row.cadenceAdjusted && row.settlementWindowSpread != null ? row.settlementWindowSpread : bestRawFundingSpread(row) }
function hasCadenceOpportunity(row: FundingRateMatrixRow) { return Boolean(row.cadenceAdjusted && row.settlementWindowSpread != null && row.settlementWindowSpread > 0) }
function cadenceStatusText(row: FundingRateMatrixRow) {
  if (!row.cadenceAdjusted || !row.settlementWindowHours || row.settlementWindowSpread == null) return null
  const verdict = row.settlementWindowSpread > 0 ? '跨周期可行' : '跨周期不占优'
  return `${verdict}：${row.settlementWindowHours}h 累计 ${formatPercent(row.settlementWindowSpread)}`
}
function bestIntervalHint(row: FundingRateMatrixRow) {
  if (!row.longExchange || !row.shortExchange) return null
  const longInterval = row.intervalHoursByExchange?.[row.longExchange]
  const shortInterval = row.intervalHoursByExchange?.[row.shortExchange]
  if (!longInterval || !shortInterval || longInterval === shortInterval) return null
  return `${row.longExchange} ${longInterval}h / ${row.shortExchange} ${shortInterval}h`
}
function FundingRateMatrixTableRow({ row, exchanges }: { row: FundingRateMatrixRow; exchanges: string[] }) {
  const direction = row.longExchange && row.shortExchange ? `多 ${row.longExchange} / 空 ${row.shortExchange}` : '—'
  const rawSpread = bestRawFundingSpread(row)
  const intervalHint = bestIntervalHint(row)
  const cadenceOpportunity = hasCadenceOpportunity(row)
  const cadenceText = cadenceStatusText(row)
  return <tr>
    <td><b>{row.symbol}</b><small>{row.sampledAt || '—'}</small></td>
    {exchanges.map((exchange) => <td key={exchange} className={`arb-rate-cell ${valueTone(row.ratesByExchange[exchange] || 0)}`}><b>{formatFundingRate(row.ratesByExchange[exchange], row.intervalHoursByExchange?.[exchange])}</b></td>)}
    <td className="arb-best-opportunity"><b>{direction}</b><span className={`arb-best-spread ${cadenceOpportunity ? 'mismatch' : ''}`}><strong>原始差 {formatPercent(rawSpread)}</strong><small>{intervalHint ? `周期 ${intervalHint}` : '按上方费率直接相减'}</small></span>{cadenceText ? <small className="arb-window-spread">{cadenceText}</small> : null}{cadenceOpportunity ? <span className="arb-spread-warning">高频腿累计收款覆盖低频腿付款</span> : null}</td>
    <td>{formatNotional(row.executableNotional)}<small>净收益 {formatPercent(row.estimatedNetYield)}</small></td>
    <td><Status status={isExecutableStatus(row.status) ? 'QUALIFIED' : row.status === 'OBSERVING' ? 'OBSERVING' : 'REJECTED'} />{row.rejectionReason ? <small>{row.rejectionReason}</small> : null}</td>
  </tr>
}
function normalizeTrendDashboardResponse(response: HtxTrendDashboardResponse | { data?: HtxTrendDashboardResponse } | undefined): HtxTrendDashboardResponse | undefined {
  if (!response) return undefined
  if ('summary' in response && 'activePositions' in response) return response as HtxTrendDashboardResponse
  return (response as { data?: HtxTrendDashboardResponse }).data
}
function TrendDashboard({ trend, loading, refresh, view, onViewChange }: { trend?: HtxTrendDashboardResponse; loading: boolean; refresh: () => void; view: TrendView; onViewChange: (view: TrendView) => void }) {
  const [selectedAccount, setSelectedAccount] = useState('ALL')
  if (!trend) return <div className="arb-unavailable-panel"><h2>{loading ? '趋势账本加载中' : '等待趋势账本数据'}</h2><button className="arb-text-button" type="button" onClick={refresh}>刷新</button></div>
  const lastPosition = latestClosedOrRecentPosition(trend.recentPositions)
  const openAccountHistory = (account: string) => {
    setSelectedAccount(account)
    onViewChange('history')
  }
  return <div className={`trend-dashboard trend-dashboard-${view}`}>
    <TrendBotHero trend={trend} loading={loading} refresh={refresh} />
    <TrendViewTabs view={view} onViewChange={onViewChange} />
    {view === 'overview' ? <TrendOverview trend={trend} lastPosition={lastPosition} onViewChange={onViewChange} selectedAccount={selectedAccount} onSelectAccount={setSelectedAccount} onOpenAccountHistory={openAccountHistory} /> : null}
    {view === 'history' ? <TrendHistoryView positions={trend.recentPositions} orders={trend.recentOrders} ledgerEvents={trend.recentLedgerEvents || []} accountSummaries={trend.accountSummaries || []} selectedAccount={selectedAccount} /> : null}
    {view === 'quality' ? <TrendQualityView trend={trend} /> : null}
    {view === 'candidates' ? <TrendCandidateEvidenceView candidates={trend.recentCandidateSignals || []} /> : null}
  </div>
}
function TrendBotHero({ trend, loading, refresh }: { trend: HtxTrendDashboardResponse; loading: boolean; refresh: () => void }) {
  const state = botState(trend)
  const heartbeat = latestRuntimeEventAt(trend)
  return <section className={`trend-bot-hero ${state.tone}`}>
    <div className="trend-bot-identity">
      <h2>趋势 Bot</h2>
      <div className="trend-command-bar" aria-label="Bot status bar">
        <span><b>状态</b><Status status={state.status} /></span>
        <span><b>心跳</b><small>{heartbeat || '—'}</small></span>
        <span><b>风险</b><small className={state.tone}>{state.detail}</small></span>
        <span><b>更新</b><small>{trend.asOf || '—'}</small></span>
      </div>
    </div>
    <div className="trend-bot-actions">
      <button className="arb-text-button" type="button" onClick={refresh}>{loading ? '刷新中' : '刷新'}</button>
    </div>
  </section>
}
function TrendViewTabs({ view, onViewChange }: { view: TrendView; onViewChange: (view: TrendView) => void }) {
  return <div className="trend-view-tabs" role="tablist">{TREND_VIEWS.map((item) => <button key={item.value} type="button" role="tab" aria-selected={view === item.value} className={view === item.value ? 'active' : ''} onClick={() => onViewChange(item.value)}><strong>{item.label}</strong></button>)}</div>
}
function TrendOverview({ trend, lastPosition, onViewChange, selectedAccount, onSelectAccount, onOpenAccountHistory }: { trend: HtxTrendDashboardResponse; lastPosition?: TrendPosition; onViewChange: (view: TrendView) => void; selectedAccount: string; onSelectAccount: (account: string) => void; onOpenAccountHistory: (account: string) => void }) {
  return <div className="trend-overview">
    <section className="trend-overview-grid trend-first-screen-grid">
      <TrendNowPanel positions={trend.activePositions} onHistory={() => onViewChange('history')} />
      <TrendTodayPnlPanel trend={trend} />
      <TrendLastTradePanel position={lastPosition} onHistory={() => onViewChange('history')} />
      <TrendScanPanel trend={trend} />
    </section>
    <TrendAccountOverview rows={trend.accountSummaries || []} selectedAccount={selectedAccount} onSelectAccount={onSelectAccount} onOpenAccountHistory={onOpenAccountHistory} />
    <TrendDailyPnlChart rows={trend.dailyPnl} />
    <TrendAttributionTable title="今日方向盈亏" rows={trend.todaySidePnl} />
  </div>
}
function TrendScanPanel({ trend }: { trend: HtxTrendDashboardResponse }) {
  const runtime = trend.runtime
  const latest = runtime.latestSignalAt || runtime.latestCandidateAt || trend.asOf
  const scannedText = runtime.scannedContractsLast30m > 0 ? `${runtime.scannedContractsLast30m} 个币对` : '等待扫描'
  const hasLiveActivity = trend.activePositions.length > 0 || runtime.latestOrderAt != null
  return <section className="trend-panel trend-scan-panel">
    <header><div><h2>最近扫描</h2></div><span>30m</span></header>
    <div className={`trend-gate-summary ${hasLiveActivity ? 'active' : 'neutral'}`}>
      <strong>{scannedText}</strong>
      <span>实盘信号 {runtime.signalsLast30m} · 影子观察 {runtime.candidateSignalsLast30m} · 最近 {latest || '—'}</span>
    </div>
    <dl className="trend-compact-dl">
      <div><dt>实盘方向</dt><dd>多 {runtime.longSignalsLast30m} / 空 {runtime.shortSignalsLast30m}</dd></div>
      <div><dt>观察方向</dt><dd>多 {runtime.candidateLongLast30m} / 空 {runtime.candidateShortLast30m}</dd></div>
      <div><dt>实盘观望</dt><dd>{runtime.flatSignalsLast30m}</dd></div>
      <div><dt>观察观望</dt><dd>{runtime.candidateFlatLast30m}</dd></div>
    </dl>
  </section>
}
function TrendNowPanel({ positions, onHistory }: { positions: TrendPosition[]; onHistory: () => void }) {
  return <section className="trend-panel trend-now">
    <header><div><h2>当前仓位</h2></div><button className="arb-text-button" type="button" onClick={onHistory}>{positions.length} 条</button></header>
    {positions.length ? <div className="trend-position-list">
      {positions.slice(0, 5).map((position) => <article className="trend-current-position" key={position.id}>
        <div><strong>{position.contractCode}</strong><span><small>{accountName(position.accountLabel)}</small><Status status={position.side} /></span></div>
        <dl><div><dt>账户</dt><dd>{accountName(position.accountLabel)}</dd></div><div><dt>开仓均价</dt><dd>{formatDecimal(position.entryAvgPrice)}</dd></div><div><dt>追踪止损</dt><dd>{formatDecimal(position.trailingStopPrice ?? position.stopPrice)}</dd></div><div><dt>管理状态</dt><dd>{positionManagementLabel(position)}</dd></div><div><dt>退出状态</dt><dd>{exitSnapshotStateText(position.exitSnapshotState)}</dd></div><div><dt>当前净值</dt><dd className={valueTone(position.exitSnapshotNet ?? 0)}>{formatUsdt(position.exitSnapshotNet)}</dd></div><div><dt>MFE / 回吐</dt><dd>{formatUsdt(position.exitSnapshotMfe)} / {formatGiveback(position.exitSnapshotGiveback)}</dd></div></dl>
      </article>)}
      {positions.length > 5 ? <small>另有 {positions.length - 5} 条活动仓位，可在交易历史视图完整查看。</small> : null}
    </div> : <div className="trend-empty-state"><strong>当前没有活动趋势仓位</strong><small>等待下一次交易机会。</small></div>}
  </section>
}
function TrendTodayPnlPanel({ trend }: { trend: HtxTrendDashboardResponse }) {
  const performance = trend.performance
  const todayWinRate = performance?.todayClosedPositions ? `${((performance.todayWinRate || 0) * 100).toFixed(1)}%` : '—'
  const payoff = performance?.todayClosedPositions ? formatRatio(performance.todayPayoffRatio) : '—'
  const profitFactor = performance?.todayClosedPositions ? formatRatio(performance.todayProfitFactor) : '—'
  return <section className="trend-panel trend-pnl-panel">
    <header><div><h2>今日已实现</h2></div><span className={valueTone(trend.summary.todayNetRealizedProfit)}>{formatUsdt(trend.summary.todayNetRealizedProfit)}</span></header>
    <dl className="trend-compact-dl">
      <div><dt>平仓 / 胜单</dt><dd>{trend.summary.todayClosedPositions} / {trend.summary.todayWinningPositions}</dd></div>
      <div><dt>胜率</dt><dd>{todayWinRate}</dd></div>
      <div><dt>均赚 / 均亏</dt><dd>{formatUsdtNoSign(performance?.todayAverageWin)} / {formatUsdtNoSign(performance?.todayAverageLoss)}</dd></div>
      <div><dt>盈亏比 / PF</dt><dd>{payoff} / {profitFactor}</dd></div>
      <div><dt>单笔期望</dt><dd className={valueTone(performance?.todayExpectancy || 0)}>{formatUsdt(performance?.todayExpectancy)}</dd></div>
      <div><dt>交易量 / 费用</dt><dd>{formatNotional(trend.summary.todayTurnover)} / {formatUsdtNoSign(trend.summary.todayFees)}</dd></div>
    </dl>
  </section>
}
function TrendAccountOverview({ rows, selectedAccount, onSelectAccount, onOpenAccountHistory }: { rows: TrendAccountSummary[]; selectedAccount: string; onSelectAccount: (account: string) => void; onOpenAccountHistory: (account: string) => void }) {
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState<AccountFilter>('ALL')
  const [sort, setSort] = useState<AccountSortKey>('priority')
  const ordered = accountRows(rows, filter, sort, query)
  const visible = ordered.slice(0, 20)
  const selectedRow = rows.find((row) => accountName(row.accountLabel) === selectedAccount)
  const totalNet = rows.reduce((sum, row) => sum + (row.todayNetRealizedProfit || 0), 0)
  const totalTurnover = rows.reduce((sum, row) => sum + (row.todayTurnover || row.todayOrderNotional || 0), 0)
  const activeAccounts = rows.filter((row) => row.activePositions > 0).length
  const filledAccounts = rows.filter(hasTodayFill).length
  const orderAccounts = rows.filter(hasTodayOrder).length
  const mirrorAccounts = rows.filter((row) => row.mirrorEnabled || (row.accountRole || '').toUpperCase() === 'MIRROR').length
  return <section className="trend-account-overview trend-account-workspace">
    <header className="trend-table-title trend-account-title"><div><h2>账户池</h2></div><span>{rows.length} 个账户</span></header>
    {rows.length ? <>
      <div className="trend-account-pool">
        <div><small>今日净收益</small><strong className={valueTone(totalNet)}>{formatUsdt(totalNet)}</strong></div>
        <div><small>活跃账号</small><strong>{activeAccounts}</strong></div>
        <div><small>今日成交账户</small><strong>{filledAccounts}</strong></div>
        <div><small>今日订单账户</small><strong>{orderAccounts}</strong></div>
        <div><small>跟随账号</small><strong>{mirrorAccounts}</strong></div>
        <div><small>今日交易量</small><strong>{formatNotional(totalTurnover)}</strong></div>
      </div>
      <div className="trend-account-controls">
        <label>搜索账号<input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="primary / TradeX" /></label>
        <label>账户范围<select value={filter} onChange={(event) => setFilter(event.target.value as AccountFilter)}><option value="ALL">全部账户</option><option value="ACTIVE">有仓位 / 挂单</option><option value="TRADED">今日有成交</option><option value="WATCH">需要关注</option><option value="MIRROR">跟随账户</option></select></label>
        <label>排序<select value={sort} onChange={(event) => setSort(event.target.value as AccountSortKey)}><option value="priority">优先级</option><option value="pnl">今日净收益</option><option value="turnover">今日交易量</option><option value="orders">订单数</option><option value="latest">最近账本</option><option value="name">账号名</option></select></label>
      </div>
      {selectedRow ? <TrendAccountDetail row={selectedRow} onOpen={() => onOpenAccountHistory(accountName(selectedRow.accountLabel))} /> : null}
      <div className="arb-table-wrap trend-account-table-wrap">
        <table className="trend-account-table">
          <thead>
            <tr><th>账号</th><th>角色</th><th>状态</th><th>活动</th><th>今日平仓</th><th>胜率</th><th>交易量</th><th>净收益</th><th>费用</th><th>最近账本</th><th>操作</th></tr>
          </thead>
          <tbody>{visible.map((row) => <TrendAccountRow key={accountName(row.accountLabel)} row={row} selected={accountName(row.accountLabel) === selectedAccount} onSelect={() => onSelectAccount(accountName(row.accountLabel))} onOpen={() => onOpenAccountHistory(accountName(row.accountLabel))} />)}</tbody>
        </table>
      </div>
      {ordered.length > visible.length ? <p className="trend-account-footnote">已按优先级显示前 20 个账户；完整账户池应进入独立账户管理页。</p> : null}
    </> : <div className="trend-empty-state"><strong>暂无账户账本</strong><small>账号注册或策略写入后会自动出现。</small></div>}
  </section>
}

function TrendAccountRow({ row, selected, onSelect, onOpen }: { row: TrendAccountSummary; selected: boolean; onSelect: () => void; onOpen: () => void }) {
  const winRate = row.closedPositions ? `${(row.winningPositions / row.closedPositions * 100).toFixed(1)}%` : '—'
  const latest = row.latestOrderAt || row.latestPositionAt || '—'
  const fees = row.todayFees || row.todayOrderFees || row.todayEstimatedOrderFees
  const activity = row.activePositions > 0 ? `${row.activePositions} 持仓` : row.openOrders > 0 ? `${row.openOrders} 挂单` : '空闲'
  return <tr className={selected ? 'selected' : ''} onClick={onSelect}>
    <td><b>{accountName(row.accountLabel)}</b></td><td><small>{accountRoleText(row)}</small></td><td><Status status={row.accountEnabled && row.trendEnabled ? 'ACTIVE' : 'REJECTED'} /></td><td>{activity}</td><td>{row.todayClosedPositions}</td><td>{winRate}</td><td>{formatNotional(row.todayTurnover || row.todayOrderNotional)}</td><td className={valueTone(row.todayNetRealizedProfit)}>{formatUsdt(row.todayNetRealizedProfit)}</td><td>{formatUsdtNoSign(fees)}</td><td><small>{latest}</small></td>
    <td><button className="arb-text-button trend-account-open" type="button" onClick={(event) => { event.stopPropagation(); onOpen() }}>查看账本</button></td>
  </tr>
}

function TrendAccountDetail({ row, onOpen }: { row: TrendAccountSummary; onOpen: () => void }) {
  const winRate = row.closedPositions ? `${(row.winningPositions / row.closedPositions * 100).toFixed(1)}%` : '—'
  const latest = row.latestOrderAt || row.latestPositionAt || '—'
  const fees = row.todayFees || row.todayOrderFees || row.todayEstimatedOrderFees
  return <div className="trend-account-detail">
    <div><small>当前账号</small><strong>{accountName(row.accountLabel)}</strong><span>{accountRoleText(row)}</span></div>
    <div><small>今日净收益</small><strong className={valueTone(row.todayNetRealizedProfit)}>{formatUsdt(row.todayNetRealizedProfit)}</strong><span>费用 {formatUsdtNoSign(fees)}</span></div>
    <div><small>今日成交</small><strong>{formatNotional(row.todayTurnover || row.todayOrderNotional)}</strong><span>平仓 {row.todayClosedPositions} · 订单 {row.todayOrders}</span></div>
    <div><small>历史胜率</small><strong>{winRate}</strong><span>最近 {latest}</span></div>
    <button className="arb-text-button" type="button" onClick={onOpen}>查看该账号账本</button>
  </div>
}

function compareAccounts(sort: AccountSortKey) {
  return (left: TrendAccountSummary, right: TrendAccountSummary) => {
    if (sort === 'name') return accountName(left.accountLabel).localeCompare(accountName(right.accountLabel))
    if (sort === 'pnl') return (right.todayNetRealizedProfit || 0) - (left.todayNetRealizedProfit || 0)
    if (sort === 'turnover') return (right.todayTurnover || right.todayOrderNotional || 0) - (left.todayTurnover || left.todayOrderNotional || 0)
    if (sort === 'orders') return (right.totalOrders || 0) - (left.totalOrders || 0)
    if (sort === 'latest') return String(right.latestOrderAt || right.latestPositionAt || '').localeCompare(String(left.latestOrderAt || left.latestPositionAt || ''))
    return accountPriority(right) - accountPriority(left)
  }
}
function accountPriority(row: TrendAccountSummary) {
  return (row.activePositions > 0 ? 1000 : 0) + (row.openOrders > 0 ? 500 : 0) + (hasTodayFill(row) ? 100 : 0) + Math.abs(row.todayNetRealizedProfit || 0)
}
function accountRows(rows: TrendAccountSummary[], filter: AccountFilter, sort: AccountSortKey, query: string) {
  const keyword = query.trim().toLowerCase()
  return [...rows].filter((row) => {
    const label = accountName(row.accountLabel).toLowerCase()
    if (keyword && !label.includes(keyword)) return false
    if (filter === 'ACTIVE') return row.activePositions > 0 || row.openOrders > 0
    if (filter === 'TRADED') return hasTodayFill(row)
    if (filter === 'WATCH') return !row.accountEnabled || !row.trendEnabled || row.todayNetRealizedProfit < 0 || row.openOrders > 0
    if (filter === 'MIRROR') return row.mirrorEnabled || (row.accountRole || '').toUpperCase() === 'MIRROR'
    return true
  }).sort(compareAccounts(sort))
}

function TrendLastTradePanel({ position, onHistory }: { position?: TrendPosition; onHistory: () => void }) {
  return <section className="trend-panel">
    <header><div><h2>最近一笔交易</h2></div><button className="arb-text-button" type="button" onClick={onHistory}>完整历史</button></header>
    {position ? <div className="trend-last-trade">
      <div><strong>{position.contractCode}</strong><span><small>{accountName(position.accountLabel)}</small><Status status={position.side} /></span></div>
      <dl><div><dt>账户</dt><dd>{accountName(position.accountLabel)}</dd></div><div><dt>净盈亏</dt><dd className={valueTone(position.finalNetProfit ?? position.netRealizedProfit ?? position.realizedProfit ?? 0)}>{formatUsdt(position.finalNetProfit ?? position.netRealizedProfit ?? position.realizedProfit)}</dd></div><div><dt>开 / 平仓</dt><dd>{formatDecimal(position.entryAvgPrice)} / {formatDecimal(position.closeAvgPrice)}</dd></div><div><dt>结果</dt><dd>{StatusText(position.status)}</dd></div></dl>
    </div> : <div className="trend-empty-state"><strong>暂无交易记录</strong><small>策略账本返回空历史时，页面不会补造收益样例。</small></div>}
  </section>
}
function TrendDailyPnlChart({ rows }: { rows: HtxTrendDashboardResponse['dailyPnl'] }) {
  const days = [...rows].reverse()
  const max = Math.max(0.01, ...days.map((item) => Math.abs(item.netProfit)))
  return <section className="trend-chart"><header><div><h2>日度已实现净收益</h2></div><span>{days.length} 天</span></header><div className="trend-bars">{days.length ? days.map((item) => <div className="trend-bar-item" key={item.tradeDate}><i className={item.netProfit >= 0 ? 'positive' : 'negative'} style={{ height: `${Math.max(5, Math.abs(item.netProfit) / max * 100)}%` }} title={`${item.tradeDate}: ${formatUsdt(item.netProfit)}`} /><strong>{formatUsdt(item.netProfit)}</strong><small>{item.tradeDate}</small></div>) : <p>暂无已平仓日度记录。趋势仓位仍在运行或尚未完成平仓。</p>}</div></section>
}
function TrendHistoryView({ positions, orders, ledgerEvents, accountSummaries, selectedAccount }: { positions: TrendPosition[]; orders: TrendOrder[]; ledgerEvents: TrendLedgerEvent[]; accountSummaries: TrendAccountSummary[]; selectedAccount: string }) {
  const [side, setSide] = useState('ALL')
  const [status, setStatus] = useState('ALL')
  const [account, setAccount] = useState(selectedAccount || 'ALL')
  const [symbol, setSymbol] = useState('')
  const [positionSort, setPositionSort] = useState<PositionSortKey>('updatedAt')
  const [orderSort, setOrderSort] = useState<OrderSortKey>('updatedAt')
  const accounts = accountOptions(positions, orders, accountSummaries)
  useEffect(() => {
    setAccount(selectedAccount || 'ALL')
  }, [selectedAccount])
  const filteredPositions = [...positions]
    .filter((position) => (!symbol || position.contractCode.includes(symbol.toUpperCase())) && (account === 'ALL' || accountName(position.accountLabel) === account) && (side === 'ALL' || position.side === side) && (status === 'ALL' || position.status === status))
    .sort(comparePositions(positionSort))
  const filteredOrders = [...orders]
    .filter((order) => (!symbol || order.contractCode.includes(symbol.toUpperCase())) && (account === 'ALL' || accountName(order.accountLabel) === account) && (side === 'ALL' || order.trendSide === side))
    .sort(compareOrders(orderSort))
  const filteredLedgerEvents = [...ledgerEvents]
    .filter((event) => (!symbol || (event.contractCode || '').includes(symbol.toUpperCase())) && (account === 'ALL' || accountName(event.accountLabel) === account) && (side === 'ALL' || event.side === side))
  return <div className="trend-view-stack trend-history-view">
    <section className="trend-toolbar trend-history-toolbar">
      <div><h2>交易历史记录</h2></div>
      <label>合约<input value={symbol} onChange={(event) => setSymbol(event.target.value)} placeholder="BTC、SOL" /></label>
      <label>账户<select value={account} onChange={(event) => setAccount(event.target.value)}><option value="ALL">全部账户</option>{accounts.map((item) => <option value={item} key={item}>{item}</option>)}</select></label>
      <label>方向<select value={side} onChange={(event) => setSide(event.target.value)}><option value="ALL">全部</option><option value="LONG">LONG</option><option value="SHORT">SHORT</option></select></label>
      <label>状态<select value={status} onChange={(event) => setStatus(event.target.value)}><option value="ALL">全部</option><option value="ACTIVE">持仓中</option><option value="DONE">已完成</option><option value="ENTRY_SENT">开仓已发出</option><option value="CLOSE_SENT">平仓已发出</option></select></label>
      <label>仓位排序<select value={positionSort} onChange={(event) => setPositionSort(event.target.value as PositionSortKey)}><option value="updatedAt">最近更新</option><option value="pnl">净盈亏</option><option value="turnover">交易量</option><option value="fee">手续费</option><option value="symbol">合约名</option></select></label>
      <label>订单排序<select value={orderSort} onChange={(event) => setOrderSort(event.target.value as OrderSortKey)}><option value="updatedAt">最近更新</option><option value="notional">成交额</option><option value="fee">手续费</option><option value="state">订单状态</option><option value="symbol">合约名</option></select></label>
    </section>
    {account !== 'ALL' ? <div className="trend-history-scope"><span>正在查看 <strong>{account}</strong></span><button className="arb-text-button" type="button" onClick={() => setAccount('ALL')}>全部账户</button></div> : null}
    <section className="arb-table-wrap"><header className="trend-table-title"><div><h2>仓位账本</h2></div><span>{filteredPositions.length} / {positions.length}</span></header><table><thead><tr><th>账户</th><th>合约 / 方向</th><th>状态</th><th>开仓 / 平仓价</th><th>数量</th><th>手续费</th><th>交易量</th><th>净盈亏</th><th>退出诊断</th><th>更新时间</th></tr></thead><tbody>{filteredPositions.length ? filteredPositions.map((position) => <TrendPositionRow key={position.id} position={position} />) : <tr><td className="arb-empty" colSpan={10}>没有符合筛选条件的仓位记录</td></tr>}</tbody></table></section>
    <TrendLedgerEventsTable rows={filteredLedgerEvents} total={ledgerEvents.length} />
    <TrendOrdersTable rows={filteredOrders} />
  </div>
}
function TrendQualityView({ trend }: { trend: HtxTrendDashboardResponse }) {
  return <div className="trend-view-stack trend-quality-view">
    <section className="trend-quality-grid">
      <TrendAttributionTable title="入场模型收益质量" rows={trend.entryModelPnl || []} />
      <TrendAttributionTable title="市场状态收益质量" rows={trend.marketRegimePnl || []} />
      <TrendAttributionTable title="亏损币种优先排查" rows={trend.symbolPnl || []} />
      <TrendAttributionTable title="今日退出原因" rows={trend.exitReasonPnl || []} />
    </section>
  </div>
}

type TrendCostEvidence = {
  account?: { label?: string; feeSource?: string; openTakerRate?: number; closeTakerRate?: number; effectiveTakerRate?: number }
  orderBook?: { bestBid?: number; bestAsk?: number; spreadRate?: number; depthLevels?: number; availableContracts?: number; requiredContracts?: number; coverageRatio?: number }
  expectation?: { atrMove?: number; grossPnl?: number; entryFee?: number; closeFee?: number; spreadCost?: number; netPnlAfterCosts?: number; requiredMove?: number; gateResult?: string; gateReason?: string }
}

function parseCostEvidence(value: string | null | undefined): TrendCostEvidence | undefined {
  if (!value) return undefined
  try { return JSON.parse(value) as TrendCostEvidence } catch { return undefined }
}

function TrendCandidateEvidenceView({ candidates }: { candidates: TrendCandidateSignal[] }) {
  const [symbol, setSymbol] = useState('')
  const [gate, setGate] = useState('ALL')
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const rows = [...candidates]
    .filter((row) => !symbol || row.contractCode.includes(symbol.toUpperCase()))
    .filter((row) => gate === 'ALL' || row.gateResult === gate)
  const selected = rows.find((row) => row.id === selectedId) || rows[0]
  const cost = parseCostEvidence(selected?.costFactorsJson)
  const distinctGates = [...new Set(candidates.map((row) => row.gateResult)
    .filter((value): value is string => Boolean(value)))]
  const estimatedCosts = cost ? (cost.expectation?.entryFee || 0) + (cost.expectation?.closeFee || 0) + (cost.expectation?.spreadCost || 0) : undefined
  return <div className="trend-view-stack trend-candidate-view">
    <section className="trend-toolbar trend-candidate-toolbar">
      <div><h2>候选与执行证据</h2></div>
      <label>合约<input value={symbol} onChange={(event) => setSymbol(event.target.value)} placeholder="BTC、SOL" /></label>
      <label>Gate<select value={gate} onChange={(event) => setGate(event.target.value)}><option value="ALL">全部</option>{distinctGates.map((item) => <option value={item} key={item}>{item}</option>)}</select></label>
      <span>{rows.length} / {candidates.length}</span>
    </section>
    <section className="arb-table-wrap"><table><thead><tr><th>合约 / 方向</th><th>模型</th><th>分数</th><th>Gate</th><th>实际费率</th><th>点差 / 深度</th><th>预期净收益</th><th>时间</th></tr></thead><tbody>{rows.length ? rows.map((row) => {
      const rowCost = parseCostEvidence(row.costFactorsJson)
      return <tr key={row.id} className={selected?.id === row.id ? 'selected' : ''} onClick={() => setSelectedId(row.id)}>
        <td><b>{row.contractCode}</b><small>{directionText(row.direction)} · {row.regime}</small></td>
        <td><b>{row.primaryModel || '未匹配'}</b><small>{row.confirmationModels || '—'}</small></td>
        <td>{formatDecimal(row.signalScore, 2)}<small>差值 {formatDecimal(row.scoreGap, 2)}</small></td>
        <td><Status status={row.gateResult || 'OBSERVING'} /><small>{row.gateReason || row.invalidationReason || '—'}</small></td>
        <td>{rowCost ? <><b>{formatPercent(rowCost.account?.effectiveTakerRate)}</b><small>{rowCost.account?.feeSource === 'HTX_ACCOUNT' ? '账户实际费率' : '保守配置费率'}</small></> : '待执行预检'}</td>
        <td>{rowCost ? <><b>{formatPercent(rowCost.orderBook?.spreadRate)}</b><small>{formatDecimal(rowCost.orderBook?.coverageRatio, 2)}× 深度覆盖</small></> : '待盘口快照'}</td>
        <td className={valueTone(rowCost?.expectation?.netPnlAfterCosts || 0)}>{rowCost ? <><b>{formatUsdt(rowCost.expectation?.netPnlAfterCosts)}</b><small>成本 {formatUsdtNoSign((rowCost.expectation?.entryFee || 0) + (rowCost.expectation?.closeFee || 0) + (rowCost.expectation?.spreadCost || 0))}</small></> : '—'}</td>
        <td><small>{row.createdAt || '—'}</small></td>
      </tr>
    }) : <tr><td className="arb-empty" colSpan={8}>没有符合筛选条件的候选</td></tr>}</tbody></table></section>
    {selected ? <section className="trend-candidate-detail"><header><div><h2>{selected.contractCode} · {selected.primaryModel || '候选'}</h2><small>{selected.entryHypothesis || selected.entryReason || '—'}</small></div><Status status={selected.gateResult || 'OBSERVING'} /></header>{cost ? <div className="trend-evidence-grid">
      <div><small>费用来源</small><strong>{cost.account?.feeSource === 'HTX_ACCOUNT' ? 'HTX 账户实际费率' : '保守配置费率'}</strong><span>开 {formatPercent(cost.account?.openTakerRate)} · 平 {formatPercent(cost.account?.closeTakerRate)}</span></div>
      <div><small>盘口</small><strong>{formatDecimal(cost.orderBook?.bestBid)} / {formatDecimal(cost.orderBook?.bestAsk)}</strong><span>点差 {formatPercent(cost.orderBook?.spreadRate)}</span></div>
      <div><small>深度覆盖</small><strong>{formatDecimal(cost.orderBook?.coverageRatio, 2)}×</strong><span>{formatDecimal(cost.orderBook?.availableContracts)} / {formatDecimal(cost.orderBook?.requiredContracts)} 张</span></div>
      <div><small>预期毛收益</small><strong>{formatUsdt(cost.expectation?.grossPnl)}</strong><span>ATR 目标移动 {formatDecimal(cost.expectation?.atrMove)}</span></div>
      <div><small>预估总成本</small><strong>{formatUsdtNoSign(estimatedCosts)}</strong><span>费用与点差已计入</span></div>
      <div><small>扣费后预期</small><strong className={valueTone(cost.expectation?.netPnlAfterCosts || 0)}>{formatUsdt(cost.expectation?.netPnlAfterCosts)}</strong><span>{cost.expectation?.gateResult || '—'}</span></div>
    </div> : <div className="trend-empty-state"><strong>该候选尚未进入真实执行预检</strong><small>因此没有伪造账户费率、盘口或预期收益；下一次候选进入执行预检后会自动补齐。</small></div>}<p className="trend-candidate-gate"><b>Gate：</b>{cost?.expectation?.gateReason || selected.gateReason || selected.invalidationReason || '—'}</p></section> : null}
  </div>
}

function TrendAttributionTable({ title, rows }: { title: string; rows: TrendDimensionPnl[] }) {
  return <section className="arb-table-wrap trend-compact-table"><header className="trend-table-title"><div><h2>{title}</h2></div><span>{rows.length} 类</span></header><table><thead><tr><th>分类</th><th>笔数</th><th>胜率</th><th>均赚/均亏</th><th>盈亏比/PF</th><th>期望</th><th>净盈亏</th></tr></thead><tbody>{rows.length ? rows.map((row) => <tr key={`${row.bucket}-${row.side}`}><td><b>{trendBucketText(row.bucket)}</b>{row.side && row.side !== row.bucket ? <small>{directionText(row.side)}</small> : null}</td><td>{row.closedPositions}</td><td>{row.closedPositions ? `${((row.winRate ?? row.winningPositions / row.closedPositions) * 100).toFixed(1)}%` : '—'}</td><td>{formatUsdtNoSign(row.averageWin)} / {formatUsdtNoSign(row.averageLoss)}</td><td>{formatRatio(row.payoffRatio)} / {formatRatio(row.profitFactor)}</td><td className={valueTone(row.expectancy || 0)}>{formatUsdt(row.expectancy)}</td><td className={valueTone(row.netProfit)}>{formatUsdt(row.netProfit)}</td></tr>) : <tr><td className="arb-empty" colSpan={7}>暂无归因数据</td></tr>}</tbody></table></section>
}
function TrendLedgerEventsTable({ rows, total }: { rows: TrendLedgerEvent[]; total: number }) {
  return <section className="arb-table-wrap">
    <header className="trend-table-title"><div><h2>统一账本事件</h2></div><span>{rows.length} / {total}</span></header>
    <table>
      <thead><tr><th>账户</th><th>事件</th><th>合约</th><th>开仓价</th><th>平仓价</th><th>成交数</th><th>交易量</th><th>净收益</th><th>时间</th></tr></thead>
      <tbody>{rows.length ? rows.map((row) => <tr key={row.id}>
        <td><b>{accountName(row.accountLabel)}</b></td>
        <td><b>{ledgerEventText(row.eventType)}</b><small>{directionText(row.side)}</small></td>
        <td><b>{row.contractCode || '—'}</b><small>#{row.positionId || '—'}</small></td>
        <td>{formatDecimal(row.entryPriceUsdt ?? row.executionPriceUsdt)}</td>
        <td>{formatDecimal(row.closePriceUsdt)}</td>
        <td>{formatDecimal(row.filledContracts)}</td>
        <td>{formatNotional(row.amountUsdt)}</td>
        <td className={valueTone(row.netPnlAfterFee || 0)}>{formatUsdt(row.netPnlAfterFee)}</td>
        <td><small>{row.occurredAt || '—'}</small></td>
      </tr>) : <tr><td className="arb-empty" colSpan={9}>暂无统一账本事件</td></tr>}</tbody>
    </table>
  </section>
}

function TrendOrdersTable({ rows }: { rows: TrendOrder[] }) {
  return <section className="arb-table-wrap"><header className="trend-table-title"><div><h2>最近订单</h2></div><span>{rows.length} 条</span></header><table><thead><tr><th>账户</th><th>合约 / 腿</th><th>方向</th><th>状态</th><th>数量</th><th>均价</th><th>成交额</th><th>手续费</th><th>已实现</th><th>更新时间</th></tr></thead><tbody>{rows.length ? rows.map((row) => <tr key={row.id}><td><b>{accountName(row.accountLabel)}</b></td><td><b>{row.contractCode}</b><small>{orderLegText(row.leg)} · #{row.positionId}</small></td><td><small>{directionText(row.trendSide)} / {directionText(row.orderSide)}{row.reduceOnly ? ' · 只减仓' : ''}</small></td><td><Status status={row.status} /></td><td>{row.filledContracts || row.requestVolume}</td><td>{formatDecimal(row.averagePrice)}</td><td>{formatNotional(row.notional)}</td><td>{formatUsdtNoSign(row.fee)}</td><td className={valueTone(row.realizedProfit || 0)}>{formatUsdt(row.realizedProfit)}</td><td><small>{row.updatedAt || row.createdAt || '—'}</small></td></tr>) : <tr><td className="arb-empty" colSpan={10}>暂无订单记录</td></tr>}</tbody></table></section>
}
function TrendPositionRow({ position }: { position: TrendPosition }) { return <tr><td><b>{accountName(position.accountLabel)}</b></td><td><b>{position.contractCode}</b><small>{directionText(position.side)}</small></td><td><Status status={position.status} /></td><td>{formatDecimal(position.entryAvgPrice)} / {formatDecimal(position.closeAvgPrice)}</td><td>{position.contractVolume}</td><td>{formatUsdtNoSign(position.finalTotalFee ?? ((position.entryFee || 0) + (position.closeFee || 0)))}</td><td>{formatNotional(position.turnover)}</td><td className={valueTone(position.finalNetProfit ?? position.netRealizedProfit ?? position.realizedProfit ?? 0)}>{formatUsdt(position.finalNetProfit ?? position.netRealizedProfit ?? position.realizedProfit)}</td><td><small>{position.finalPnlSource ? `${position.finalPnlSource} · 毛利 ${formatUsdt(position.finalGrossProfit)} · ${position.finalPnlCalculatedAt || ""}` : compactExitSnapshot(position)}</small></td><td><small>{position.updatedAt || '—'}</small></td></tr> }
function Status({ status }: { status: string }) {
  const upper = (status || '').toUpperCase()
  const label = upper === 'QUALIFIED' ? '可执行' : upper === 'OBSERVING' ? '观察中' : upper === 'REJECTED' ? '已拒绝' : upper === 'WARN' ? '预警' : upper === 'ACTIVE' ? '持仓中' : upper === 'DONE' ? '已完成' : upper === 'LONG' ? '多' : upper === 'SHORT' ? '空' : upper || '未知'
  return <span className={`arb-status ${upper.toLowerCase()}`}>● {label}</span>
}
function UnsupportedModePanel({ mode }: { mode: Exclude<ArbitrageMode, 'CROSS_FUNDING' | 'HTX_TREND'> }) {
  const copy = UNSUPPORTED_MODES[mode]
  return <div className="arb-unavailable-panel">
    <p className="arb-eyebrow">真实数据未接入</p>
    <h2>{copy.title}</h2>
    <p>{copy.description}</p>
    <small>{copy.next}</small>
  </div>
}
