import { useDeferredValue, useEffect, useState } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { walletApi } from '@/api'
import { formatAmount, formatMoney, formatNumber, formatPercent, truncateText } from '@/utils/format'
import { buildTableOptions, TABLE_PAGE_SIZE } from '@/utils/table'
import { TableToolbar } from './TableToolbar'
import { TablePagination } from './TablePagination'

const TRADE_CURSOR_PAGE_SIZE = 50

interface TradesTableProps {
  mode?: 'PAPER' | 'LIVE'
  pnl?: 'ALL' | 'PROFIT' | 'LOSS' | 'ZERO'
  settlement?: 'ALL' | 'SETTLED' | 'UNSETTLED' | 'EARLY_EXIT'
}

function parseNextCursor(nextCursor?: string | null): number | undefined {
  const cursor = Number(nextCursor)
  return Number.isFinite(cursor) && cursor > 0 ? cursor : undefined
}

function getPnlBadgeClass(value?: number): string {
  const pnl = Number(value || 0)
  if (pnl > 0) return 'badge accent'
  if (pnl < 0) return 'badge danger'
  return 'badge'
}

function formatIsoSecond(value?: string | number | null): string {
  if (value == null || value === '') return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return date.toISOString().replace(/\.\d{3}Z$/, 'Z')
}

function formatMarketEnd(row: { marketEndAt?: number | null; marketEndAtBjt?: string | null }): string {
  if (row.marketEndAtBjt) return row.marketEndAtBjt
  if (row.marketEndAt) return formatIsoSecond(row.marketEndAt)
  return '-'
}

function buildPolymarketMarketUrl(row: {
  marketId?: string | null
  marketSlug?: string | null
  marketUrl?: string | null
  externalMarketUrl?: string | null
  internalMarketUrl?: string | null
  question?: string | null
}): string {
  if (row.externalMarketUrl || row.marketUrl || row.internalMarketUrl) {
    return row.externalMarketUrl || row.marketUrl || row.internalMarketUrl || ''
  }
  const slug = (row.marketSlug || '').trim()
  if (!slug) {
    return row.marketId ? `/dashboard/polymarket/markets/${encodeURIComponent(row.marketId.trim())}` : ''
  }
  const sportsUrl = buildPolymarketSportsUrl(slug)
  if (sportsUrl) {
    return sportsUrl
  }
  return `https://polymarket.com/event/${slug}`
}

function buildPolymarketSportsUrl(slug: string): string {
  const sportsMarket = /^((?:nba|nfl|mlb|nhl|mls|epl|ucl|laliga|seriea|bundesliga|ligue1|ncaab|ncaaf|wnba|afl|f1|ufc|boxing|tennis|golf|cricket|nascar|pga)-[a-z0-9]+-[a-z0-9]+-\d{4}-\d{2}-\d{2})(?:-.+)?$/i.exec(slug)
  const eventSlug = sportsMarket?.[1]
  const league = sportsMarket?.[1]?.split('-')[0]
  if (!eventSlug || !league) {
    return ''
  }
  return `https://polymarket.com/sports/${league.toLowerCase()}/${eventSlug.toLowerCase()}`
}

