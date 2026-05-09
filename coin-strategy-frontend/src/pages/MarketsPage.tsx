import { startTransition } from 'react'
import { useQuery } from '@tanstack/react-query'
import ReactECharts from 'echarts-for-react'
import type { EChartsOption } from 'echarts'
import { useSearchParams } from 'react-router-dom'
import { reportsApi } from '@/api'
import { TablePagination } from '@/components/tables/TablePagination'
import type { SmartWalletMarketMonitorItem, SmartWalletMarketMonitorTrendPoint } from '@/types/smartwallet'
import { compactAddress, formatNumber, formatPercent, truncateText } from '@/utils/format'

const LOOKBACK_OPTIONS = [7, 14, 30]
const LIMIT_OPTIONS = [24, 40, 60]
const PAGE_SIZE_OPTIONS = [10, 20, 40]
const CATEGORY_OPTIONS = [
  'POLITICS',
  'WEATHER',
  'SPORTS',
  'CRYPTO',
  'MACRO',
  'BUSINESS',
  'ENTERTAINMENT',
  'CURRENT_EVENTS',
  'OTHER',
] as const

function buildTrendOption(data: SmartWalletMarketMonitorTrendPoint[]): EChartsOption {
  return {
    tooltip: { trigger: 'axis' },
    legend: {
      top: 4,
      textStyle: { color: '#7ef7a5', fontSize: 11 },
    },
    grid: { left: 40, right: 30, top: 44, bottom: 28 },
    xAxis: {
      type: 'category',
      data: data.map((item) => item.date.slice(5)),
      axisLine: { lineStyle: { color: 'rgba(0,255,101,0.18)' } },
      axisLabel: { color: '#8ba59a', fontSize: 11 },
    },
    yAxis: [
      {
        type: 'value',
        axisLine: { lineStyle: { color: 'rgba(0,255,101,0.18)' } },
        splitLine: { lineStyle: { color: 'rgba(0,255,101,0.08)' } },
        axisLabel: { color: '#8ba59a', fontSize: 11 },
      },
      {
        type: 'value',
        axisLine: { lineStyle: { color: 'rgba(255,176,0,0.18)' } },
        splitLine: { show: false },
        axisLabel: { color: '#d8b65a', fontSize: 11 },
      },
    ],
    series: [
      {
        name: '监控市场',
        type: 'line',
        smooth: true,
        symbolSize: 8,
        data: data.map((item) => item.monitoredMarketCount),
        lineStyle: { width: 3, color: '#2ef2a1' },
        itemStyle: { color: '#2ef2a1' },
        areaStyle: { color: 'rgba(46,242,161,0.12)' },
      },
      {
        name: '有效市场',
        type: 'line',
        smooth: true,
        symbolSize: 7,
        data: data.map((item) => item.effectiveMarketCount),
        lineStyle: { width: 2, color: '#8ef9ff' },
        itemStyle: { color: '#8ef9ff' },
      },
      {
        name: '高集中',
        type: 'line',
        smooth: true,
        symbolSize: 7,
        data: data.map((item) => item.concentratedMarketCount),
        lineStyle: { width: 2, color: '#ffb020' },
        itemStyle: { color: '#ffb020' },
      },
      {
        name: '极端集中',
        type: 'line',
        smooth: true,
        symbolSize: 7,
        data: data.map((item) => item.extremeConcentratedMarketCount),
        lineStyle: { width: 2, color: '#ff5d5d' },
        itemStyle: { color: '#ff5d5d' },
      },
      {
        name: '信号数',
        type: 'bar',
        yAxisIndex: 1,
        data: data.map((item) => item.signalCount),
        itemStyle: {
          color: 'rgba(0,255,101,0.22)',
          borderColor: 'rgba(0,255,101,0.55)',
          borderWidth: 1,
        },
        barMaxWidth: 22,
      },
    ],
  }
}

