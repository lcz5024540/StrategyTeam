import { useDeferredValue, useEffect, useState } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { walletApi } from '@/api'
import { compactAddress, formatAmount, formatMoney, truncateText } from '@/utils/format'
import { buildTableOptions, TABLE_PAGE_SIZE } from '@/utils/table'
import { TableToolbar } from './TableToolbar'
import { TablePagination } from './TablePagination'

const ORDER_CURSOR_PAGE_SIZE = 50
type MarketLifecycleFilter = 'ALL' | 'ACTIVE' | 'ENDED'

const EXIT_REASON_LABELS: Record<string, string> = {
  TAKE_PROFIT_AUTO_EXIT: '自动止盈',
  STOP_LOSS_AUTO_EXIT: '自动止损',
  CAPITAL_RECYCLE_AUTO_EXIT: '资金回收',
  TREND_STOP_LOSS_AUTO_EXIT: '趋势止损',
  TIME_WINDOW_AUTO_EXIT: '时间窗口退出',
}

function getExitReasonLabel(exitReason?: string): string {
  if (!exitReason) {
    return '提前卖出'
  }
  return EXIT_REASON_LABELS[exitReason] || exitReason
}

function getOperationTypeLabel(order: {
  side?: string | null
  orderType?: string | null
  earlyExit?: boolean
  exitReason?: string | null
}): string {
  const side = (order.side || '').toUpperCase()
  const orderType = (order.orderType || '').toUpperCase()
  if (side === 'BUY') {
    if (orderType === 'EXIT') return '买入回补'
    return '买入开仓'
  }
  if (side === 'SELL') {
    if (order.earlyExit || order.exitReason || orderType === 'EXIT') return '卖出退出'
    return '卖出'
  }
  return orderType || side || '-'
}

function getOperationBadgeClass(order: {
  side?: string | null
  orderType?: string | null
  earlyExit?: boolean
  exitReason?: string | null
}): string {
  const side = (order.side || '').toUpperCase()
  if (side === 'BUY') return 'badge accent'
  if (side === 'SELL') return 'badge warn'
  return 'badge'
}

function getPnlBadgeClass(value?: number): string {
  const pnl = Number(value || 0)
  if (pnl > 0) return 'badge accent'
  if (pnl < 0) return 'badge danger'
  return 'badge'
}

function getExitReasonBadgeClass(exitReason: string, realizedPnlUsdc?: number): string {
  if (exitReason === 'STOP_LOSS_AUTO_EXIT' || exitReason === 'TREND_STOP_LOSS_AUTO_EXIT') {
    return 'danger'
  }
  if (exitReason === 'CAPITAL_RECYCLE_AUTO_EXIT') {
    const pnl = Number(realizedPnlUsdc || 0)
    if (pnl > 0) return 'accent'
    if (pnl < 0) return 'danger'
    return 'info'
  }
  return 'warn'
}

function shouldShowRealizedPnl(order: {
  earlyExit?: boolean
  realizedPnlUsdc?: number
  status: string
}): boolean {
  const pnl = Number(order.realizedPnlUsdc || 0)
  if (order.earlyExit) {
    return true
  }
  return pnl !== 0 || order.status === 'SETTLED_WIN' || order.status === 'SETTLED_LOSS'
}

function formatFeeBreakdown(order: {
  feeUsdc?: number
  tradingFeeUsdc?: number
  gasFeeUsdc?: number
  feePol?: number
}): string {
  const total = Number(order.feeUsdc || 0)
  const trading = Number(order.tradingFeeUsdc || 0)
  const gas = Number(order.gasFeeUsdc || 0)
  const feePol = Number(order.feePol || 0)
  const parts = [`总 ${formatMoney(total)}`]
  if (trading) parts.push(`交易 ${formatMoney(trading)}`)
  if (gas) parts.push(`Gas ${formatMoney(gas)}`)
  if (feePol) parts.push(`POL ${formatAmount(feePol)}`)
  return parts.join(' / ')
}

function isActiveOrderMarket(order: {
  activeMarket?: boolean | null
  marketLifecycle?: string | null
  closed?: boolean | null
  acceptingOrders?: boolean | null
  marketEndAt?: number | null
}): boolean {
  if (order.activeMarket != null) {
    return Boolean(order.activeMarket)
  }
  const lifecycle = (order.marketLifecycle || '').toUpperCase()
  if (lifecycle && lifecycle !== 'ACTIVE') {
    return false
  }
  if (order.closed || order.acceptingOrders === false) {
    return false
  }
  return !order.marketEndAt || order.marketEndAt <= 0 || order.marketEndAt > Date.now()
}

function getLifecycleLabel(order: {
  activeMarket?: boolean | null
  marketLifecycle?: string | null
}): string {
  if (isActiveOrderMarket(order)) {
    return '活跃市场'
  }
  const lifecycle = (order.marketLifecycle || '').toUpperCase()
  if (lifecycle === 'RESOLVED') {
    return '已结算市场'
  }
  return '已结束市场'
}

