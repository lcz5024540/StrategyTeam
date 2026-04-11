export function formatMoney(value: number | null | undefined): string {
  const num = Number(value || 0)
  const sign = num >= 0 ? '+' : '-'
  return `${sign}$${Math.abs(num).toFixed(2)}`
}

export function formatAmount(value: number | null | undefined): string {
  const num = Number(value || 0)
  return `$${num.toFixed(2)}`
}

export function formatPercent(value: number | null | undefined): string {
  const num = Number(value || 0) * 100
  return `${num.toFixed(2)}%`
}

export function formatNumber(value: number | null | undefined, decimals = 2): string {
  const num = Number(value || 0)
  return num.toFixed(decimals)
}

export function getAmountClass(value: number | null | undefined): 'positive' | 'negative' | 'neutral' {
  const num = Number(value || 0)
  if (num > 0) return 'positive'
  if (num < 0) return 'negative'
  return 'neutral'
}

function toDisplayText(value: unknown): string {
  if (value == null) return ''
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'bigint' || typeof value === 'boolean') {
    return String(value)
  }

  try {
    return JSON.stringify(value) ?? String(value)
  } catch {
    return String(value)
  }
}

export function compactAddress(address: unknown, prefixLen = 6, suffixLen = 4): string {
  const text = toDisplayText(address)
  if (!text || text.length <= prefixLen + suffixLen) return text
  return `${text.slice(0, prefixLen)}...${text.slice(-suffixLen)}`
}

export function truncateText(text: unknown, maxLen: number): string {
  const normalizedText = toDisplayText(text)
  if (!normalizedText || normalizedText.length <= maxLen) return normalizedText
  return `${normalizedText.slice(0, maxLen)}...`
}
