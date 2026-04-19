import { useEffect, useState } from 'react'
import { clampTablePage } from '@/utils/table'

interface TablePaginationProps {
  page: number
  pageSize: number
  totalItems: number
  onPageChange: (page: number) => void
  hasMore?: boolean
}

export function TablePagination({
  page,
  pageSize,
  totalItems,
  onPageChange,
  hasMore = false,
}: TablePaginationProps) {
  const loadedPages = Math.max(1, Math.ceil(totalItems / pageSize))
  const totalPages = hasMore ? Math.max(loadedPages + 1, page) : loadedPages
  const [jumpPage, setJumpPage] = useState(String(page))

  useEffect(() => {
    setJumpPage(String(page))
  }, [page])

  const goToPage = (nextPage: number) => {
    onPageChange(clampTablePage(nextPage, totalPages))
  }

  const submitJump = () => {
    const nextPage = Number(jumpPage)
    goToPage(nextPage)
  }

  return (
    <div className="table-pagination">
      <div className="table-pagination-summary">
        {hasMore
          ? `第 ${page} 页，每页 ${pageSize} 条，已加载 ${totalItems} 条，可继续翻页`
          : `第 ${page} / ${totalPages} 页，每页 ${pageSize} 条，共 ${totalItems} 条`}
      </div>
      <div className="table-pagination-actions">
        <button
          type="button"
          className="btn btn-secondary"
          disabled={page <= 1}
          onClick={() => goToPage(page - 1)}
        >
          上一页
        </button>
        <button
          type="button"
          className="btn btn-secondary"
          disabled={!hasMore && page >= totalPages}
          onClick={() => goToPage(page + 1)}
        >
          下一页
        </button>
        <label className="table-jump">
          <span>跳转到</span>
          <input
            type="number"
            min={1}
            max={totalPages}
            value={jumpPage}
            onChange={(event) => setJumpPage(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                submitJump()
              }
            }}
          />
          <span>页</span>
        </label>
        <button type="button" className="btn btn-primary" onClick={submitJump}>
          跳转
        </button>
      </div>
    </div>
  )
}