function buildRankingOption(items: SmartWalletMarketMonitorItem[]): EChartsOption {
  const topItems = [...items]
    .sort((left, right) => (right.top3Pct || 0) - (left.top3Pct || 0))
    .slice(0, 10)
    .reverse()

  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: {
      top: 4,
      textStyle: { color: '#7ef7a5', fontSize: 11 },
    },
    grid: { left: 150, right: 40, top: 44, bottom: 24 },
    xAxis: [
      {
        type: 'value',
        min: 0,
        max: 100,
        axisLabel: { color: '#8ba59a', formatter: '{value}%' },
        splitLine: { lineStyle: { color: 'rgba(0,255,101,0.08)' } },
      },
      {
        type: 'value',
        min: 0,
        max: 10000,
        axisLabel: { color: '#d8b65a' },
        splitLine: { show: false },
      },
    ],
    yAxis: {
      type: 'category',
      data: topItems.map((item) => truncateText(item.question || item.marketId, 22)),
      axisLabel: { color: '#b7c8c0', fontSize: 11 },
      axisLine: { lineStyle: { color: 'rgba(0,255,101,0.18)' } },
    },
    series: [
      {
        name: 'Top3 控盘',
        type: 'bar',
        data: topItems.map((item) => Number(item.top3Pct || 0) * 100),
        barMaxWidth: 16,
        itemStyle: {
          color: (params) => Number(params.value) >= 90 ? '#ff5d5d' : Number(params.value) >= 70 ? '#ffb020' : '#2ef2a1',
          borderRadius: [0, 8, 8, 0],
        },
      },
      {
        name: 'HHI',
        type: 'line',
        xAxisIndex: 1,
        data: topItems.map((item) => item.hhi || 0),
        smooth: true,
        symbolSize: 7,
        lineStyle: { color: '#8ef9ff', width: 2 },
        itemStyle: { color: '#8ef9ff' },
      },
    ],
  }
}

function buildScatterOption(items: SmartWalletMarketMonitorItem[]): EChartsOption {
  const seriesData = items.slice(0, 36).map((item) => {
    const weight = 10 + item.signalCount * 1.8 + item.openPositionCount * 4 + item.pendingTaskCount * 3
    const color = item.extremelyConcentrated ? '#ff5d5d' : item.highlyConcentrated ? '#ffb020' : item.effectiveMarket ? '#2ef2a1' : '#7e8993'
    return {
      name: truncateText(item.question || item.marketId, 28),
      value: [Number(item.top3Pct || 0) * 100, item.hhi || 0, weight, item.signalCount || 0, item.openPositionCount || 0],
      itemStyle: { color },
      marketId: item.marketId,
      question: item.question,
    }
  })

  return {
    tooltip: {
      formatter: (params: any) => {
        const data = params.data
        return [
          `<strong>${data.name}</strong>`,
          `Top3 控盘: ${Number(data.value[0]).toFixed(2)}%`,
          `HHI: ${data.value[1]}`,
          `信号数: ${data.value[3]}`,
          `持仓市场数: ${data.value[4]}`,
        ].join('<br/>')
      },
    },
    grid: { left: 56, right: 24, top: 20, bottom: 36 },
    xAxis: {
      type: 'value',
      min: 0,
      max: 100,
      name: 'Top3 控盘%',
      nameTextStyle: { color: '#8ba59a' },
      axisLabel: { color: '#8ba59a', formatter: '{value}%' },
      splitLine: { lineStyle: { color: 'rgba(0,255,101,0.08)' } },
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 10000,
      name: 'HHI',
      nameTextStyle: { color: '#8ba59a' },
      axisLabel: { color: '#8ba59a' },
      splitLine: { lineStyle: { color: 'rgba(0,255,101,0.08)' } },
    },
    series: [
      {
        type: 'scatter',
        data: seriesData,
        symbolSize: (value: number[]) => Math.max(12, Math.min(44, value[2])),
        emphasis: {
          scale: 1.15,
        },
      },
    ],
  }
}

function riskClass(item: SmartWalletMarketMonitorItem) {
  if (item.extremelyConcentrated) return 'market-risk-badge extreme'
  if (item.highlyConcentrated) return 'market-risk-badge high'
  if (item.effectiveMarket) return 'market-risk-badge healthy'
  return 'market-risk-badge muted'
}

function categoryBadgeClass(category: string | null | undefined) {
  if (category === 'POLITICS' || category === 'CURRENT_EVENTS') return 'badge accent'
  if (category === 'WEATHER' || category === 'SPORTS') return 'badge positive'
  if (category === 'CRYPTO' || category === 'BUSINESS' || category === 'MACRO') return 'badge warn'
  return 'badge'
}