function getCategoryLabel(value?: string | null): string {
  if (!value) return 'OTHER'
  return value.replace(/_/g, ' ')
}

function formatIsoSecond(value?: string | number | null): string {
  if (value == null || value === '') {
    return '-'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return String(value)
  }
  return date.toISOString().replace(/\.\d{3}Z$/, 'Z')
}

function formatMarketEnd(order: {
  marketEndAt?: number | null
  marketEndAtBjt?: string | null
}): string {
  if (order.marketEndAtBjt) {
    return order.marketEndAtBjt
  }
  if (order.marketEndAt) {
    return formatIsoSecond(order.marketEndAt)
  }
  return '-'
}

interface OrdersTableProps {
  mode?: 'PAPER' | 'LIVE'
  pnl?: 'ALL' | 'PROFIT' | 'LOSS' | 'ZERO'
  settlement?: 'ALL' | 'SETTLED' | 'UNSETTLED' | 'EARLY_EXIT'
}

function parseNextCursor(nextCursor?: string | null): number | undefined {
  const cursor = Number(nextCursor)
  return Number.isFinite(cursor) && cursor > 0 ? cursor : undefined
}

export function OrdersTable({ mode, pnl = 'ALL', settlement = 'ALL' }: OrdersTableProps) {
  const [page, setPage] = useState(1)
  const [keyword, setKeyword] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [marketLifecycleFilter, setMarketLifecycleFilter] = useState<MarketLifecycleFilter>('ALL')
  const deferredKeyword = useDeferredValue(keyword.trim())

  const {
    data,
    isLoading,
    isFetchingNextPage,
    hasNextPage,
    fetchNextPage,
  } = useInfiniteQuery({
    queryKey: ['ordersPage', mode, pnl, settlement, statusFilter, deferredKeyword],
    initialPageParam: null as number | null,
    queryFn: ({ pageParam }) => walletApi.getOrdersPage(ORDER_CURSOR_PAGE_SIZE, pageParam, mode, {
      pnl,
      settlement,
      status: statusFilter,
      q: deferredKeyword,
    }),
    getNextPageParam: (lastPage) => parseNextCursor(lastPage.nextCursor),
  })

  const orders = data?.pages.flatMap((ordersPage) => ordersPage.items) ?? []

  const activeCount = orders.filter(isActiveOrderMarket).length
  const endedCount = orders.length - activeCount
  const filteredOrders = orders.filter((order) => {
    if (marketLifecycleFilter === 'ACTIVE') return isActiveOrderMarket(order)
    if (marketLifecycleFilter === 'ENDED') return !isActiveOrderMarket(order)
    return true
  })

  const loadedPages = Math.max(1, Math.ceil(filteredOrders.length / TABLE_PAGE_SIZE))
  const totalPages = hasNextPage ? Math.max(loadedPages + 1, page) : loadedPages
  const visibleOrders = filteredOrders.slice((page - 1) * TABLE_PAGE_SIZE, page * TABLE_PAGE_SIZE)
  const hasActiveFilters = Boolean(keyword || statusFilter || marketLifecycleFilter !== 'ALL')
  const requiredItems = page * TABLE_PAGE_SIZE
  const isPageLoading = isFetchingNextPage && orders.length < (page - 1) * TABLE_PAGE_SIZE + 1

  useEffect(() => {
    setPage(1)
  }, [deferredKeyword, statusFilter, marketLifecycleFilter, mode, pnl, settlement])

  useEffect(() => {
    setPage((currentPage) => Math.min(currentPage, totalPages))
  }, [totalPages])

  useEffect(() => {
    if (!hasNextPage || isFetchingNextPage || orders.length >= requiredItems) {
      return
    }
    void fetchNextPage()
  }, [fetchNextPage, orders.length, hasNextPage, isFetchingNextPage, requiredItems])

  if (isLoading) {
    return <div className="loading">订单加载中...</div>
  }

  if (orders.length === 0) {
    return (
      <div className="table-wrap">
        <table>
          <tbody>
            <tr>
              <td colSpan={11} className="empty-state">暂无订单记录</td>
            </tr>
          </tbody>
        </table>
      </div>
    )
  }

  return (
    <>
      <TableToolbar
        keyword={keyword}
        onKeywordChange={setKeyword}
        keywordPlaceholder="搜索订单 / 市场 / 钱包"
        filteredCount={filteredOrders.length}
        totalCount={orders.length}
        countLabel="已加载"
        hasActiveFilters={hasActiveFilters}
        onReset={() => {
          setKeyword('')
          setStatusFilter('')
          setMarketLifecycleFilter('ALL')
        }}
        filters={[
          {
            value: statusFilter,
            onChange: setStatusFilter,
            options: buildTableOptions(orders.map((order) => order.status), '全部状态'),
            ariaLabel: '订单状态筛选',
          },
        ]}
      />

      <div className="orders-market-segmented segmented-control" aria-label="市场生命周期筛选">
        <button
          type="button"
          className={marketLifecycleFilter === 'ALL' ? 'active' : ''}
          onClick={() => setMarketLifecycleFilter('ALL')}
        >
          全部 {orders.length}
        </button>
        <button
          type="button"
          className={marketLifecycleFilter === 'ACTIVE' ? 'active' : ''}
          onClick={() => setMarketLifecycleFilter('ACTIVE')}
        >
          活跃市场 {activeCount}
        </button>
        <button
          type="button"
          className={marketLifecycleFilter === 'ENDED' ? 'active' : ''}
          onClick={() => setMarketLifecycleFilter('ENDED')}
        >
          已结束市场 {endedCount}
        </button>
      </div>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>订单ID</th>
              <th>操作类型</th>
              <th>跟单地址</th>
              <th>市场</th>
              <th>预计结算</th>
              <th>Outcome</th>
              <th>标签 / 收益</th>
              <th>成本</th>
              <th>手续费</th>
              <th>状态</th>
              <th>下单时间</th>
            </tr>
          </thead>
          <tbody>
            {visibleOrders.length === 0 ? (
              <tr>
                <td colSpan={11} className="empty-state">
                  {isPageLoading ? '订单加载中...' : '当前筛选条件下暂无订单记录'}
                </td>
              </tr>
            ) : (
              visibleOrders.map((order) => {
                const marketLabel = order.question || order.marketId || '-'
                const activeMarket = isActiveOrderMarket(order)
                const marketUrl = order.externalMarketUrl || order.marketUrl || order.internalMarketUrl || ''
                const externalMarketLink = marketUrl.startsWith('http')
                const followAddress = order.walletAddress || order.executionAddress || '-'

                return (
                  <tr key={order.id}>
                    <td>{truncateText(order.id, 14)}</td>
                    <td>
                      <div style={{ display: 'grid', gap: '6px' }}>
                        <span className={getOperationBadgeClass(order)}>{getOperationTypeLabel(order)}</span>
                        <span className="table-subtext">{order.side || '-'}{order.orderType ? ` / ${order.orderType}` : ''}</span>
                      </div>
                    </td>
                    <td title={followAddress}>{compactAddress(followAddress)}</td>
                    <td className="market-cell" title={marketLabel}>
                      {marketUrl ? (
                        <a
                          className="market-link"
                          href={marketUrl}
                          target={externalMarketLink ? '_blank' : undefined}
                          rel={externalMarketLink ? 'noreferrer' : undefined}
                          title={`${externalMarketLink ? '在 Polymarket 打开' : '查看内部市场详情'}：${marketLabel}`}
                        >
                          {truncateText(marketLabel, 34)}
                        </a>
                      ) : (
                        <span className="market-cell-title">{truncateText(marketLabel, 34)}</span>
                      )}
                      <div className="market-cell-sub">
                        <span className={activeMarket ? 'badge accent' : 'badge'}>
                          {activeMarket ? '活跃规则' : getLifecycleLabel(order)}
                        </span>
                        {order.normalizedCategory && !activeMarket ? (
                          <span className="badge">{getCategoryLabel(order.normalizedCategory)}</span>
                        ) : null}
                        {activeMarket && order.acceptingOrders === false ? <span className="badge warn">暂停接单</span> : null}
                        {!order.externalMarketUrl && !order.marketSlug ? <span className="badge warn">内部详情</span> : null}
                      </div>
                    </td>
                    <td title={formatMarketEnd(order)}>{formatMarketEnd(order)}</td>
                    <td>{order.outcome}</td>
                    <td>
                      <div style={{ display: 'grid', gap: '6px' }}>
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px' }}>
                          {order.earlyExit ? <span className="badge accent">提前卖出</span> : null}
                          {order.exitReason ? (
                            <span className={`badge ${getExitReasonBadgeClass(order.exitReason, order.realizedPnlUsdc)}`}>
                              {getExitReasonLabel(order.exitReason)}
                            </span>
                          ) : null}
                          {!order.earlyExit && order.orderType ? <span className="badge">{order.orderType}</span> : null}
                        </div>
                        {shouldShowRealizedPnl(order) ? (
                          <span className={getPnlBadgeClass(order.realizedPnlUsdc)}>
                            收益 {formatMoney(order.realizedPnlUsdc)}
                          </span>
                        ) : (
                          <span className="badge">-</span>
                        )}
                      </div>
                    </td>
                    <td>
                      <div>{formatAmount(order.costBasisUsdc ?? order.notionalUsdc)}</div>
                      {order.roiAnomalyReason ? <span className="badge warn" title={order.roiAnomalyReason}>单位异常</span> : null}
                    </td>
                    <td title={formatFeeBreakdown(order)}>
                      {order.feeUsdc ? formatMoney(order.feeUsdc) : '-'}
                    </td>
                    <td>
                      <span className="badge">{order.status}</span>
                    </td>
                    <td>{formatIsoSecond(order.placedAt)}</td>
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
        totalItems={filteredOrders.length}
        onPageChange={setPage}
        hasMore={Boolean(hasNextPage)}
      />
    </>
  )
}
