import { useDeferredValue, useEffect, useState } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { walletApi } from '@/api'
import { compactAddress, formatAmount, formatMoney, truncateText } from '@/utils/format'
import { buildTableOptions, matchesTableKeyword, TABLE_PAGE_SIZE } from '@/utils/table'
import { TableToolbar } from './TableToolbar'
import { TablePagination } from './TablePagination'

const ORDER_CURSOR_PAGE_SIZE = 50

const EXIT_REASON_LABELS: Record<string, string> = {
  TAKE_PROFIT_AUTO_EXIT: '自动止盈',
  STOP_LOSS_AUTO_EXIT: '自动止损',
}

function getExitReasonLabel(exitReason?: string): string {
  if (!exitReason) {
    return '提前卖出'
  }
  return EXIT_REASON_LABELS[exitReason] || exitReason
}

function getPnlBadgeClass(value?: number): string {
  const pnl = Number(value || 0)
  if (pnl > 0) return 'badge accent'
  if (pnl < 0) return 'badge danger'
  return 'badge'
}

interface OrdersTableProps {
  mode?: 'PAPER' | 'LIVE'
}

function parseNextCursor(nextCursor?: string | null): number | undefined {
  const cursor = Number(nextCursor)
  return Number.isFinite(cursor) && cursor > 0 ? cursor : undefined
}

export function OrdersTable({ mode }: OrdersTableProps) {
  const [page, setPage] = useState(1)
  const [keyword, setKeyword] = useState('')
  const [sideFilter, setSideFilter] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const deferredKeyword = useDeferredValue(keyword.trim().toLowerCase())

  const {
    data,
    isLoading,
    isFetchingNextPage,
    hasNextPage,
    fetchNextPage,
  } = useInfiniteQuery({
    queryKey: ['ordersPage', mode],
    initialPageParam: null as number | null,
    queryFn: ({ pageParam }) => walletApi.getOrdersPage(ORDER_CURSOR_PAGE_SIZE, pageParam, mode),
    getNextPageParam: (lastPage) => parseNextCursor(lastPage.nextCursor),
  })

  const orders = data?.pages.flatMap((ordersPage) => ordersPage.items) ?? []

  const filteredOrders = orders.filter((order) => {
    if (sideFilter && order.side !== sideFilter) {
      return false
    }

    if (statusFilter && order.status !== statusFilter) {
      return false
    }

    return matchesTableKeyword(
      deferredKeyword,
      order.id,
      order.question,
      order.outcome,
      order.executionAddress,
      order.marketId
    )
  })

  const loadedPages = Math.max(1, Math.ceil(filteredOrders.length / TABLE_PAGE_SIZE))
  const totalPages = hasNextPage ? Math.max(loadedPages + 1, page) : loadedPages
  const visibleOrders = filteredOrders.slice((page - 1) * TABLE_PAGE_SIZE, page * TABLE_PAGE_SIZE)
  const hasActiveFilters = Boolean(keyword || sideFilter || statusFilter)
  const requiredItems = page * TABLE_PAGE_SIZE
  const isPageLoading = isFetchingNextPage && filteredOrders.length < (page - 1) * TABLE_PAGE_SIZE + 1

  useEffect(() => {
    setPage(1)
  }, [deferredKeyword, sideFilter, statusFilter, mode])

  useEffect(() => {
    setPage((currentPage) => Math.min(currentPage, totalPages))
  }, [totalPages])

  useEffect(() => {
    if (!hasNextPage || isFetchingNextPage || filteredOrders.length >= requiredItems) {
      return
    }
    void fetchNextPage()
  }, [fetchNextPage, filteredOrders.length, hasNextPage, isFetchingNextPage, requiredItems])

  if (isLoading) {
    return <div className="loading">订单加载中...</div>
  }

  if (orders.length === 0) {
    return (
      <div className="table-wrap">
        <table>
          <tbody>
            <tr>
              <td colSpan={9} className="empty-state">暂无订单记录</td>
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
        hasActiveFilters={hasActiveFilters}
        onReset={() => {
          setKeyword('')
          setSideFilter('')
          setStatusFilter('')
        }}
        filters={[
          {
            value: sideFilter,
            onChange: setSideFilter,
            options: buildTableOptions(orders.map((order) => order.side), '全部方向'),
            ariaLabel: '订单方向筛选',
          },
          {
            value: statusFilter,
            onChange: setStatusFilter,
            options: buildTableOptions(orders.map((order) => order.status), '全部状态'),
            ariaLabel: '订单状态筛选',
          },
        ]}
      />

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>订单ID</th>
              <th>钱包</th>
              <th>市场</th>
              <th>Outcome</th>
              <th>方向</th>
              <th>标签 / 收益</th>
              <th>名义金额</th>
              <th>状态</th>
              <th>下单时间</th>
            </tr>
          </thead>
          <tbody>
            {visibleOrders.length === 0 ? (
              <tr>
                <td colSpan={9} className="empty-state">
                  {isPageLoading ? '订单加载中...' : '当前筛选条件下暂无订单记录'}
                </td>
              </tr>
            ) : (
              visibleOrders.map((order) => (
                <tr key={order.id}>
                  <td>{truncateText(order.id, 14)}</td>
                  <td title={order.executionAddress}>{compactAddress(order.executionAddress)}</td>
                  <td title={order.question}>{truncateText(order.question, 34)}</td>
                  <td>{order.outcome}</td>
                  <td>
                    <span className={`badge ${order.side === 'BUY' ? 'accent' : 'warn'}`}>{order.side}</span>
                  </td>
                  <td>
                    <div style={{ display: 'grid', gap: '6px' }}>
                      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px' }}>
                        {order.earlyExit ? <span className="badge accent">提前卖出</span> : null}
                        {order.exitReason ? (
                          <span className={`badge ${order.exitReason === 'STOP_LOSS_AUTO_EXIT' ? 'danger' : 'warn'}`}>
                            {getExitReasonLabel(order.exitReason)}
                          </span>
                        ) : null}
                        {!order.earlyExit && order.orderType ? <span className="badge">{order.orderType}</span> : null}
                      </div>
                      {order.earlyExit ? (
                        <span className={getPnlBadgeClass(order.realizedPnlUsdc)}>
                          收益 {formatMoney(order.realizedPnlUsdc)}
                        </span>
                      ) : (
                        <span className="badge">-</span>
                      )}
                    </div>
                  </td>
                  <td>{formatAmount(order.notionalUsdc)}</td>
                  <td>
                    <span className="badge">{order.status}</span>
                  </td>
                  <td>{order.placedAt || '-'}</td>
                </tr>
              ))
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
