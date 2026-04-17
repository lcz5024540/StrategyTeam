import { useDeferredValue, useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { walletApi } from '@/api'
import { compactAddress, formatAmount, truncateText } from '@/utils/format'
import { buildTableOptions, matchesTableKeyword, TABLE_FETCH_LIMIT, TABLE_PAGE_SIZE } from '@/utils/table'
import { TableToolbar } from './TableToolbar'
import { TablePagination } from './TablePagination'

interface OrdersTableProps {
  mode?: 'PAPER' | 'LIVE'
}

export function OrdersTable({ mode }: OrdersTableProps) {
  const [page, setPage] = useState(1)
  const [keyword, setKeyword] = useState('')
  const [sideFilter, setSideFilter] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const deferredKeyword = useDeferredValue(keyword.trim().toLowerCase())

  const { data: orders = [], isLoading } = useQuery({
    queryKey: ['orders', mode],
    queryFn: () => walletApi.getOrders(TABLE_FETCH_LIMIT, mode),
  })

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

  const totalPages = Math.max(1, Math.ceil(filteredOrders.length / TABLE_PAGE_SIZE))
  const visibleOrders = filteredOrders.slice((page - 1) * TABLE_PAGE_SIZE, page * TABLE_PAGE_SIZE)
  const hasActiveFilters = Boolean(keyword || sideFilter || statusFilter)

  useEffect(() => {
    setPage(1)
  }, [deferredKeyword, sideFilter, statusFilter])

  useEffect(() => {
    setPage((currentPage) => Math.min(currentPage, totalPages))
  }, [totalPages])

  if (isLoading) {
    return <div className="loading">订单加载中...</div>
  }

  if (orders.length === 0) {
    return (
      <div className="table-wrap">
        <table>
          <tbody>
            <tr>
              <td colSpan={8} className="empty-state">暂无订单记录</td>
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
              <th>名义金额</th>
              <th>状态</th>
              <th>下单时间</th>
            </tr>
          </thead>
          <tbody>
            {visibleOrders.length === 0 ? (
              <tr>
                <td colSpan={8} className="empty-state">当前筛选条件下暂无订单记录</td>
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
      />
    </>
  )
}