export function TradesTable({ mode, pnl = 'ALL', settlement = 'ALL' }: TradesTableProps) {
  const [page, setPage] = useState(1)
  const [keyword, setKeyword] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const deferredKeyword = useDeferredValue(keyword.trim())

  const {
    data,
    isLoading,
    isFetchingNextPage,
    hasNextPage,
    fetchNextPage,
  } = useInfiniteQuery({
    queryKey: ['tradeOrdersPage', mode, pnl, settlement, statusFilter, deferredKeyword],
    initialPageParam: null as number | null,
    queryFn: ({ pageParam }) => walletApi.getTradeOrdersPage(TRADE_CURSOR_PAGE_SIZE, pageParam, mode, {
      pnl,
      settlement,
      status: statusFilter,
      q: deferredKeyword,
    }),
    getNextPageParam: (lastPage) => parseNextCursor(lastPage.nextCursor),
  })

  const trades = data?.pages.flatMap((tradesPage) => tradesPage.items) ?? []
  const loadedPages = Math.max(1, Math.ceil(trades.length / TABLE_PAGE_SIZE))
  const totalPages = hasNextPage ? Math.max(loadedPages + 1, page) : loadedPages
  const visibleTrades = trades.slice((page - 1) * TABLE_PAGE_SIZE, page * TABLE_PAGE_SIZE)
  const requiredItems = page * TABLE_PAGE_SIZE
  const isPageLoading = isFetchingNextPage && trades.length < (page - 1) * TABLE_PAGE_SIZE + 1
  const hasActiveFilters = Boolean(keyword || statusFilter)

  useEffect(() => {
    setPage(1)
  }, [deferredKeyword, statusFilter, mode, pnl, settlement])

  useEffect(() => {
    setPage((currentPage) => Math.min(currentPage, totalPages))
  }, [totalPages])

  useEffect(() => {
    if (!hasNextPage || isFetchingNextPage || trades.length >= requiredItems) return
    void fetchNextPage()
  }, [fetchNextPage, trades.length, hasNextPage, isFetchingNextPage, requiredItems])

  if (isLoading) return <div className="loading">订单记录加载中...</div>

  return (
    <>
      <TableToolbar
        keyword={keyword}
        onKeywordChange={setKeyword}
        keywordPlaceholder="搜索主单 / 市场 / 状态"
        filteredCount={trades.length}
        totalCount={trades.length}
        countLabel="已加载"
        hasActiveFilters={hasActiveFilters}
        onReset={() => {
          setKeyword('')
          setStatusFilter('')
        }}
        filters={[
          {
            value: statusFilter,
            onChange: setStatusFilter,
            options: buildTableOptions(trades.map((trade) => trade.status), '全部状态'),
            ariaLabel: '交易状态筛选',
          },
        ]}
      />

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>主单ID</th>
              <th>市场</th>
              <th>Outcome</th>
              <th>开仓</th>
              <th>卖出 / 剩余</th>
              <th>收益</th>
              <th>ROI</th>
              <th>状态</th>
              <th>预计结算</th>
              <th>时间</th>
            </tr>
          </thead>
          <tbody>
            {visibleTrades.length === 0 ? (
              <tr>
                <td colSpan={10} className="empty-state">
                  {isPageLoading ? '订单记录加载中...' : '当前筛选条件下暂无订单记录'}
                </td>
              </tr>
            ) : (
              visibleTrades.map((trade) => {
                const marketLabel = trade.question || trade.marketId || '-'
                const marketUrl = buildPolymarketMarketUrl(trade)
                const entryOrders = (trade.entryOrderIds || []).join(', ')
                const exitOrders = (trade.exitOrderIds || []).join(', ')
                return (
                  <tr key={trade.id}>
                    <td title={`entry: ${entryOrders || '-'} / exit: ${exitOrders || '-'}`}>
                      {trade.id}
                      <div className="table-subtext">position #{trade.positionId}</div>
                    </td>
                    <td className="market-cell" title={marketLabel}>
                      {marketUrl ? (
                        <a
                          className="market-link"
                          href={marketUrl}
                          target={marketUrl.startsWith('http') ? '_blank' : undefined}
                          rel={marketUrl.startsWith('http') ? 'noreferrer' : undefined}
                        >
                          {truncateText(marketLabel, 34)}
                        </a>
                      ) : (
                        truncateText(marketLabel, 34)
                      )}
                    </td>
                    <td>{trade.outcome}</td>
                    <td>
                      <div>{formatNumber(trade.entryQuantity, 4)} @ {formatNumber(trade.entryAvgPrice, 4)}</div>
                      <span className="badge">成本 {formatAmount(trade.entryCostUsdc)}</span>
                    </td>
                    <td>
                      <div>{formatNumber(trade.exitQuantity, 4)} @ {formatNumber(trade.exitAvgPrice, 4)}</div>
                      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px', marginTop: '6px' }}>
                        <span className="badge">剩余 {formatNumber(trade.remainingQuantity, 4)}</span>
                        {trade.settlementProceedsUsdc ? <span className="badge">结算 {formatAmount(trade.settlementProceedsUsdc)}</span> : null}
                      </div>
                    </td>
                    <td>
                      <div className={getPnlBadgeClass(trade.totalPnlUsdc)}>
                        总 {formatMoney(trade.totalPnlUsdc)}
                      </div>
                      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px', marginTop: '6px' }}>
                        <span className={getPnlBadgeClass(trade.totalRealizedPnlUsdc)}>已实现 {formatMoney(trade.totalRealizedPnlUsdc)}</span>
                        <span className={getPnlBadgeClass(trade.unrealizedPnlUsdc)}>浮动 {formatMoney(trade.unrealizedPnlUsdc)}</span>
                        {trade.settlementPnlUsdc ? <span className={getPnlBadgeClass(trade.settlementPnlUsdc)}>结算 {formatMoney(trade.settlementPnlUsdc)}</span> : null}
                      </div>
                    </td>
                    <td>
                      <div>{formatPercent(trade.roiRate)}</div>
                      <span className="badge">基数 {formatAmount(trade.entryCostUsdc)}</span>
                    </td>
                    <td><span className="badge">{trade.status}</span></td>
                    <td title={formatMarketEnd(trade)}>{formatMarketEnd(trade)}</td>
                    <td>
                      <div>{formatIsoSecond(trade.openedAt)}</div>
                      {trade.lastExitAt ? <span className="badge">卖 {formatIsoSecond(trade.lastExitAt)}</span> : null}
                      {trade.settledAt ? <span className="badge">结 {formatIsoSecond(trade.settledAt)}</span> : null}
                    </td>
                  </tr>
                )
              })
            )}
          </tbody>
        </table>
      </div>

      <TablePagination
        page={page}
        pageSize={TABLE_PAGE_SIZE}
        totalItems={trades.length}
        onPageChange={setPage}
        hasMore={Boolean(hasNextPage)}
      />
    </>
  )
}
