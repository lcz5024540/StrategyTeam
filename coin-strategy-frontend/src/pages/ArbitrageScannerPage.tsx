import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { arbitrageDashboardApi } from '@/api'
import type {
  ArbitrageMode,
  FundingRateMatrixRow,
  HtxTrendDashboardResponse,
  TrendCandidateSignal,
  TrendDimensionPnl,
  TrendOrder,
  TrendPosition,
  TrendSignal,
  TrendSignalRollup,
} from '@/types/arbitrage-dashboard'

const REFRESH_MS = 10_000
const MODES: Array<{ value: ArbitrageMode; label: string; tag: string }> = [
  { value: 'CROSS_PRICE', label: '跨所套利', tag: '观察' },
  { value: 'CROSS_FUNDING', label: '对冲套利', tag: 'Paper' },
  { value: 'HTX_TREND', label: 'HTX 趋势交易', tag: 'Live' },
  { value: 'HTX_GRID', label: '网格交易', tag: '暂停' },
]
const TREND_VIEWS = [
  { value: 'overview', label: '概览', hint: '当前仓位 / 今日结果' },
  { value: 'history', label: '交易历史', hint: '仓位 / 订单账本' },
  { value: 'signals', label: '信号分析', hint: '定时信号 / 候选模型' },
  { value: 'strategy', label: '策略说明', hint: '口径 / 缺口 / 参数' },
] as const

