import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { arbitrageDashboardApi } from '@/api'
import type { ArbitrageDashboardSnapshot, ArbitrageMode, ArbitrageOpportunity, FundingRateMatrixRow } from '@/types/arbitrage-dashboard'

const REFRESH_MS = 10_000
const MODES: Array<{ value: ArbitrageMode; label: string; tag: string }> = [
  { value: 'CROSS_PRICE', label: '跨所价格', tag: '观察' },
  { value: 'CROSS_FUNDING', label: '跨所资金费', tag: 'Paper' },
  { value: 'SINGLE_EXCHANGE', label: '同所期现 / 永续', tag: '持仓' },
]

const demoSnapshot: ArbitrageDashboardSnapshot = {
  asOf: 'DEMO 快照', source: 'DEMO · 等待读模型 API', partial: true, missingExchanges: ['BINANCE'],
  coverage: { total: 42, displayed: 18, qualified: 6, missingL2: 8, stale: 4, unavailable: 12 },
  health: [
    { exchange: 'HTX', healthy: true, success: 99, latency: 760, note: 'L2 完整' },
    { exchange: 'OKX', healthy: true, success: 99, latency: 477, note: 'L2 完整' },
    { exchange: 'BITGET', healthy: true, success: 99, latency: 351, note: 'L2 完整' },
    { exchange: 'BINANCE', healthy: false, success: 0, latency: 0, note: 'HTTP 451 · 地域访问受限' },
  ],
  opportunities: [
    { id: 'eth-htx-okx', kind: 'CROSS_FUNDING', symbol: 'ETHUSDT', a: { exchange: 'HTX', contract: 'ETH-USDT', funding: '-0.0072%', interval: '8h', countdown: '02:18:44', vwap: '3,418.82', volume: '$1.26B', oi: '$488M' }, b: { exchange: 'OKX', contract: 'ETH-USDT-SWAP', funding: '+0.0248%', interval: '8h', countdown: '02:18:37', vwap: '3,425.11', volume: '$2.13B', oi: '$901M' }, gross: '+0.0320%', cost: '0.0200%', net: 0.012, netText: '+0.0120%', annualized: '+13.1%', capacity: 12800, capacityText: '$12.8K', basis: '+0.184%', historical: ['+0.018%', '+0.104%', '+0.390%'], status: 'QUALIFIED', reason: '', stable: '4m 22s / 3m', aDepth: [['3,418.82', '$4.8K'], ['3,419.74', '$5.1K'], ['3,420.60', '$2.9K']], bDepth: [['3,425.11', '$5.0K'], ['3,424.28', '$4.6K'], ['3,423.65', '$3.2K']] },
    { id: 'sol-bitget-okx', kind: 'CROSS_FUNDING', symbol: 'SOLUSDT', a: { exchange: 'BITGET', contract: 'SOLUSDT', funding: '-0.0050%', interval: '8h', countdown: '02:18:41', vwap: '147.24', volume: '$611M', oi: '$162M' }, b: { exchange: 'OKX', contract: 'SOL-USDT-SWAP', funding: '+0.0180%', interval: '8h', countdown: '02:18:38', vwap: '147.61', volume: '$844M', oi: '$310M' }, gross: '+0.0230%', cost: '0.0200%', net: 0.003, netText: '+0.0030%', annualized: '+3.3%', capacity: 6200, capacityText: '$6.2K', basis: '+0.251%', historical: ['+0.013%', '+0.054%', '+0.188%'], status: 'OBSERVING', reason: '成本覆盖倍数 1.15，低于 1.25 阈值', stable: '6m 09s / 3m', aDepth: [['147.24', '$2.4K'], ['147.28', '$2.1K']], bDepth: [['147.61', '$2.7K'], ['147.57', '$2.0K']] },
    { id: 'eth-price-okx-htx', kind: 'CROSS_PRICE', symbol: 'ETHUSDT', a: { exchange: 'OKX', contract: 'ETH-USDT-SWAP', funding: '+0.0248%', interval: '8h', countdown: '02:18:37', vwap: '3,429.44', volume: '$2.13B', oi: '$901M' }, b: { exchange: 'HTX', contract: 'ETH-USDT', funding: '-0.0072%', interval: '8h', countdown: '02:18:44', vwap: '3,418.82', volume: '$1.26B', oi: '$488M' }, gross: '+0.311%', cost: '0.140%', net: 0.171, netText: '+0.171%', annualized: '研究指标', capacity: 8700, capacityText: '$8.7K', basis: '+0.311%', historical: ['+0.069%', '+0.220%', '+0.542%'], status: 'OBSERVING', reason: 'OBSERVE_ONLY：跨所价格扫描尚未接入 Paper 执行', stable: '—', aDepth: [['3,429.44', '$4.2K'], ['3,428.91', '$4.5K']], bDepth: [['3,418.82', '$3.4K'], ['3,419.15', '$2.6K']] },
    { id: 'sol-price-okx-bitget', kind: 'CROSS_PRICE', symbol: 'SOLUSDT', a: { exchange: 'OKX', contract: 'SOL-USDT-SWAP', funding: '+0.0180%', interval: '8h', countdown: '02:18:38', vwap: '147.74', volume: '$844M', oi: '$310M' }, b: { exchange: 'BITGET', contract: 'SOLUSDT', funding: '-0.0050%', interval: '8h', countdown: '02:18:41', vwap: '147.21', volume: '$611M', oi: '$162M' }, gross: '+0.360%', cost: '0.140%', net: 0.22, netText: '+0.220%', annualized: '研究指标', capacity: 5400, capacityText: '$5.4K', basis: '+0.360%', historical: ['+0.042%', '+0.161%', '+0.313%'], status: 'OBSERVING', reason: 'OBSERVE_ONLY：等待价差持续性与执行延迟统计', stable: '—', aDepth: [], bDepth: [] },
    { id: 'btc-single', kind: 'SINGLE_EXCHANGE', symbol: 'BTCUSDT', a: { exchange: 'HTX', contract: 'BTC/USDT · 现货', funding: '买入现货', interval: '', countdown: '—', vwap: '112,110.0', volume: '$1.82B', oi: '—' }, b: { exchange: 'HTX', contract: 'BTC-USDT · 永续空', funding: '+0.0140%', interval: '8h', countdown: '02:18:44', vwap: '112,286.1', volume: '$1.82B', oi: '$714M' }, gross: '+0.0140%', cost: '0.100%', net: 0.006, netText: '+0.0060%', annualized: '+6.6%', capacity: 1000, capacityText: '$1.0K', basis: '+0.157%', historical: ['+0.011%', '+0.078%', '+0.281%'], status: 'QUALIFIED', reason: '保证金缓冲、现货与合约数量均满足限制', stable: '资金费有效', aDepth: [['112,110.0', '$750'], ['112,116.0', '$250']], bDepth: [['112,286.1', '$620'], ['112,279.5', '$380']] },
  ],
}

