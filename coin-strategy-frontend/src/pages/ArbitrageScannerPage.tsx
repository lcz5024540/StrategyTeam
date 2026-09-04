import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { arbitrageDashboardApi } from '@/api'
import type { ArbitrageMode, FundingRateMatrixRow } from '@/types/arbitrage-dashboard'

const REFRESH_MS = 10_000
const MODES: Array<{ value: ArbitrageMode; label: string; tag: string }> = [
  { value: 'CROSS_PRICE', label: '跨所价格', tag: '观察' },
  { value: 'CROSS_FUNDING', label: '跨所资金费', tag: 'Paper' },
  { value: 'SINGLE_EXCHANGE', label: '同所期现 / 永续', tag: '持仓' },
]

const EMPTY_FUNDING_MATRIX: { exchanges: string[]; items: FundingRateMatrixRow[] } = {
  exchanges: ['BINANCE', 'OKX', 'HTX', 'BITGET', 'HYPERLIQUID'],
  items: [],
}

const UNSUPPORTED_MODES: Record<Exclude<ArbitrageMode, 'CROSS_FUNDING'>, { title: string; description: string; next: string }> = {
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
  const fundingMatrix = matrixResponse?.data || EMPTY_FUNDING_MATRIX
  const isFundingMode = mode === 'CROSS_FUNDING'
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
  const sourceStatus = isFundingMode && matrixResponse ? '跨所资金费读模型 · 在线' : '未接入该模式真实读模型'
  const sourceDetail = isFundingMode
    ? `${matrixResponse?.source || '等待 funding-rate-matrix'}${(matrixResponse?.partial ?? false) ? ` · 部分数据：${(matrixResponse?.missingExchanges || []).join('、') || '未知'}` : ''}`
    : '已隐藏前端示例数据，等待后端只读接口'
  const exchangeCoverage = fundingMatrix.exchanges.map((name) => ({
    name,
    count: fundingMatrix.items.filter((row) => row.ratesByExchange[name] != null).length,
  }))

  const updateMode = (next: ArbitrageMode) => { setMode(next); setParams((current) => { current.set('mode', next); return current }, { replace: true }) }
  const resetFilters = () => { setSymbol(''); setExchange('ALL'); setMinCapacity(0); setMinYield(0); setQualifiedOnly(false); setParams({ mode }) }
  const copyView = async () => { await navigator.clipboard?.writeText(location.href) }

  return <div className="ms-page arb-page">
    <section className="arb-hero">
      <div><p className="arb-eyebrow">MARKET INTELLIGENCE · PAPER ONLY</p><h1>套利机会看板</h1><p>{isFundingMode ? '跨所资金费按标准币对聚合：一行展示各交易所原始周期费率，最佳方向主值只看原始差；周期不一致时单独提示跨周期累计收付是否占优。' : UNSUPPORTED_MODES[mode].description}</p></div>
      <div className="arb-source"><span>数据来源</span><strong>{sourceStatus}</strong><small>{sourceDetail}</small></div>
    </section>
    <section className="arb-metrics">
      <Metric label="可执行机会" value={isFundingMode ? String(executableFundingRows.length) : '0'} detail={isFundingMode ? '满足 L2、成本和同步结算条件' : '未接入真实读模型，不展示 demo'} tone={isFundingMode ? 'positive' : 'neutral'} />
      <Metric label={isFundingMode ? '最大原始费差' : '最佳单次净收益'} value={isFundingMode ? formatOpportunitySpread(bestFunding) : '—'} detail={isFundingMode ? '主展示不做按小时换算；跨周期另标累计判断' : '等待真实机会接口'} tone={isFundingMode ? 'positive' : 'neutral'} />
      <Metric label="最大可成交容量" value={isFundingMode ? formatNotional(largestFunding?.executableNotional) : '—'} detail={isFundingMode ? '共同基础币数量对应的单腿名义金额' : '等待真实 L2/VWAP 容量'} />
      <Metric label={isFundingMode ? '覆盖币对' : '数据状态'} value={isFundingMode ? String(fundingRows.length) : '未接入'} detail={isFundingMode ? `${fundingMatrix.exchanges.length} 个交易所横向比对 · 缺失以 — 显示` : '已移除前端硬编码样例'} tone="warn" />
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
      <div className="arb-meta"><span>{MODES.find((item) => item.value === mode)?.label}扫描 · {isFundingMode ? `当前显示 ${fundingRows.length} 个币对，其中可执行 ${executableFundingRows.length} 个` : '真实读模型未接入'}</span><span>{isFundingMode ? '展示口径：原始差 = 空头费率 - 多头费率；非 8h 周期在交易所费率后标注' : '已隐藏历史 demo 组合，避免误判为真实机会'}</span></div>
      {isFundingMode ? <div className="arb-table-wrap"><table><thead><tr>{fundingTableHeaders(fundingMatrix.exchanges).map((item) => <th key={item}>{item}</th>)}</tr></thead><tbody>{fundingRows.length ? fundingRows.map((row) => <FundingRateMatrixTableRow key={row.symbol} row={row} exchanges={fundingMatrix.exchanges} />) : <tr><td className="arb-empty" colSpan={fundingMatrix.exchanges.length + 5}>没有满足当前筛选条件的币对</td></tr>}</tbody></table></div> : <UnsupportedModePanel mode={mode} />}
    </section>
    <section className="arb-bottom-grid"><article className="arb-card arb-health"><header><div><p className="arb-eyebrow">DATA COVERAGE</p><h2>资金费矩阵覆盖</h2></div><span>{matrixResponse?.asOf || '等待接口'}</span></header>{exchangeCoverage.map((item) => <div className="arb-health-row" key={item.name}><strong className={item.count > 0 ? 'good' : 'bad'}>● {item.name}</strong><i><b style={{ width: `${fundingMatrix.items.length ? Math.round(item.count / fundingMatrix.items.length * 100) : 0}%` }} /></i><span>{item.count || '—'}</span><small>{item.count ? '有资金费样本' : '当前矩阵无样本'}</small></div>)}</article>
      <article className="arb-card arb-ranking"><p className="arb-eyebrow">WHY THIS RANKING</p><h2>排序口径</h2><ol><li>同一结算周期直接按原始差排序：空头交易所费率 - 多头交易所费率。</li><li>不同结算周期不展示按小时换算值；只在行内补充共同结算窗口的累计收付判断。</li><li>如果高频腿累计收款可以覆盖低频腿付款，会标记为“跨周期可行”，例如 Hyper 1h 对 Bitget 8h。</li><li>策略状态、净收益和可执行容量继续单独展示，不把高费差直接等同于可开仓。</li></ol></article></section>
    {isError ? <div className="arb-notice">读模型接口暂不可用；为避免误导，当前不展示 Demo 数据。</div> : null}
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
function Status({ status }: { status: string }) { const label = status === 'QUALIFIED' ? '可执行' : status === 'OBSERVING' ? '观察中' : '已拒绝'; return <span className={`arb-status ${status.toLowerCase()}`}>● {label}</span> }
function UnsupportedModePanel({ mode }: { mode: Exclude<ArbitrageMode, 'CROSS_FUNDING'> }) {
  const copy = UNSUPPORTED_MODES[mode]
  return <div className="arb-unavailable-panel">
    <p className="arb-eyebrow">真实数据未接入</p>
    <h2>{copy.title}</h2>
    <p>{copy.description}</p>
    <small>{copy.next}</small>
  </div>
}
