export interface TableSelectOption {
  label: string
  value: string
}

export const TABLE_PAGE_SIZE = 10
export const TABLE_FETCH_LIMIT = 500

export function normalizeTableText(value: unknown): string {
  return String(value ?? '').trim().toLowerCase()
}

export function matchesTableKeyword(keyword: string, ...values: unknown[]): boolean {
  if (!keyword) {
    return true
  }

  return values.some((value) => normalizeTableText(value).includes(keyword))
}

export function buildTableOptions(values: Array<string | null | undefined>, allLabel: string): TableSelectOption[] {
  const uniqueValues = Array.from(
    new Set(values.map((value) => String(value ?? '').trim()).filter(Boolean))
  )

  return [
    { label: allLabel, value: '' },
    ...uniqueValues.map((value) => ({ label: value, value })),
  ]
}

export function clampTablePage(page: number, totalPages: number): number {
  const safeTotalPages = Math.max(totalPages, 1)

  if (!Number.isFinite(page)) {
    return 1
  }

  return Math.min(Math.max(Math.floor(page), 1), safeTotalPages)
}