const demoFundingMatrix: { exchanges: string[]; items: FundingRateMatrixRow[] } = {
  exchanges: ['BINANCE', 'OKX', 'HTX', 'BITGET', 'HYPERLIQUID'],
  items: [
    { symbol: 'ETHUSDT', ratesByExchange: { BINANCE: null, OKX: 0.000248, HTX: -0.000072, BITGET: 0.00011, HYPERLIQUID: null }, intervalHoursByExchange: { BINANCE: null, OKX: 8, HTX: 8, BITGET: 8, HYPERLIQUID: null }, longExchange: 'HTX', shortExchange: 'OKX', hourlyFundingSpread: 0.00004, estimatedNetYield: 0.00012, executableNotional: 12800, status: 'QUALIFIED', rejectionReason: null, sampledAt: 'DEMO 快照' },
    { symbol: 'SOLUSDT', ratesByExchange: { BINANCE: null, OKX: 0.00018, HTX: null, BITGET: -0.00005, HYPERLIQUID: 0.00002 }, intervalHoursByExchange: { BINANCE: null, OKX: 8, HTX: null, BITGET: 8, HYPERLIQUID: 1 }, longExchange: 'BITGET', shortExchange: 'OKX', hourlyFundingSpread: 0.000029, estimatedNetYield: 0.00003, executableNotional: 6200, status: 'REJECTED', rejectionReason: 'COST_COVERAGE_BELOW_THRESHOLD', sampledAt: 'DEMO 快照' },
    { symbol: 'BTCUSDT', ratesByExchange: { BINANCE: null, OKX: 0.00014, HTX: 0.00009, BITGET: null, HYPERLIQUID: 0.00001 }, intervalHoursByExchange: { BINANCE: null, OKX: 8, HTX: 8, BITGET: null, HYPERLIQUID: 1 }, longExchange: 'HYPERLIQUID', shortExchange: 'OKX', hourlyFundingSpread: 0.000016, estimatedNetYield: -0.00002, executableNotional: 2000, status: 'REJECTED', rejectionReason: 'SETTLEMENT_TIME_SKEW', sampledAt: 'DEMO 快照' },
  ],
}