type TrendView = typeof TREND_VIEWS[number]['value']

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
  const trendView = parseTrendView(params.get('view'))
  const [symbol, setSymbol] = useState(params.get('symbol') || '')
  const [exchange, setExchange] = useState(params.get('exchange') || 'ALL')
  const [minCapacity, setMinCapacity] = useState(Number(params.get('minCapacity') || 0))
  const [minYield, setMinYield] = useState(Number(params.get('minYield') || 0))
  const [qualifiedOnly, setQualifiedOnly] = useState(params.get('qualified') === '1')
  const { data: matrixResponse, isError, refetch, isFetching } = useQuery({ queryKey: ['fundingRateMatrix'], queryFn: arbitrageDashboardApi.getFundingRateMatrix, refetchInterval: REFRESH_MS, retry: false })
  const { data: trendResponse, isError: trendError, refetch: refetchTrend, isFetching: isFetchingTrend } = useQuery({ queryKey: ['htxTrendDashboard'], queryFn: arbitrageDashboardApi.getHtxTrendDashboard, refetchInterval: REFRESH_MS, retry: false, enabled: mode === 'HTX_TREND' })
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
        <p className="arb-eyebrow">STRATEGY WORKSTATION · {isTrendMode ? 'LIVE EXECUTION LEDGER' : 'PAPER ONLY'}</p>
        <h1>{isTrendMode ? 'HTX 趋势策略工作台' : '策略控制台'}</h1>
        <p>{isTrendMode ? '首屏只回答三件事：现在是否持仓、今天真实赚亏、最近一笔交易和信号是否健康。完整交易历史、订单账本、信号归因和策略口径放在下方视图。' : isFundingMode ? '跨所资金费按标准币对聚合：一行展示各交易所原始周期费率，最佳方向主值只看原始差；周期不一致时单独提示跨周期累计收付是否占优。' : UNSUPPORTED_MODES[mode].description}</p>
      </div>
      <div className="arb-source"><span>数据来源</span><strong>{sourceStatus}</strong><small>{sourceDetail}</small></div>
    </section>
    <section className="arb-metrics">
      <Metric label={isTrendMode ? '今日交易量' : '可执行机会'} value={isTrendMode ? formatNotional(trend?.summary.todayTurnover) : isFundingMode ? String(executableFundingRows.length) : '0'} detail={isTrendMode ? `累计 ${formatNotional(trend?.summary.totalTurnover)}` : isFundingMode ? '满足 L2、成本和同步结算条件' : '未接入真实读模型，不展示 demo'} tone={isTrendMode || isFundingMode ? 'positive' : 'neutral'} />
      <Metric label={isTrendMode ? '今日净盈亏' : isFundingMode ? '最大原始费差' : '最佳单次净收益'} value={isTrendMode ? formatUsdt(trend?.summary.todayNetRealizedProfit) : isFundingMode ? formatOpportunitySpread(bestFunding) : '—'} detail={isTrendMode ? `累计 ${formatUsdt(trend?.summary.netRealizedProfit)}` : isFundingMode ? '主展示不做按小时换算；跨周期另标累计判断' : '等待真实机会接口'} tone={isTrendMode ? valueTone(trend?.summary.todayNetRealizedProfit || 0) : isFundingMode ? 'positive' : 'neutral'} />
      <Metric label={isTrendMode ? '活动仓位' : '最大可成交容量'} value={isTrendMode ? String(trend?.summary.activePositions || 0) : isFundingMode ? formatNotional(largestFunding?.executableNotional) : '—'} detail={isTrendMode ? `今日平仓 ${trend?.summary.todayClosedPositions || 0} · 今日胜 ${trend?.summary.todayWinningPositions || 0}` : isFundingMode ? '共同基础币数量对应的单腿名义金额' : '等待真实 L2/VWAP 容量'} />
      <Metric label={isTrendMode ? '今日手续费' : isFundingMode ? '覆盖币对' : '数据状态'} value={isTrendMode ? formatUsdtNoSign(trend?.summary.todayFees) : isFundingMode ? String(fundingRows.length) : '未接入'} detail={isTrendMode ? `累计手续费 ${formatUsdtNoSign(trend?.summary.totalFees)}` : isFundingMode ? `${fundingMatrix.exchanges.length} 个交易所横向比对 · 缺失以 — 显示` : '已移除前端硬编码样例'} tone="warn" />
    </section>
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
      <div className="arb-meta"><span>{MODES.find((item) => item.value === mode)?.label} · {isTrendMode ? `当前活动仓位 ${trend?.summary.activePositions || 0}，今日平仓 ${trend?.summary.todayClosedPositions || 0}` : isFundingMode ? `当前显示 ${fundingRows.length} 个币对，其中可执行 ${executableFundingRows.length} 个` : '真实读模型未接入'}</span><span>{isTrendMode ? '口径：交易量为已成交开平仓名义额；净已实现盈亏已扣账本手续费；未实现盈亏需交易所标记价补充。' : isFundingMode ? '展示口径：原始差 = 空头费率 - 多头费率；非 8h 周期在交易所费率后标注。' : '已隐藏历史 demo 组合，避免误判为真实机会。'}</span></div>
      {isTrendMode ? <TrendDashboard trend={trend} loading={isFetchingTrend} refresh={() => void refetchTrend()} view={trendView} onViewChange={updateTrendView} /> : isFundingMode ? <div className="arb-table-wrap"><table><thead><tr>{fundingTableHeaders(fundingMatrix.exchanges).map((item) => <th key={item}>{item}</th>)}</tr></thead><tbody>{fundingRows.length ? fundingRows.map((row) => <FundingRateMatrixTableRow key={row.symbol} row={row} exchanges={fundingMatrix.exchanges} />) : <tr><td className="arb-empty" colSpan={fundingMatrix.exchanges.length + 5}>没有满足当前筛选条件的币对</td></tr>}</tbody></table></div> : <UnsupportedModePanel mode={mode as Exclude<ArbitrageMode, 'CROSS_FUNDING' | 'HTX_TREND'>} />}
    </section>
    {isFundingMode && <section className="arb-bottom-grid"><article className="arb-card arb-health"><header><div><p className="arb-eyebrow">DATA COVERAGE</p><h2>资金费矩阵覆盖</h2></div><span>{matrixResponse?.asOf || '等待接口'}</span></header>{exchangeCoverage.length ? exchangeCoverage.map((item) => <div className="arb-health-row" key={item.name}><strong className={item.count > 0 ? 'good' : 'bad'}>● {item.name}</strong><i><b style={{ width: `${fundingMatrix.items.length ? Math.round(item.count / fundingMatrix.items.length * 100) : 0}%` }} /></i><span>{item.count || '—'}</span><small>{item.count ? '有资金费样本' : '当前矩阵无样本'}</small></div>) : <p className="arb-compact-note">行情读模型尚未返回资金费样本；不会将空数据呈现为可交易机会。</p>}</article>
      <article className="arb-card arb-ranking"><p className="arb-eyebrow">DATA RULES</p><h2>排序口径</h2><ol><li>仅保留具备可执行深度的市场。</li><li>资金费统一折算到共同结算窗口。</li><li>排序前已扣除手续费与 L2 滑点。</li></ol></article></section>}
    {(isError || trendError) ? <div className="arb-notice">读模型接口暂不可用；为避免误导，当前不展示 Demo 数据。</div> : null}
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
function formatDecimal(value: number | null | undefined, digits = 6) { return value == null ? '—' : Number(value).toFixed(digits).replace(/\.0+$/, '').replace(/(\.\d*?)0+$/, '$1') }
function compactReason(value: string | null | undefined) { return value || '—' }
function trendSourceStatus(trend?: HtxTrendDashboardResponse) { return trend?.source === 'HTX_TREND_EXECUTION_LEDGER_UNAVAILABLE' ? 'HTX 趋势执行账本 · 不可用' : 'HTX 趋势执行账本 · 在线' }
function trendSourceDetail(trend?: HtxTrendDashboardResponse) {
  if (!trend) return '等待后端只读接口返回'
  if (trend.source === 'HTX_TREND_EXECUTION_LEDGER_UNAVAILABLE') return '数据库账本暂不可读，页面不会回退到人工扫描数据'
  return `订单、仓位、费用、信号和定时任务快照均来自数据库 · ${trend.asOf}`
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
  if (!trend) return <div className="arb-unavailable-panel"><p className="arb-eyebrow">HTX TREND LEDGER</p><h2>{loading ? '趋势账本加载中' : '等待趋势账本数据'}</h2><button className="arb-text-button" type="button" onClick={refresh}>刷新</button></div>
  const winRate = trend.summary.closedPositions ? trend.summary.winningPositions / trend.summary.closedPositions : null
  const lastPosition = trend.recentPositions.find((position) => position.status.toUpperCase() === 'DONE') || trend.recentPositions[0]
  const dataCoverage = [
    { label: '活动仓位', value: trend.activePositions.length },
    { label: '平仓历史', value: trend.recentPositions.length },
    { label: '订单账本', value: trend.recentOrders.length },
    { label: '定时信号', value: trend.recentSignals.length },
    { label: '候选信号', value: trend.recentCandidateSignals.length },
  ]
  return <div className="trend-dashboard">
    <section className="trend-command-bar">
      <div><p className="arb-eyebrow">HTX TREND</p><h2>Live execution ledger</h2></div>
      <div className="trend-command-actions"><span>{trend.asOf}</span><button className="arb-text-button" type="button" onClick={refresh}>{loading ? '刷新中' : '刷新'}</button></div>
    </section>
    <section className="trend-runtime-grid">
      <TrendMiniMetric label="运行态" value={trend.summary.activePositions > 0 ? '持仓中' : '等待信号'} detail={`最新信号 ${trend.runtime.latestSignalAt || '—'}`} tone={trend.summary.activePositions > 0 ? 'positive' : 'neutral'} />
      <TrendMiniMetric label="今日表现" value={formatUsdt(trend.summary.todayNetRealizedProfit)} detail={`平仓 ${trend.summary.todayClosedPositions} / 胜 ${trend.summary.todayWinningPositions} / 手续费 ${formatUsdtNoSign(trend.summary.todayFees)}`} tone={valueTone(trend.summary.todayNetRealizedProfit)} />
      <TrendMiniMetric label="累计胜率" value={winRate == null ? '—' : `${(winRate * 100).toFixed(1)}%`} detail={`已平仓 ${trend.summary.closedPositions} / 总仓位 ${trend.summary.totalPositions}`} />
      <TrendMiniMetric label="最近订单" value={trend.runtime.latestOrderAt || '—'} detail={`订单 ${trend.recentOrders.length} 条 / 最新仓位 ${trend.runtime.latestPositionAt || '—'}`} />
    </section>
    <TrendViewTabs view={view} onViewChange={onViewChange} />
    {view === 'overview' ? <TrendOverview trend={trend} lastPosition={lastPosition} onViewChange={onViewChange} /> : null}
    {view === 'history' ? <TrendHistoryView positions={trend.recentPositions} orders={trend.recentOrders} /> : null}
    {view === 'signals' ? <TrendSignalsView rollup={trend.signalRollup} signals={trend.recentSignals} candidates={trend.recentCandidateSignals} /> : null}
    {view === 'strategy' ? <TrendStrategyView trend={trend} dataCoverage={dataCoverage} /> : null}
  </div>
}
function TrendViewTabs({ view, onViewChange }: { view: TrendView; onViewChange: (view: TrendView) => void }) {
  return <div className="trend-view-tabs" role="tablist">{TREND_VIEWS.map((item) => <button key={item.value} type="button" role="tab" aria-selected={view === item.value} className={view === item.value ? 'active' : ''} onClick={() => onViewChange(item.value)}><strong>{item.label}</strong><small>{item.hint}</small></button>)}</div>
}
function TrendOverview({ trend, lastPosition, onViewChange }: { trend: HtxTrendDashboardResponse; lastPosition?: TrendPosition; onViewChange: (view: TrendView) => void }) {
  return <div className="trend-overview">
    <section className="trend-overview-grid">
      <TrendNowPanel positions={trend.activePositions} />
      <TrendLastTradePanel position={lastPosition} onHistory={() => onViewChange('history')} />
    </section>
    <TrendDailyPnlChart rows={trend.dailyPnl} />
    <section className="trend-two-column">
      <TrendAttributionTable title="今日方向盈亏" eyebrow="SIDE ATTRIBUTION · TODAY" rows={trend.todaySidePnl} />
      <TrendAttributionTable title="今日退出原因" eyebrow="EXIT REASON · TODAY" rows={trend.exitReasonPnl} />
    </section>
    <section className="trend-overview-grid">
      <TrendSignalPulse trend={trend} onSignals={() => onViewChange('signals')} />
      <TrendDataCoverage trend={trend} onStrategy={() => onViewChange('strategy')} />
    </section>
  </div>
}
function TrendNowPanel({ positions }: { positions: TrendPosition[] }) {
  const first = positions[0]
  return <section className="trend-panel trend-now">
    <header><div><p className="arb-eyebrow">CURRENT POSITION</p><h2>当前仓位</h2></div><span>{positions.length} 条</span></header>
    {first ? <div className="trend-current-position">
      <div><strong>{first.contractCode}</strong><Status status={first.side} /></div>
      <dl><div><dt>数量</dt><dd>{first.contractVolume}</dd></div><div><dt>开仓均价</dt><dd>{formatDecimal(first.entryAvgPrice)}</dd></div><div><dt>追踪止损</dt><dd>{formatDecimal(first.trailingStopPrice ?? first.stopPrice)}</dd></div><div><dt>更新时间</dt><dd>{first.updatedAt || '—'}</dd></div></dl>
      <p>{first.entrySignal || first.message || '—'}</p>
      {positions.length > 1 ? <small>另有 {positions.length - 1} 条活动仓位，可在交易历史视图完整查看。</small> : null}
    </div> : <div className="trend-empty-state"><strong>当前没有活动趋势仓位</strong><small>下一笔开仓需要候选信号、定时信号、流动性、点差与风险 gate 同时满足。</small></div>}
  </section>
}
function TrendLastTradePanel({ position, onHistory }: { position?: TrendPosition; onHistory: () => void }) {
  return <section className="trend-panel">
    <header><div><p className="arb-eyebrow">LAST CLOSED TRADE</p><h2>最近交易</h2></div><button className="arb-text-button" type="button" onClick={onHistory}>完整历史</button></header>
    {position ? <div className="trend-last-trade">
      <div><strong>{position.contractCode}</strong><Status status={position.side} /></div>
      <dl><div><dt>净盈亏</dt><dd className={valueTone(position.netRealizedProfit ?? position.realizedProfit ?? 0)}>{formatUsdt(position.netRealizedProfit ?? position.realizedProfit)}</dd></div><div><dt>开 / 平仓</dt><dd>{formatDecimal(position.entryAvgPrice)} / {formatDecimal(position.closeAvgPrice)}</dd></div><div><dt>手续费</dt><dd>{formatUsdtNoSign((position.entryFee || 0) + (position.closeFee || 0))}</dd></div><div><dt>退出原因</dt><dd>{position.exitReason || '—'}</dd></div></dl>
    </div> : <div className="trend-empty-state"><strong>暂无已平仓记录</strong><small>策略账本返回空历史时，页面不会补造收益样例。</small></div>}
  </section>
}
function TrendSignalPulse({ trend, onSignals }: { trend: HtxTrendDashboardResponse; onSignals: () => void }) {
  return <section className="trend-panel">
    <header><div><p className="arb-eyebrow">SIGNAL PULSE · 30M</p><h2>最近信号结构</h2></div><button className="arb-text-button" type="button" onClick={onSignals}>信号分析</button></header>
    <div className="trend-pulse-grid">
      <span><b>{trend.runtime.scannedContractsLast30m}</b><small>扫描合约</small></span>
      <span><b>{trend.runtime.longSignalsLast30m}</b><small>LONG</small></span>
      <span><b>{trend.runtime.shortSignalsLast30m}</b><small>SHORT</small></span>
      <span><b>{trend.runtime.flatSignalsLast30m}</b><small>FLAT</small></span>
    </div>
    <p className="trend-muted">候选模型：多 {trend.runtime.candidateLongLast30m} / 空 {trend.runtime.candidateShortLast30m} / 平 {trend.runtime.candidateFlatLast30m}</p>
  </section>
}
function TrendDataCoverage({ trend, onStrategy }: { trend: HtxTrendDashboardResponse; onStrategy: () => void }) {
  return <section className="trend-panel">
    <header><div><p className="arb-eyebrow">DATA COVERAGE</p><h2>策略数据完整度</h2></div><button className="arb-text-button" type="button" onClick={onStrategy}>口径</button></header>
    <div className="trend-coverage-list">
      <span><b>{trend.activePositions.length}</b><small>活动仓位</small></span>
      <span><b>{trend.recentPositions.length}</b><small>交易历史</small></span>
      <span><b>{trend.recentOrders.length}</b><small>订单</small></span>
      <span><b>{trend.recentSignals.length}</b><small>定时信号</small></span>
      <span><b>{trend.recentCandidateSignals.length}</b><small>候选信号</small></span>
    </div>
  </section>
}
function TrendDailyPnlChart({ rows }: { rows: HtxTrendDashboardResponse['dailyPnl'] }) {
  const days = [...rows].reverse()
  const max = Math.max(0.01, ...days.map((item) => Math.abs(item.netProfit)))
  return <section className="trend-chart"><header><div><p className="arb-eyebrow">DAILY REALIZED PNL</p><h2>日度已实现净收益</h2></div><span>{days.length} 天</span></header><div className="trend-bars">{days.length ? days.map((item) => <div className="trend-bar-item" key={item.tradeDate}><i className={item.netProfit >= 0 ? 'positive' : 'negative'} style={{ height: `${Math.max(5, Math.abs(item.netProfit) / max * 100)}%` }} title={`${item.tradeDate}: ${formatUsdt(item.netProfit)}`} /><strong>{formatUsdt(item.netProfit)}</strong><small>{item.tradeDate}</small></div>) : <p>暂无已平仓日度记录。趋势仓位仍在运行或尚未完成平仓。</p>}</div></section>
}
function TrendHistoryView({ positions, orders }: { positions: TrendPosition[]; orders: TrendOrder[] }) {
  const [side, setSide] = useState('ALL')
  const [status, setStatus] = useState('ALL')
  const [symbol, setSymbol] = useState('')
  const filteredPositions = positions.filter((position) => (!symbol || position.contractCode.includes(symbol.toUpperCase())) && (side === 'ALL' || position.side === side) && (status === 'ALL' || position.status === status))
  return <div className="trend-view-stack">
    <section className="trend-toolbar">
      <div><p className="arb-eyebrow">EXECUTION HISTORY</p><h2>交易历史记录</h2></div>
      <label>合约<input value={symbol} onChange={(event) => setSymbol(event.target.value)} placeholder="BTC、SOL" /></label>
      <label>方向<select value={side} onChange={(event) => setSide(event.target.value)}><option value="ALL">全部</option><option value="LONG">LONG</option><option value="SHORT">SHORT</option></select></label>
      <label>状态<select value={status} onChange={(event) => setStatus(event.target.value)}><option value="ALL">全部</option><option value="ACTIVE">持仓中</option><option value="DONE">已完成</option></select></label>
    </section>
    <section className="arb-table-wrap"><header className="trend-table-title"><div><p className="arb-eyebrow">POSITIONS</p><h2>仓位账本</h2></div><span>{filteredPositions.length} / {positions.length}</span></header><table><thead><tr><th>合约 / 方向</th><th>状态</th><th>开仓 / 平仓价</th><th>数量</th><th>手续费</th><th>交易量</th><th>净盈亏</th><th>退出原因</th><th>更新时间</th></tr></thead><tbody>{filteredPositions.length ? filteredPositions.map((position) => <TrendPositionRow key={position.id} position={position} />) : <tr><td className="arb-empty" colSpan={9}>没有符合筛选条件的仓位记录</td></tr>}</tbody></table></section>
    <TrendOrdersTable rows={orders} />
  </div>
}
function TrendSignalsView({ rollup, signals, candidates }: { rollup: TrendSignalRollup[]; signals: TrendSignal[]; candidates: TrendCandidateSignal[] }) {
  const [signal, setSignal] = useState('ALL')
  const [symbol, setSymbol] = useState('')
  const filteredSignals = signals.filter((row) => (!symbol || row.contractCode.includes(symbol.toUpperCase())) && (signal === 'ALL' || row.trendSignal === signal))
  const filteredCandidates = candidates.filter((row) => (!symbol || row.contractCode.includes(symbol.toUpperCase())) && (signal === 'ALL' || row.direction === signal))
  return <div className="trend-view-stack">
    <section className="trend-toolbar">
      <div><p className="arb-eyebrow">SIGNAL ANALYSIS</p><h2>信号分析</h2></div>
      <label>合约<input value={symbol} onChange={(event) => setSymbol(event.target.value)} placeholder="BTC、SOL" /></label>
      <label>信号<select value={signal} onChange={(event) => setSignal(event.target.value)}><option value="ALL">全部</option><option value="LONG">LONG</option><option value="SHORT">SHORT</option><option value="FLAT">FLAT</option></select></label>
    </section>
    <TrendSignalRollupTable rows={rollup} />
    <TrendSignalsTable rows={filteredSignals} total={signals.length} />
    <TrendCandidateTable rows={filteredCandidates} total={candidates.length} />
  </div>
}
function TrendStrategyView({ trend, dataCoverage }: { trend: HtxTrendDashboardResponse; dataCoverage: Array<{ label: string; value: number }> }) {
  return <div className="trend-strategy-grid">
    <section className="trend-panel">
      <header><div><p className="arb-eyebrow">STRATEGY LOOP</p><h2>策略闭环</h2></div><span>research to signal to execution</span></header>
      <ol className="trend-rule-list"><li>研究/规则：趋势方向、突破/回撤、ADX、ATR、动量、量能共同形成候选信号。</li><li>风控/gate：最终开仓仍需通过点差、流动性、冷却、仓位和执行窗口约束。</li><li>执行/账本：订单、手续费、开平仓价格、退出原因和净收益必须回写到账本。</li><li>复盘/归因：按方向、退出原因、日度 PnL、信号分布持续看策略质量。</li></ol>
    </section>
    <section className="trend-panel">
      <header><div><p className="arb-eyebrow">DATASET</p><h2>当前已接入数据</h2></div><span>{trend.asOf}</span></header>
      <div className="trend-coverage-list strategy">{dataCoverage.map((item) => <span key={item.label}><b>{item.value}</b><small>{item.label}</small></span>)}</div>
      <p className="trend-muted">页面只展示后端读模型返回的数据；策略参数阈值、实时交易所持仓标记价、未实现盈亏和逐笔撮合明细目前还不是该接口的一部分。</p>
    </section>
    <section className="trend-panel trend-wide">
      <header><div><p className="arb-eyebrow">QUANT REFERENCE</p><h2>页面指标口径</h2></div></header>
      <div className="trend-definition-grid">
        <span><b>净已实现盈亏</b><small>仓位账本的 realized profit 扣除 entry/close fee 后展示。</small></span>
        <span><b>方向归因</b><small>按 LONG / SHORT 聚合 closed positions、winning positions、fees、turnover 和 net profit。</small></span>
        <span><b>退出原因</b><small>以平仓记录的 exitReason 聚合，辅助定位止损、信号反转、风控退出。</small></span>
        <span><b>信号分布</b><small>24 小时定时信号按 trendSignal 和 reasonBucket 汇总，最近信号保留 ADX/ATR/价差/资金费。</small></span>
      </div>
    </section>
  </div>
}
function TrendMiniMetric({ label, value, detail, tone = 'neutral' }: { label: string; value: string; detail: string; tone?: string }) { return <article className={`trend-mini ${tone}`}><span>{label}</span><strong>{value}</strong><small>{detail}</small></article> }
function TrendAttributionTable({ title, eyebrow, rows }: { title: string; eyebrow: string; rows: TrendDimensionPnl[] }) {
  return <section className="arb-table-wrap trend-compact-table"><header className="trend-table-title"><div><p className="arb-eyebrow">{eyebrow}</p><h2>{title}</h2></div><span>{rows.length} 类</span></header><table><thead><tr><th>分类</th><th>笔数</th><th>胜率</th><th>交易量</th><th>手续费</th><th>净盈亏</th></tr></thead><tbody>{rows.length ? rows.map((row) => <tr key={`${row.bucket}-${row.side}`}><td><b>{row.bucket || '—'}</b>{row.side && row.side !== row.bucket ? <small>{row.side}</small> : null}</td><td>{row.closedPositions}</td><td>{row.closedPositions ? `${(row.winningPositions / row.closedPositions * 100).toFixed(1)}%` : '—'}</td><td>{formatNotional(row.turnover)}</td><td>{formatUsdtNoSign(row.fees)}</td><td className={valueTone(row.netProfit)}>{formatUsdt(row.netProfit)}</td></tr>) : <tr><td className="arb-empty" colSpan={6}>暂无归因数据</td></tr>}</tbody></table></section>
}
function TrendSignalRollupTable({ rows }: { rows: TrendSignalRollup[] }) {
  return <section className="arb-table-wrap trend-compact-table"><header className="trend-table-title"><div><p className="arb-eyebrow">SCHEDULED SIGNALS · 24H</p><h2>定时信号分布</h2></div><span>{rows.length} 类</span></header><table><thead><tr><th>信号</th><th>原因</th><th>次数 / 合约</th><th>ADX</th><th>ATR%</th><th>最新时间</th></tr></thead><tbody>{rows.length ? rows.map((row) => <tr key={`${row.trendSignal}-${row.reasonBucket}`}><td><Status status={row.trendSignal} /></td><td><small>{compactReason(row.reasonBucket)}</small></td><td>{row.signals} / {row.contracts}</td><td>{formatDecimal(row.avgAdx, 2)}</td><td>{formatPercent(row.avgAtrPct)}</td><td><small>{row.latestAt || '—'}</small></td></tr>) : <tr><td className="arb-empty" colSpan={6}>暂无最近 24 小时信号</td></tr>}</tbody></table></section>
}
function TrendOrdersTable({ rows }: { rows: TrendOrder[] }) {
  return <section className="arb-table-wrap"><header className="trend-table-title"><div><p className="arb-eyebrow">ORDER LEDGER</p><h2>最近订单</h2></div><span>{rows.length} 条</span></header><table><thead><tr><th>合约 / 腿</th><th>方向</th><th>状态</th><th>数量</th><th>均价</th><th>成交额</th><th>手续费</th><th>已实现</th><th>更新时间</th></tr></thead><tbody>{rows.length ? rows.map((row) => <tr key={row.id}><td><b>{row.contractCode}</b><small>{row.leg} · #{row.positionId}</small></td><td><small>{row.trendSide} / {row.orderSide}{row.reduceOnly ? ' · reduce' : ''}</small></td><td><Status status={row.status} /></td><td>{row.filledContracts || row.requestVolume}</td><td>{formatDecimal(row.averagePrice)}</td><td>{formatNotional(row.notional)}</td><td>{formatUsdtNoSign(row.fee)}</td><td className={valueTone(row.realizedProfit || 0)}>{formatUsdt(row.realizedProfit)}</td><td><small>{row.updatedAt || row.createdAt || '—'}</small></td></tr>) : <tr><td className="arb-empty" colSpan={9}>暂无订单记录</td></tr>}</tbody></table></section>
}
function TrendSignalsTable({ rows, total }: { rows: TrendSignal[]; total: number }) {
  return <section className="arb-table-wrap"><header className="trend-table-title"><div><p className="arb-eyebrow">SCHEDULED SIGNAL SNAPSHOTS</p><h2>最近定时信号</h2></div><span>{rows.length} / {total}</span></header><table><thead><tr><th>合约 / 周期</th><th>信号</th><th>价格</th><th>ADX</th><th>ATR%</th><th>价差</th><th>资金费</th><th>原因</th><th>时间</th></tr></thead><tbody>{rows.length ? rows.map((row) => <tr key={row.id}><td><b>{row.contractCode}</b><small>{row.period}</small></td><td><Status status={row.trendSignal} /></td><td>{formatDecimal(row.closePrice)}</td><td>{formatDecimal(row.adx, 2)}</td><td>{formatPercent(row.atrPct)}</td><td>{formatPercent(row.spreadRate)}</td><td>{formatPercent(row.fundingRate)}</td><td><small>{compactReason(row.reason)}</small></td><td><small>{row.createdAt}</small></td></tr>) : <tr><td className="arb-empty" colSpan={9}>暂无定时信号</td></tr>}</tbody></table></section>
}
function TrendCandidateTable({ rows, total }: { rows: TrendCandidateSignal[]; total: number }) {
  return <section className="arb-table-wrap"><header className="trend-table-title"><div><p className="arb-eyebrow">ENSEMBLE CANDIDATES</p><h2>组合模型候选信号</h2></div><span>{rows.length} / {total}</span></header><table><thead><tr><th>合约 / 周期</th><th>方向 / 状态</th><th>分数</th><th>ADX</th><th>ATR%</th><th>动量</th><th>量能</th><th>说明</th><th>时间</th></tr></thead><tbody>{rows.length ? rows.map((row) => <tr key={row.id}><td><b>{row.contractCode}</b><small>{row.period}</small></td><td><Status status={row.direction} /><small>{row.regime}</small></td><td><b>{formatDecimal(row.signalScore, 1)}</b><small>L {formatDecimal(row.longScore, 1)} / S {formatDecimal(row.shortScore, 1)} / gap {formatDecimal(row.scoreGap, 1)}</small></td><td>{formatDecimal(row.adx, 2)}</td><td>{formatPercent(row.atrPct)}</td><td>{formatPercent(row.momentumPct)}</td><td>{formatDecimal(row.volumeRatio, 2)}</td><td><small>{compactReason(row.entryReason || row.invalidationReason)}</small></td><td><small>{row.createdAt}</small></td></tr>) : <tr><td className="arb-empty" colSpan={9}>暂无组合模型候选信号</td></tr>}</tbody></table></section>
}
function TrendPositionRow({ position }: { position: TrendPosition }) { return <tr><td><b>{position.contractCode}</b><small>{position.side}</small></td><td><Status status={position.status} /></td><td>{formatDecimal(position.entryAvgPrice)} / {formatDecimal(position.closeAvgPrice)}</td><td>{position.contractVolume}</td><td>{formatUsdtNoSign((position.entryFee || 0) + (position.closeFee || 0))}</td><td>{formatNotional(position.turnover)}</td><td className={valueTone(position.netRealizedProfit ?? position.realizedProfit ?? 0)}>{formatUsdt(position.netRealizedProfit ?? position.realizedProfit)}</td><td><small>{position.exitReason || position.entrySignal || '—'}</small></td><td><small>{position.updatedAt || '—'}</small></td></tr> }
function Status({ status }: { status: string }) {
  const upper = (status || '').toUpperCase()
  const label = upper === 'QUALIFIED' ? '可执行' : upper === 'OBSERVING' ? '观察中' : upper === 'REJECTED' ? '已拒绝' : upper === 'ACTIVE' ? '持仓中' : upper === 'DONE' ? '已完成' : upper === 'LONG' ? '多' : upper === 'SHORT' ? '空' : upper || '未知'
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