function formatSyncTime(value: string | null | undefined) {
  if (!value) return '未同步'
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function queryErrorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

function buildMonitorMarketUrl(item: SmartWalletMarketMonitorItem): string {
  const slug = (item.marketSlug || '').trim()
  if (slug) {
    return `https://polymarket.com/event/${slug}`
  }
  return item.marketId ? `/dashboard/polymarket/markets/${encodeURIComponent(item.marketId.trim())}` : ''
}

export function MarketsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const lookbackDays = LOOKBACK_OPTIONS.includes(Number(searchParams.get('lookbackDays')))
    ? Number(searchParams.get('lookbackDays'))
    : 14
  const limit = LIMIT_OPTIONS.includes(Number(searchParams.get('limit')))
    ? Number(searchParams.get('limit'))
    : 40
  const trendDays = Math.min(lookbackDays, lookbackDays >= 30 ? 14 : 7)
  const page = Math.max(1, Number(searchParams.get('page')) || 1)
  const pageSize = PAGE_SIZE_OPTIONS.includes(Number(searchParams.get('pageSize')))
    ? Number(searchParams.get('pageSize'))
    : 10
  const keyword = searchParams.get('keyword')?.trim() || ''
  const category = searchParams.get('category')?.trim() || ''
  const concentrationBand = searchParams.get('concentrationBand')?.trim() || ''
  const hasPositionParam = searchParams.get('hasPosition')?.trim() || ''
  const hasPosition =
    hasPositionParam === 'true'
      ? true
      : hasPositionParam === 'false'
        ? false
        : null

  const updateParams = (
    updates: Record<string, string | number | boolean | null | undefined>,
    options: { resetPage?: boolean } = {}
  ) => {
    startTransition(() => {
      const nextParams = new URLSearchParams(searchParams)
      Object.entries(updates).forEach(([key, value]) => {
        if (value === null || value === undefined || value === '') {
          nextParams.delete(key)
        } else {
          nextParams.set(key, String(value))
        }
      })
      if (options.resetPage) {
        nextParams.set('page', '1')
      }
      nextParams.set('strategy', 'POLYMARKET_COPY')
      setSearchParams(nextParams)
    })
  }

  const {
    data,
    isLoading,
    error: summaryError,
    refetch: refetchSummary,
  } = useQuery({
    queryKey: ['marketMonitoringSummary', limit, lookbackDays, trendDays],
    queryFn: () => reportsApi.getMarketMonitoringSummary(limit, lookbackDays, trendDays),
  })

  const canLoadTable = Boolean(data) || Boolean(summaryError)

  const {
    data: marketTablePage,
    isLoading: tableLoading,
    error: tableError,
    refetch: refetchTable,
  } = useQuery({
    queryKey: [
      'marketMonitoringTablePage',
      lookbackDays,
      page,
      pageSize,
      keyword,
      category,
      concentrationBand,
      hasPosition,
    ],
    queryFn: () =>
      reportsApi.getMarketMonitoringTablePage({
        page,
        pageSize,
        lookbackDays,
        keyword: keyword || undefined,
        category: category || undefined,
        concentrationBand: concentrationBand || undefined,
        hasPosition,
      }),
    enabled: canLoadTable,
  })

  const metricCards = data ? [
    {
      label: '监控市场',
      value: formatNumber(data.monitoredMarketCount, 0),
      hint: `${formatPercent(data.concentrationCoverageRate)} 已完成集中度采样`,
      tone: 'neutral',
    },
    {
      label: '有效市场',
      value: formatNumber(data.effectiveMarketCount, 0),
      hint: '活跃且未触发高集中风险',
      tone: 'positive',
    },
    {
      label: '控盘市场',
      value: formatNumber(data.concentratedMarketCount, 0),
      hint: `其中极端集中 ${formatNumber(data.extremeConcentratedMarketCount, 0)}`,
      tone: 'negative',
    },
    {
      label: '有信号市场',
      value: formatNumber(data.signalDrivenMarketCount, 0),
      hint: `${lookbackDays}d 内有源地址动作`,
      tone: 'neutral',
    },
    {
      label: '持仓市场',
      value: formatNumber(data.openPositionMarketCount, 0),
      hint: '仍有跟单仓位暴露',
      tone: 'warn',
    },
    {
      label: '待执行市场',
      value: formatNumber(data.pendingTaskMarketCount, 0),
      hint: '存在待执行 follow task',
      tone: 'warn',
    },
  ] : []

  return (
    <>
      <section className="hero">
        <div>
          <h1>市场监控</h1>
        </div>
        <div className="market-console-controls">
          <div>
            <p className="eyebrow">回看</p>
            <div className="segmented-control">
              {LOOKBACK_OPTIONS.map((option) => (
                <button
                  key={option}
                  type="button"
                  className={option === lookbackDays ? 'active' : ''}
                  onClick={() => updateParams({ lookbackDays: option }, { resetPage: true })}
                >
                  {option}d
                </button>
              ))}
            </div>
          </div>
          <div>
            <p className="eyebrow">样本</p>
            <div className="segmented-control">
              {LIMIT_OPTIONS.map((option) => (
                <button
                  key={option}
                  type="button"
                  className={option === limit ? 'active' : ''}
                  onClick={() => updateParams({ limit: option })}
                >
                  {option}
                </button>
              ))}
            </div>
          </div>
        </div>
      </section>

      <section className="panel">
        <div className="section-head">
          <h2>市场总览</h2>
        </div>
        {isLoading ? (
          <div className="loading">加载市场监控中...</div>
        ) : summaryError || !data ? (
          <div className="query-state">
            <strong>市场总览暂时不可用</strong>
            <div className="table-subtext">{queryErrorMessage(summaryError, '市场监控摘要加载失败')}</div>
            <button type="button" className="btn btn-secondary" onClick={() => void refetchSummary()}>
              重试摘要
            </button>
          </div>
        ) : (
          <div className="metric-grid">
            {metricCards.map((item) => (
              <article key={item.label} className="metric-card">
                <div className="label">{item.label}</div>
                <div className={`value ${item.tone}`}>{item.value}</div>
                <div className="hint">{item.hint}</div>
              </article>
            ))}
          </div>
        )}
      </section>

      {data ? (
        <>
          <div className="split-grid">
            <section className="panel">
              <div className="section-head">
                <h2>市场趋势</h2>
              </div>
              <ReactECharts option={buildTrendOption(data.trend)} style={{ height: 340, width: '100%' }} />
            </section>
            <section className="panel">
              <div className="section-head">
                <h2>控盘排行</h2>
              </div>
              <ReactECharts option={buildRankingOption(data.items)} style={{ height: 340, width: '100%' }} />
            </section>
          </div>

          <section className="panel">
            <div className="section-head">
              <h2>控盘分布图</h2>
            </div>
            <ReactECharts option={buildScatterOption(data.items)} style={{ height: 360, width: '100%' }} />
          </section>
        </>
      ) : null}

      <section className="panel">
        <div className="section-head">
          <h2>活跃市场</h2>
        </div>

        <div className="table-toolbar">
          <div className="filter-bar">
            <input
              className="table-input"
              value={keyword}
              placeholder="搜索市场标题 / marketId / 持仓方向"
              onChange={(event) => updateParams({ keyword: event.target.value }, { resetPage: true })}
            />
            <select
              value={category}
              onChange={(event) => updateParams({ category: event.target.value }, { resetPage: true })}
            >
              <option value="">全部分类</option>
              {CATEGORY_OPTIONS.map((option) => (
                <option key={option} value={option}>{option}</option>
              ))}
            </select>
            <select
              value={concentrationBand}
              onChange={(event) => updateParams({ concentrationBand: event.target.value }, { resetPage: true })}
            >
              <option value="">全部集中度</option>
              <option value="HEALTHY">HEALTHY 健康</option>
              <option value="HIGH">HIGH 高集中</option>
              <option value="EXTREME">EXTREME 极端集中</option>
              <option value="UNKNOWN">UNKNOWN 未知</option>
            </select>
            <select
              value={hasPositionParam}
              onChange={(event) => updateParams({ hasPosition: event.target.value }, { resetPage: true })}
            >
              <option value="">全部持仓情况</option>
              <option value="true">仅有持仓</option>
              <option value="false">仅无持仓</option>
            </select>
            <select
              value={String(pageSize)}
              onChange={(event) => updateParams({ pageSize: Number(event.target.value) }, { resetPage: true })}
            >
              {PAGE_SIZE_OPTIONS.map((option) => (
                <option key={option} value={option}>{`每页 ${option} 条`}</option>
              ))}
            </select>
          </div>

          <div className="table-toolbar-meta">
            {marketTablePage
              ? `筛选后 ${formatNumber(marketTablePage.totalItems, 0)} 条`
              : canLoadTable ? '表格筛选中...' : '等待市场总览加载完成...'}
          </div>
        </div>

        {!canLoadTable || tableLoading ? (
          <div className="loading">加载市场状态表中...</div>
        ) : tableError || !marketTablePage ? (
          <div className="query-state">
            <strong>市场状态表暂时不可用</strong>
            <div className="table-subtext">{queryErrorMessage(tableError, '市场状态表加载失败')}</div>
            <button type="button" className="btn btn-secondary" onClick={() => void refetchTable()}>
              重试表格
            </button>
          </div>
        ) : (
          <>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>市场</th>
                    <th>分类</th>
                    <th>集中度</th>
                    <th>Top1</th>
                    <th>Top3</th>
                    <th>Top5</th>
                    <th>HHI</th>
                    <th>持仓人</th>
                    <th>信号</th>
                    <th>地址</th>
                    <th>持仓</th>
                    <th>市场比例</th>
                    <th>最新信号</th>
                    <th>结束时间</th>
                  </tr>
                </thead>
                <tbody>
                  {marketTablePage.items.length === 0 ? (
                    <tr>
                      <td colSpan={14} className="empty-state">当前筛选条件下暂无市场监控数据</td>
                    </tr>
                  ) : marketTablePage.items.map((item) => (
                    <tr key={item.marketId}>
                      <td className="market-cell">
                        {(() => {
                          const marketUrl = buildMonitorMarketUrl(item)
                          const marketLabel = truncateText(item.question || item.marketId, 48)
                          return marketUrl ? (
                            <a
                              className="market-link"
                              href={marketUrl}
                              target={marketUrl.startsWith('http') ? '_blank' : undefined}
                              rel={marketUrl.startsWith('http') ? 'noreferrer' : undefined}
                            >
                              {marketLabel}
                            </a>
                          ) : (
                            <div className="market-cell-title">{marketLabel}</div>
                          )
                        })()}
                        <div className="market-cell-sub mono">{item.marketId}</div>
                        <div className="market-cell-sub">
                          生命周期: {item.marketLifecycle || item.marketState}
                        </div>
                        <div className="market-cell-sub">
                          元数据: {formatSyncTime(item.lastMetadataSyncAt)}
                          {item.metadataStale ? ' · 已过期' : ' · 最新'}
                        </div>
                        {item.topHolderAddresses?.length ? (
                          <div className="market-chip-row">
                            {item.topHolderAddresses.slice(0, 3).map((address) => (
                              <span key={address} className="market-holder-chip">{compactAddress(address)}</span>
                            ))}
                          </div>
                        ) : null}
                      </td>
                      <td>
                        <span className={categoryBadgeClass(item.normalizedCategory)}>
                          {item.normalizedCategory || 'OTHER'}
                        </span>
                        <div className="market-cell-sub">{item.sourceCategory || '-'}</div>
                      </td>
                      <td>
                        <span className={riskClass(item)}>{item.concentrationBand}</span>
                        <div className="market-cell-sub">
                          {item.lastConcentrationSnapshotAt
                            ? `快照 ${formatSyncTime(item.lastConcentrationSnapshotAt)}${item.concentrationStale ? ' · 已过期' : ''}`
                            : '快照未采样'}
                        </div>
                      </td>
                      <td className="mono">{formatPercent(item.top1Pct)}</td>
                      <td className="mono">{formatPercent(item.top3Pct)}</td>
                      <td className="mono">{formatPercent(item.top5Pct)}</td>
                      <td className="mono">{formatNumber(item.hhi, 0)}</td>
                      <td>{formatNumber(item.observedHolders, 0)}</td>
                      <td>{formatNumber(item.signalCount, 0)}</td>
                      <td>{formatNumber(item.uniqueWalletCount, 0)}</td>
                      <td>{formatNumber(item.openPositionCount, 0)}</td>
                      <td className="mono">{item.outcomePrices || '-'}</td>
                      <td>{item.lastSignalAtBjt || '-'}</td>
                      <td>{item.marketEndAtBjt || '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <TablePagination
              page={marketTablePage.page}
              pageSize={marketTablePage.pageSize}
              totalItems={marketTablePage.totalItems}
              onPageChange={(nextPage) => updateParams({ page: nextPage })}
            />
          </>
        )}
      </section>
    </>
  )
}