function valueTone(value: number) { return value > 0 ? 'positive' : value < 0 ? 'negative' : 'neutral' }
function parseMode(value: string | null): ArbitrageMode { return MODES.some((mode) => mode.value === value) ? value as ArbitrageMode : 'CROSS_PRICE' }
function legRole(row: ArbitrageOpportunity, side: 'a' | 'b') {
  if (row.kind === 'CROSS_PRICE') return side === 'a' ? '高价腿 · 卖出' : '低价腿 · 买入'
  return side === 'a' ? '买入 / 多头腿' : '卖出 / 空头腿'
}

export function ArbitrageScannerPage() {
  const [params, setParams] = useSearchParams()
  const [mode, setMode] = useState<ArbitrageMode>(() => parseMode(params.get('mode')))
  const [symbol, setSymbol] = useState(params.get('symbol') || '')
  const [exchange, setExchange] = useState(params.get('exchange') || 'ALL')
  const [minCapacity, setMinCapacity] = useState(Number(params.get('minCapacity') || 0))
  const [minYield, setMinYield] = useState(Number(params.get('minYield') || 0))
  const [qualifiedOnly, setQualifiedOnly] = useState(params.get('qualified') === '1')
  const [selected, setSelected] = useState<ArbitrageOpportunity | null>(null)
  const { data: matrixResponse, isError, refetch, isFetching } = useQuery({ queryKey: ['fundingRateMatrix'], queryFn: arbitrageDashboardApi.getFundingRateMatrix, refetchInterval: REFRESH_MS, retry: false })
  const snapshot = demoSnapshot
  const fundingMatrix = matrixResponse?.data || demoFundingMatrix
  const exchanges = useMemo(() => Array.from(new Set(snapshot.opportunities.flatMap((row) => [row.a.exchange, row.b.exchange]))).sort(), [snapshot])
  const rows = useMemo(() => snapshot.opportunities.filter((row) => row.kind === mode && (!symbol || row.symbol.includes(symbol.toUpperCase())) && (exchange === 'ALL' || row.a.exchange === exchange || row.b.exchange === exchange) && row.capacity >= minCapacity && (mode === 'CROSS_PRICE' || row.net >= minYield) && (!qualifiedOnly || row.status === 'QUALIFIED')).sort((a, b) => b.net * b.capacity - a.net * a.capacity), [exchange, minCapacity, minYield, mode, qualifiedOnly, snapshot.opportunities, symbol])
  const fundingRows = useMemo(() => fundingMatrix.items
    .filter((row) => (!symbol || row.symbol.includes(symbol.toUpperCase()))
      && (exchange === 'ALL' || row.ratesByExchange[exchange] != null)
      && (row.executableNotional || 0) >= minCapacity
      && (minYield <= 0 || (row.estimatedNetYield || 0) >= minYield / 100)
      && (!qualifiedOnly || isExecutableStatus(row.status)))
    .sort(compareFundingSpreadDescending),
  [exchange, fundingMatrix.items, minCapacity, minYield, qualifiedOnly, symbol])
  const displayedExchanges = mode === 'CROSS_FUNDING' ? fundingMatrix.exchanges : exchanges
  const qualified = rows.filter((row) => row.status === 'QUALIFIED')
  const executableFundingRows = fundingRows.filter((row) => isExecutableStatus(row.status))
  const best = qualified[0] || rows[0]
  const bestFunding = executableFundingRows[0] || fundingRows[0]
  const largest = rows.reduce<ArbitrageOpportunity | undefined>((current, row) => !current || row.capacity > current.capacity ? row : current, undefined)
  const largestFunding = fundingRows.reduce<FundingRateMatrixRow | undefined>((current, row) => !current || (row.executableNotional || 0) > (current.executableNotional || 0) ? row : current, undefined)
  const coverageRate = Math.round(snapshot.coverage.displayed / snapshot.coverage.total * 100)

  const updateMode = (next: ArbitrageMode) => { setMode(next); setParams((current) => { current.set('mode', next); return current }, { replace: true }) }
  const resetFilters = () => { setSymbol(''); setExchange('ALL'); setMinCapacity(0); setMinYield(0); setQualifiedOnly(false); setParams({ mode }) }
  const copyView = async () => { await navigator.clipboard?.writeText(location.href) }

  return <div className="ms-page arb-page">
    <section className="arb-hero">
      <div><p className="arb-eyebrow">MARKET INTELLIGENCE · PAPER ONLY</p><h1>发现真实可成交的套利机会</h1><p>跨所资金费按标准币对聚合：一行展示各交易所费率，最后只给出最佳方向与小时标准化差值。</p></div>
      <div className="arb-source"><span>数据来源</span><strong>{matrixResponse ? '看板读模型 · 在线' : '演示数据 · API 待读模型接入'}</strong><small>{matrixResponse?.source || 'DEMO · 等待读模型 API'}{(matrixResponse?.partial ?? true) ? ` · 部分数据：${(matrixResponse?.missingExchanges || ['BINANCE']).join('、')}` : ''}</small></div>
    </section>
    <section className="arb-metrics">
      <Metric label="可执行机会" value={mode === 'CROSS_FUNDING' ? String(executableFundingRows.length) : String(qualified.length)} detail="满足 L2、成本和同步结算条件" tone="positive" />
      <Metric label={mode === 'CROSS_FUNDING' ? '最大资金费差' : '最佳单次净收益'} value={mode === 'CROSS_FUNDING' ? formatPercent(bestFunding?.hourlyFundingSpread) : best?.netText || '—'} detail={mode === 'CROSS_FUNDING' ? '每小时标准化，避免混淆结算周期' : '下一次同步结算，已扣除成本'} tone={mode === 'CROSS_FUNDING' ? 'positive' : best ? valueTone(best.net) : 'neutral'} />
      <Metric label="最大可成交容量" value={mode === 'CROSS_FUNDING' ? formatNotional(largestFunding?.executableNotional) : largest?.capacityText || '—'} detail="共同基础币数量对应的单腿名义金额" />
      <Metric label={mode === 'CROSS_FUNDING' ? '覆盖币对' : '数据完整度'} value={mode === 'CROSS_FUNDING' ? String(fundingRows.length) : `${coverageRate}%`} detail={mode === 'CROSS_FUNDING' ? `${fundingMatrix.exchanges.length} 个交易所横向比对 · 缺失以 — 显示` : `${snapshot.coverage.displayed}/${snapshot.coverage.total} 组合可展示 · 缺 L2 ${snapshot.coverage.missingL2}`} tone="warn" />
    </section>
    <section className="arb-card">
      <div className="arb-tabs" role="tablist">{MODES.map((item) => <button key={item.value} type="button" role="tab" aria-selected={mode === item.value} className={mode === item.value ? 'active' : ''} onClick={() => updateMode(item.value)}>{item.label}<span>{item.tag}</span></button>)}</div>
      <div className="arb-filters">
        <label>⌕ <input value={symbol} onChange={(event) => setSymbol(event.target.value)} placeholder="筛选币种，例如 BTC、ETH" /></label>
        <label>交易所<select value={exchange} onChange={(event) => setExchange(event.target.value)}><option value="ALL">全部交易所</option>{displayedExchanges.map((item) => <option key={item} value={item}>{item}</option>)}</select></label>
        <label>最小容量<input type="number" min="0" value={minCapacity || ''} onChange={(event) => setMinCapacity(Number(event.target.value) || 0)} placeholder="USDT" /></label>
        {mode !== 'CROSS_PRICE' ? <label>最小净收益<input type="number" min="0" step="0.01" value={minYield || ''} onChange={(event) => setMinYield(Number(event.target.value) || 0)} placeholder="%" /></label> : null}
        <label className="arb-toggle"><input type="checkbox" checked={qualifiedOnly} onChange={(event) => setQualifiedOnly(event.target.checked)} /><span />仅可执行</label>
        <button className="arb-text-button" type="button" onClick={resetFilters}>重置</button><button className="arb-text-button" type="button" onClick={() => void refetch()}>{isFetching ? '刷新中' : '刷新'}</button><button className="arb-text-button" type="button" onClick={() => void copyView()}>复制视图</button>
      </div>
      <div className="arb-meta"><span>{MODES.find((item) => item.value === mode)?.label}扫描 · {mode === 'CROSS_FUNDING' ? `当前显示 ${fundingRows.length} 个币对，其中可执行 ${executableFundingRows.length} 个` : `当前显示 ${rows.length} 个组合，其中可执行 ${qualified.length} 个`}</span><span>{mode === 'CROSS_FUNDING' ? '默认按最佳小时标准化费率差 Δ 从大到小排序；各所列保留原始结算周期费率' : `总组合 ${snapshot.coverage.total} · 缺 L2 ${snapshot.coverage.missingL2} · 过期 ${snapshot.coverage.stale} · 不可用 ${snapshot.coverage.unavailable}`}</span></div>
      <div className="arb-table-wrap"><table><thead><tr>{(mode === 'CROSS_FUNDING' ? fundingTableHeaders(fundingMatrix.exchanges) : tableHeaders(mode)).map((item) => <th key={item}>{item}</th>)}</tr></thead><tbody>{mode === 'CROSS_FUNDING' ? (fundingRows.length ? fundingRows.map((row) => <FundingRateMatrixTableRow key={row.symbol} row={row} exchanges={fundingMatrix.exchanges} />) : <tr><td className="arb-empty" colSpan={fundingMatrix.exchanges.length + 5}>没有满足当前筛选条件的币对</td></tr>) : (rows.length ? rows.map((row) => <OpportunityRow key={row.id} row={row} mode={mode} onOpen={() => setSelected(row)} />) : <tr><td className="arb-empty" colSpan={10}>没有满足当前筛选条件的组合</td></tr>)}</tbody></table></div>
    </section>
    <section className="arb-bottom-grid"><article className="arb-card arb-health"><header><div><p className="arb-eyebrow">MARKET HEALTH</p><h2>交易所与数据质量</h2></div><span>最近 60 秒</span></header>{snapshot.health.map((item) => <div className="arb-health-row" key={item.exchange}><strong className={item.healthy ? 'good' : 'bad'}>● {item.exchange}</strong><i><b style={{ width: `${item.success}%` }} /></i><span>{item.latency ? `${item.latency}ms` : '—'}</span><small>{item.note}</small></div>)}</article>
      <article className="arb-card arb-ranking"><p className="arb-eyebrow">WHY THIS RANKING</p><h2>排序口径</h2><ol><li>跨所资金费按每个币对的最佳小时标准化费率差 Δ 降序，最大费差优先。</li><li>相同费差按币对名称排序；缺失费差固定置底。</li><li>策略状态、净收益和可执行容量继续单独展示，不把高费差直接等同于可开仓。</li><li>跨所价格仅研究可成交 VWAP 价差，不会触发 Paper 开仓。</li></ol></article></section>
    {isError ? <div className="arb-notice">读模型接口暂不可用，正在显示明确标记的 Demo 数据。</div> : null}
    {selected ? <DetailDrawer row={selected} onClose={() => setSelected(null)} /> : null}
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
function fundingTableHeaders(exchanges: string[]) { return ['币对', ...exchanges.map((exchange) => `${exchange} 资金费`), '最佳套利机会 · 费差 ↓', '可执行容量', '策略状态'] }
function compareFundingSpreadDescending(left: FundingRateMatrixRow, right: FundingRateMatrixRow) {
  if (left.hourlyFundingSpread == null) return right.hourlyFundingSpread == null ? left.symbol.localeCompare(right.symbol) : 1
  if (right.hourlyFundingSpread == null) return -1
  return right.hourlyFundingSpread - left.hourlyFundingSpread || left.symbol.localeCompare(right.symbol)
}
function tableHeaders(mode: ArbitrageMode) { return mode === 'CROSS_PRICE' ? ['币种', '高价腿 · 卖出', '低价腿 · 买入', '报价 / VWAP', '开仓价差', '费用后价差', '容量', '24h · 7d · 30d', '状态', ''] : mode === 'SINGLE_EXCHANGE' ? ['币种', '现货腿 · 买入', '永续腿 · 卖出', '资金费 / 下次结算', '当前基差', '单次净收益', '可用额度', '24h · 7d · 30d', '状态', ''] : ['币种', '低资金费腿 · 多', '高资金费腿 · 空', '资金费 / 下次结算', '毛收益 / 成本', '单次净收益', 'L2 容量', '24h · 7d · 30d', '状态', ''] }
function FundingRateMatrixTableRow({ row, exchanges }: { row: FundingRateMatrixRow; exchanges: string[] }) {
  const direction = row.longExchange && row.shortExchange ? `多 ${row.longExchange} / 空 ${row.shortExchange}` : '—'
  return <tr>
    <td><b>{row.symbol}</b><small>{row.sampledAt || '—'}</small></td>
    {exchanges.map((exchange) => <td key={exchange} className={`arb-rate-cell ${valueTone(row.ratesByExchange[exchange] || 0)}`}><b>{formatFundingRate(row.ratesByExchange[exchange], row.intervalHoursByExchange?.[exchange])}</b></td>)}
    <td className="arb-best-opportunity"><b>{direction}</b><span className="arb-best-spread"><strong>Δ {formatPercent(row.hourlyFundingSpread)}</strong><small>每小时标准化费差</small></span></td>
    <td>{formatNotional(row.executableNotional)}<small>净收益 {formatPercent(row.estimatedNetYield)}</small></td>
    <td><Status status={isExecutableStatus(row.status) ? 'QUALIFIED' : row.status === 'OBSERVING' ? 'OBSERVING' : 'REJECTED'} />{row.rejectionReason ? <small>{row.rejectionReason}</small> : null}</td>
  </tr>
}
function OpportunityRow({ row, mode, onOpen }: { row: ArbitrageOpportunity; mode: ArbitrageMode; onOpen: () => void }) { const funding = <><b>{row.a.funding} / {row.b.funding}</b><small>{row.a.interval} · {row.a.countdown} | {row.b.interval} · {row.b.countdown}</small></>; const mid = mode === 'CROSS_PRICE' ? <><td>{row.a.vwap} / {row.b.vwap}<small>VWAP · 同一基础币数量</small></td><td className="positive">{row.gross}</td><td className={valueTone(row.net)}>{row.netText}</td></> : mode === 'SINGLE_EXCHANGE' ? <><td>{funding}</td><td className={valueTone(row.net)}>{row.basis}</td><td className={valueTone(row.net)}>{row.netText}</td></> : <><td>{funding}</td><td><b className="positive">{row.gross}</b><small>成本 {row.cost}</small></td><td className={valueTone(row.net)}>{row.netText}</td></>; return <tr onClick={onOpen}><td><b>{row.symbol}</b><small>{row.annualized}</small></td><Leg leg={row.a} /><Leg leg={row.b} />{mid}<td>{row.capacityText}<small>basis {row.basis}</small></td><td className={valueTone(Number.parseFloat(row.historical[0]))}>{row.historical[0]}<small>{row.historical[1]} / {row.historical[2]}</small></td><td><Status status={row.status} /></td><td>›</td></tr> }
function Leg({ leg }: { leg: ArbitrageOpportunity['a'] }) { return <td><b>{leg.exchange}</b><small>{leg.contract}<br />Vol {leg.volume} · OI {leg.oi}</small></td> }
function Status({ status }: { status: ArbitrageOpportunity['status'] }) { const label = status === 'QUALIFIED' ? '可执行' : status === 'OBSERVING' ? '观察中' : '已拒绝'; return <span className={`arb-status ${status.toLowerCase()}`}>● {label}</span> }
function DetailDrawer({ row, onClose }: { row: ArbitrageOpportunity; onClose: () => void }) { return <><button className="arb-scrim" aria-label="关闭详情" onClick={onClose} /><aside className="arb-drawer"><header><div><p className="arb-eyebrow">{row.kind.split('_').join(' · ')}</p><h2>{row.symbol} · {row.a.exchange} ↔ {row.b.exchange}</h2></div><button onClick={onClose}>×</button></header><div className="arb-drawer-stats"><Metric label="单次净收益" value={row.netText} detail="下一次同步结算" tone={valueTone(row.net)} /><Metric label="可成交容量" value={row.capacityText} detail="共同基础币数量" /><Metric label="可成交 basis" value={row.basis} detail="L2 VWAP" /><Metric label="稳定窗口" value={row.stable} detail="当前 / 所需" /></div><section><h3>收益拆解</h3><p>毛资金费 / 价格差 <b className="positive">{row.gross}</b></p><p>开平手续费、滑点与 buffer <b className="negative">-{row.cost}</b></p><p className="total">下一次同步结算净收益 <b>{row.netText}</b></p></section><section><h3>相同基础币数量下的 L2 成交</h3><div className="arb-depths"><Depth title={`${row.a.exchange} · ${legRole(row, 'a')}`} levels={row.aDepth} tone="positive" /><Depth title={`${row.b.exchange} · ${legRole(row, 'b')}`} levels={row.bDepth} tone="negative" /></div></section><section><h3>历史实际资金费</h3><div className="arb-history"><span>24 小时<b>{row.historical[0]}</b></span><span>7 天<b>{row.historical[1]}</b></span><span>30 天<b>{row.historical[2]}</b></span><span>年化等效<b>{row.annualized}</b></span></div></section>{row.reason ? <section><h3>当前判断</h3><div className="arb-reason">{row.reason}</div></section> : null}</aside></> }
function Depth({ title, levels, tone }: { title: string; levels: Array<[string, string]>; tone: string }) { return <article className="arb-depth"><h4>{title}</h4>{levels.length ? levels.map(([price, size]) => <p key={price}><b className={tone}>{price}</b><span>{size}</span></p>) : <small>本组合未保留完整 L2</small>}</article> }
