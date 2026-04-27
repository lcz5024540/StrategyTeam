import { useDeferredValue, useEffect, useState } from 'react'
import type { Position } from '@/types/position'
import { compactAddress, formatAmount, formatMoney, getAmountClass, truncateText } from '@/utils/format'
import { buildTableOptions, matchesTableKeyword, TABLE_PAGE_SIZE } from '@/utils/table'
import { TableToolbar } from './TableToolbar'
import { TablePagination } from './TablePagination'

interface PositionsTableProps {
  positions: Position[]
  loading?: boolean
}

export function PositionsTable({ positions, loading = false }: PositionsTableProps) {
  const [page, setPage] = useState(1)
  const [keyword, setKeyword] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [outcomeFilter, setOutcomeFilter] = useState('')
  const deferredKeyword = useDeferredValue(keyword.trim().toLowerCase())

  const filteredPositions = positions.filter((position) => {
    if (statusFilter && position.status !== statusFilter) {
      return false
    }

    if (outcomeFilter && position.outcome !== outcomeFilter) {
      return false
    }

    return matchesTableKeyword(
      deferredKeyword,
      position.question,
      position.outcome,
      position.executionAddress,
      position.marketId,
      position.tokenId
    )
  })

  const totalPages = Math.max(1, Math.ceil(filteredPositions.length / TABLE_PAGE_SIZE))
  const visiblePositions = filteredPositions.slice((page - 1) * TABLE_PAGE_SIZE, page * TABLE_PAGE_SIZE)
  const hasActiveFilters = Boolean(keyword || statusFilter || outcomeFilter)

  useEffect(() => {
    setPage(1)
  }, [deferredKeyword, statusFilter, outcomeFilter])

  useEffect(() => {
    setPage((currentPage) => Math.min(currentPage, totalPages))
  }, [totalPages])

  if (loading) {
    return <div className="loading">持仓加载中...</div>
  }

  if (positions.length === 0) {
    return (
      <div className="table-wrap">
        <table>
          <tbody>
            <tr>
              <td colSpan={8} className="empty-state">暂无持仓数据</td>
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
        keywordPlaceholder="搜索市场 / 钱包 / outcome"
        filteredCount={filteredPositions.length}
        totalCount={positions.length}
        hasActiveFilters={hasActiveFilters}
        onReset={() => {
          setKeyword('')
          setStatusFilter('')
          setOutcomeFilter('')
        }}
        filters={[
          {
            value: statusFilter,
            onChange: setStatusFilter,
            options: buildTableOptions(positions.map((position) => position.status), '全部状态'),
            ariaLabel: '持仓状态筛选',
          },
          {
            value: outcomeFilter,
            onChange: setOutcomeFilter,
            options: buildTableOptions(positions.map((position) => position.outcome), '全部 Outcome'),
            ariaLabel: '持仓 outcome 筛选',
          },
        ]}
      />

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>钱包</th>
              <th>市场</th>
              <th>Outcome</th>
              <th>数量</th>
              <th>成本</th>
              <th>POL Gas</th>
              <th>未实现</th>
              <th>状态</th>
              <th>到期</th>
            </tr>
          </thead>
          <tbody>
            {visiblePositions.length === 0 ? (
              <tr>
                <td colSpan={9} className="empty-state">当前筛选条件下暂无持仓</td>
              </tr>
            ) : (
              visiblePositions.map((position) => (
                <tr key={position.id}>
                  <td title={position.executionAddress}>{compactAddress(position.executionAddress)}</td>
                  <td title={position.question}>{truncateText(position.question, 36)}</td>
                  <td>{position.outcome}</td>
                  <td>{formatAmount(position.quantity)}</td>
                  <td>{formatAmount(position.costBasisUsdc)}</td>
                  <td>{position.feePolTotal ? formatAmount(position.feePolTotal) : '-'}</td>
                  <td className={getAmountClass(position.unrealizedPnlUsdc)}>{formatMoney(position.unrealizedPnlUsdc)}</td>
                  <td>
                    <span className={`badge ${position.status === 'OPEN' ? 'accent' : ''}`}>
                      {position.status}
                    </span>
                  </td>
                  <td>{position.marketEndAtBjt || '-'}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      <TablePagination
        page={page}
        pageSize={TABLE_PAGE_SIZE}
        totalItems={filteredPositions.length}
        onPageChange={setPage}
      />
    </>
  )
}
