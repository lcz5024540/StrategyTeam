import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { arbitrageDashboardApi } from '@/api'
import type { ArbitrageMode, FundingRateMatrixRow, HtxTrendDashboardResponse, TrendPosition } from '@/types/arbitrage-dashboard'

const REFRESH_MS = 10_000
const MODES: Array<{ value: ArbitrageMode; label: string; tag: string }> = [
  { value: 'CROSS_PRICE', label: '跨所套利', tag: '观察' },
  { value: 'CROSS_FUNDING', label: '对冲套利', tag: 'Paper' },
  { value: 'HTX_TREND', label: 'HTX 趋势交易', tag: 'Live' },
  { value: 'HTX_GRID', label: '网格交易', tag: '暂停' },
]

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

export function ArbitrageScannerPage() {
  const [params, setParams] = useSearchParams()
  const [mode, setMode] = useState<ArbitrageMode>(() => parseMode(params.get('mode')))
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
  const sourceStatus = isTrendMode ? 'HTX 趋势执行账本 · 在线' : isFundingMode && matrixResponse ? '跨所资金费读模型 · 在线' : '未接入该模式真实读模型'
  const sourceDetail = isTrendMode ? '订单、仓位、费用与已实现盈亏均由交易执行账本聚合' : isFundingMode
    ? `${matrixResponse?.source || '等待 funding-rate-matrix'}${(matrixResponse?.partial ?? false) ? ` · 部分数据：${(matrixResponse?.missingExchanges || []).join('、') || '未知'}` : ''}`
    : '已隐藏前端示例数据，等待后端只读接口'
  const exchangeCoverage = fundingMatrix.exchanges.map((name) => ({
    name,
    count: fundingMatrix.items.filter((row) => row.ratesByExchange[name] != null).length,
  }))

  const updateMode = (next: ArbitrageMode) => { setMode(next); setParams((current) => { current.set('mode', next); return current }, { replace: true }) }
  const resetFilters = () => { setSymbol(''); setExchange('ALL'); setMinCapacity(0); setMinYield(0); setQualifiedOnly(false); setParams({ mode }) }
  const copyView = async () => { await navigator.clipboard?.writeText(location.href) }

  const trend = trendResponse
  return <div className="ms-page arb-page">
    <section className="arb-hero">
      <div><p className="arb-eyebrow">STRATEGY WORKSTATION · {isTrendMode ? 'LIVE EXECUTION LEDGER' : 'PAPER ONLY'}</p><h1>策略控制台</h1><p>{isTrendMode ? '只读展示真实趋势执行账本：成交量、手续费、已实现盈亏、当前仓位与按日归因。未实现盈亏需以交易所实时标记价另行核对。' : isFundingMode ? '跨所资金费按标准币对聚合：一行展示各交易所原始周期费率，最佳方向主值只看原始差；周期不一致时单独提示跨周期累计收付是否占优。' : UNSUPPORTED_MODES[mode].description}</p></div>
      <div className="arb-source"><span>数据来源</span><strong>{sourceStatus}</strong><small>{sourceDetail}</small></div>
    </section>
    <section className="arb-metrics">
      <Metric label={isTrendMode ? '累计交易量' : '可执行机会'} value={isTrendMode ? formatNotional(trend?.summary.totalTurnover) : isFundingMode ? String(executableFundingRows.length) : '0'} detail={isTrendMode ? '开仓与平仓已成交名义金额之和' : isFundingMode ? '满足 L2、成本和同步结算条件' : '未接入真实读模型，不展示 demo'} tone={isTrendMode || isFundingMode ? 'positive' : 'neutral'} />
      <Metric label={isTrendMode ? '净已实现盈亏' : isFundingMode ? '最大原始费差' : '最佳单次净收益'} value={isTrendMode ? formatUsdt(trend?.summary.netRealizedProfit) : isFundingMode ? formatOpportunitySpread(bestFunding) : '—'} detail={isTrendMode ? '已实现盈亏减开平手续费' : isFundingMode ? '主展示不做按小时换算；跨周期另标累计判断' : '等待真实机会接口'} tone={isTrendMode ? valueTone(trend?.summary.netRealizedProfit || 0) : isFundingMode ? 'positive' : 'neutral'} />
      <Metric label={isTrendMode ? '活动仓位' : '最大可成交容量'} value={isTrendMode ? String(trend?.summary.activePositions || 0) : isFundingMode ? formatNotional(largestFunding?.executableNotional) : '—'} detail={isTrendMode ? `已平仓 ${trend?.summary.closedPositions || 0} · 胜 ${trend?.summary.winningPositions || 0}` : isFundingMode ? '共同基础币数量对应的单腿名义金额' : '等待真实 L2/VWAP 容量'} />
      <Metric label={isTrendMode ? '累计手续费' : isFundingMode ? '覆盖币对' : '数据状态'} value={isTrendMode ? formatUsdt(trend?.summary.totalFees) : isFundingMode ? String(fundingRows.length) : '未接入'} detail={isTrendMode ? '账本已确认的开仓与平仓费用' : isFundingMode ? `${fundingMatrix.exchanges.length} 个交易所横向比对 · 缺失以 — 显示` : '已移除前端硬编码样例'} tone="warn" />
    </section>
    <section className="arb-card">
      <div className="arb-tabs" role="tablist">{MODES.map((item) => <button key={item.value} type="button" role="tab" aria-selected={mode === item.value} className={mode === item.value ? 'active' : ''} onClick={() => updateMode(item.value)}>{item.label}<span>{item.tag}</span></button>)}</div>
      {isFundingMode ? <div className="arb-filters">
        <label>⌕ <input value={symbol} onChange={(event) => setSymbol(event.target.value)} placeholder="筛选币种，例如 BTC、ETH" /></label>
        <label>交易所<select value={exchange} onChange={(event) => setExchange(event.target.value)}><option value="ALL">全部交易所</option>{fundingMatrix.exchanges.map((item) => <option key={item} value={item}>{item}</option>)}</select></label>
        <label>最小容量<input type="number" min="0" value={minCapacity || ''} onChange={(event) => setMinCapacity(Number(event.target.value) || 0)} placeholder="USDT" /></label>
        <label>最小净收益<input type="number" min="0" step="0.01" value={minYield || ''} onChange={(event) => setMinYield(Number(event.target.value) || 0)} placeholder="%" /></label>
        <label className="arb-toggle"><input type="checkbox" checked={qualifiedOnly} onChange={(event) => setQualifiedOnly(event.target.checked)} /><span />仅可执行</label>
        <button className="arb-text-button" type="button" onClick={resetFilters}>重置</button><button className="arb-text-button" type="button" onClick={() => void refetch()}>{isFetching ? '刷新中' : '刷新'}</button><button className="arb-text-button" type="button" onClick={() => void copyView()}>复制视图</button>
      </div> : null}
      <div className="arb-meta"><span>{MODES.find((item) => item.value === mode)?.label}扫描 · {isTrendMode ? `当前活动仓位 ${trend?.summary.activePositions || 0}，已平仓 ${trend?.summary.closedPositions || 0}` : isFundingMode ? `当前显示 ${fundingRows.length} 个币对，其中可执行 ${executableFundingRows.length} 个` : '真实读模型未接入'}</span><span>{isTrendMode ? '口径：交易量为已成交开平仓名义额；净已实现盈亏已扣账本手续费' : isFundingMode ? '展示口径：原始差 = 空头费率 - 多头费率；非 8h 周期在交易所费率后标注' : '已隐藏历史 demo 组合，避免误判为真实机会'}</span></div>
      {isTrendMode ? <TrendDashboard trend={trend} loading={isFetchingTrend} refresh={() => void refetchTrend()} /> : isFundingMode ? <div className="arb-table-wrap"><table><thead><tr>{fundingTableHeaders(fundingMatrix.exchanges).map((item) => <th key={item}>{item}</th>)}</tr></thead><tbody>{fundingRows.length ? fundingRows.map((row) => <FundingRateMatrixTableRow key={row.symbol} row={row} exchanges={fundingMatrix.exchanges} />) : <tr><td className="arb-empty" colSpan={fundingMatrix.exchanges.length + 5}>没有满足当前筛选条件的币对</td></tr>}</tbody></table></div> : <UnsupportedModePanel mode={mode as Exclude<ArbitrageMode, 'CROSS_FUNDING' | 'HTX_TREND'>} />}
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
function rankFundingOpportunity(row: FundingRateMatrixRow) {
  return row.cadenceAdjusted && row.settlementWindowSpread != null
    ? row.settlementWindowSpread
    : bestRawFundingSpread(row)
}
function hasCadenceOpportunity(row: FundingRateMatrixRow) {
  const settlementWindowSpread = row.settlementWindowSpread
  return Boolean(row.cadenceAdjusted && settlementWindowSpread != null && settlementWindowSpread > 0)
}
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
    <td className="arb-best-opportunity"><b>{direction}</b><span className={`arb-best-spread ${cadenceOpportunity ? 'mismatch' : ''}`}><strong>原始差 {formatPercent(rawSpread)}</strong><small>{intervalHint ? `周期 ${intervalHint}` : '按上方费率直接相减'}</small></span>{cadenceText ? <small className="arb-window-spread">{cadenceText}</small> : null}{cadenceOpportunity ? <span className="arb-spread-warning">⚡ 高频腿累计收款覆盖低频腿付款</span> : null}</td>
    <td>{formatNotional(row.executableNotional)}<small>净收益 {formatPercent(row.estimatedNetYield)}</small></td>
    <td><Status status={isExecutableStatus(row.status) ? 'QUALIFIED' : row.status === 'OBSERVING' ? 'OBSERVING' : 'REJECTED'} />{row.rejectionReason ? <small>{row.rejectionReason}</small> : null}</td>
  </tr>
}
function TrendDashboard({ trend, loading, refresh }: { trend?: HtxTrendDashboardResponse; loading: boolean; refresh: () => void }) {
  if (!trend) return <div className="arb-unavailable-panel"><p className="arb-eyebrow">HTX TREND LEDGER</p><h2>{loading ? '趋势账本加载中' : '等待趋势账本数据'}</h2><button className="arb-text-button" type="button" onClick={refresh}>刷新</button></div>
  const days = [...trend.dailyPnl].reverse()
  const max = Math.max(0.01, ...days.map((item) => Math.abs(item.netProfit)))
  return <div className="trend-dashboard">
    <section className="trend-chart"><header><div><p className="arb-eyebrow">DAILY REALIZED PNL</p><h2>日度已实现净收益</h2></div><button className="arb-text-button" type="button" onClick={refresh}>{loading ? '刷新中' : '刷新'}</button></header><div className="trend-bars">{days.length ? days.map((item) => <div className="trend-bar-item" key={item.tradeDate}><i className={item.netProfit >= 0 ? 'positive' : 'negative'} style={{ height: `${Math.max(5, Math.abs(item.netProfit) / max * 100)}%` }} title={`${item.tradeDate}: ${formatUsdt(item.netProfit)}`} /><strong>{formatUsdt(item.netProfit)}</strong><small>{item.tradeDate}</small></div>) : <p>暂无已平仓日度记录。趋势仓位仍在运行或尚未完成平仓。</p>}</div></section>
    <section className="arb-table-wrap"><header className="trend-table-title"><div><p className="arb-eyebrow">ACTIVE POSITIONS</p><h2>当前仓位</h2></div><span>{trend.activePositions.length} 条</span></header><table><thead><tr><th>合约 / 方向</th><th>状态</th><th>数量</th><th>开仓价</th><th>追踪止损</th><th>开仓原因</th><th>更新时间</th></tr></thead><tbody>{trend.activePositions.length ? trend.activePositions.map((position) => <TrendPositionRow key={position.id} position={position} active />) : <tr><td className="arb-empty" colSpan={7}>当前没有活动趋势仓位</td></tr>}</tbody></table></section>
    <section className="arb-table-wrap"><header className="trend-table-title"><div><p className="arb-eyebrow">EXECUTION HISTORY</p><h2>最近仓位与已实现盈亏</h2></div><span>{trend.recentPositions.length} 条</span></header><table><thead><tr><th>合约 / 方向</th><th>状态</th><th>开仓 / 平仓价</th><th>手续费</th><th>已实现盈亏</th><th>退出原因</th><th>更新时间</th></tr></thead><tbody>{trend.recentPositions.map((position) => <TrendPositionRow key={position.id} position={position} />)}</tbody></table></section>
  </div>
}
function TrendPositionRow({ position, active = false }: { position: TrendPosition; active?: boolean }) { return <tr><td><b>{position.contractCode}</b><small>{position.side}</small></td><td><Status status={position.status === 'ACTIVE' ? 'QUALIFIED' : position.status === 'DONE' ? 'OBSERVING' : 'REJECTED'} /></td>{active ? <><td>{position.contractVolume}</td><td>{position.entryAvgPrice ?? '—'}</td><td>{position.trailingStopPrice ?? position.stopPrice ?? '—'}</td><td><small>{position.entrySignal || '—'}</small></td></> : <><td>{position.entryAvgPrice ?? '—'} / {position.closeAvgPrice ?? '—'}</td><td>{formatUsdt((position.entryFee || 0) + (position.closeFee || 0))}</td><td className={valueTone(position.realizedProfit || 0)}>{formatUsdt(position.realizedProfit)}</td><td><small>{position.exitReason || position.entrySignal || '—'}</small></td></>}<td><small>{position.updatedAt || '—'}</small></td></tr> }
function Status({ status }: { status: string }) { const label = status === 'QUALIFIED' ? '可执行' : status === 'OBSERVING' ? '观察中' : '已拒绝'; return <span className={`arb-status ${status.toLowerCase()}`}>● {label}</span> }
function UnsupportedModePanel({ mode }: { mode: Exclude<ArbitrageMode, 'CROSS_FUNDING' | 'HTX_TREND'> }) {
  const copy = UNSUPPORTED_MODES[mode]
  return <div className="arb-unavailable-panel">
    <p className="arb-eyebrow">真实数据未接入</p>
    <h2>{copy.title}</h2>
    <p>{copy.description}</p>
    <small>{copy.next}</small>
  </div>
}
