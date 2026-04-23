import { useDeferredValue, useEffect, useState } from 'react'
import type { Position } from '@/types/position'
import { compactAddress, formatAmount, formatMoney, getAmountClass, truncateText } from '@/utils/format'
import { buildTableOptions, matchesTableKeyword, TABLE_PAGE_SIZE } from '@/utils/table'
import { TableToolbar } from './TableToolbar'
import { TablePagination } from './TablePagination'

interface SettlementsTableProps {
  positions: Position[]
}

export function SettlementsTable({ positions }: SettlementsTableProps) {
  const [page, setPage] = useState(1)
  const [keyword, setKeyword] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [claimableFilter, setClaimableFilter] = useState('')
  const deferredKeyword = useDeferredValue(keyword.trim().toLowerCase())

  const filteredPositions = positions.filter((position) => {
    if (statusFilter && position.status !== statusFilter) {
      return false
    }

    const isClaimable = Number(position.claimableProceedsUsdc || 0) > 0
    if (claimableFilter === 'claimable' && !isClaimable) {
      return false
    }
    if (claimableFilter === 'pending' && isClaimable) {
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
  const hasActiveFilters = Boolean(keyword || statusFilter || claimableFilter)

  useEffect(() => {
    setPage(1)
  }, [deferredKeyword, statusFilter, claimableFilter])

  useEffect(() => {
    setPage((currentPage) => Math.min(currentPage, totalPages))
  }, [totalPages])

  if (positions.length === 0) {
    return (
      <div className="table-wrap">
        <table>
          <tbody>
            <tr>
              <td colSpan={7} className="empty-state">暂无待结算仓位</td>
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
          setClaimableFilter('')
        }}
        filters={[
          {
            value: statusFilter,
            onChange: setStatusFilter,
            options: buildTableOptions(positions.map((position) => position.status), '全部状态'),
            ariaLabel: '待结算状态筛选',
          },
          {
            value: claimableFilter,
            onChange: setClaimableFilter,
            options: [
              { label: '全部领取状态', value: '' },
              { label: '仅可领取', value: 'claimable' },
              { label: '仅待确认', value: 'pending' },
            ],
            ariaLabel: '待结算领取状态筛选',
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
              <th>成本</th>
              <th>POL Gas</th>
              <th>待结算净收益</th>
              <th>可领取</th>
              <th>预计结算</th>
            </tr>
          </thead>
          <tbody>
            {visiblePositions.length === 0 ? (
              <tr>
                <td colSpan={8} className="empty-state">当前筛选条件下暂无待结算仓位</td>
              </tr>
            ) : (
              visiblePositions.map((position) => (
                <tr key={position.id}>
                  <td title={position.executionAddress}>{compactAddress(position.executionAddress)}</td>
                  <td title={position.question}>{truncateText(position.question, 36)}</td>
                  <td>{position.outcome}</td>
                  <td>{formatAmount(position.costBasisUsdc)}</td>
                  <td>{position.feePolTotal ? formatAmount(position.feePolTotal) : '-'}</td>
                  <td className={getAmountClass(position.pendingSettlementNetPnlUsdc)}>
                    {formatMoney(position.pendingSettlementNetPnlUsdc)}
                  </td>
                  <td>{formatAmount(position.claimableProceedsUsdc)}</td>
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
