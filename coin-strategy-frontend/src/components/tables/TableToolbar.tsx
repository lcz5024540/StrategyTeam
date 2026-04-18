import type { TableSelectOption } from '@/utils/table'

interface TableToolbarFilter {
  value: string
  options: TableSelectOption[]
  onChange: (value: string) => void
  ariaLabel: string
}

interface TableToolbarProps {
  keyword: string
  onKeywordChange: (value: string) => void
  keywordPlaceholder: string
  filters?: TableToolbarFilter[]
  filteredCount: number
  totalCount: number
  hasActiveFilters?: boolean
  onReset?: () => void
}

export function TableToolbar({
  keyword,
  onKeywordChange,
  keywordPlaceholder,
  filters = [],
  filteredCount,
  totalCount,
  hasActiveFilters = false,
  onReset,
}: TableToolbarProps) {
  return (
    <div className="table-toolbar">
      <div className="filter-bar">
        <input
          className="table-input"
          type="search"
          value={keyword}
          placeholder={keywordPlaceholder}
          aria-label={keywordPlaceholder}
          onChange={(event) => onKeywordChange(event.target.value)}
        />
        {filters.map((filter) => (
          <select
            key={filter.ariaLabel}
            value={filter.value}
            aria-label={filter.ariaLabel}
            onChange={(event) => filter.onChange(event.target.value)}
          >
            {filter.options.map((option) => (
              <option key={`${filter.ariaLabel}-${option.value || 'all'}`} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        ))}
        {hasActiveFilters && onReset ? (
          <button type="button" className="btn btn-secondary table-reset-btn" onClick={onReset}>
            重置筛选
          </button>
        ) : null}
      </div>
      <div className="table-toolbar-meta">
        筛选后 {filteredCount} 条 / 共 {totalCount} 条
      </div>
    </div>
  )
}
