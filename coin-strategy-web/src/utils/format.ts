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

export function compactAddress(address: string, prefixLen = 6, suffixLen = 4): string {
  if (!address || address.length <= prefixLen + suffixLen) return address
  return `${address.slice(0, prefixLen)}...${address.slice(-suffixLen)}`
}

export function truncateText(text: string, maxLen: number): string {
  if (!text || text.length <= maxLen) return text
  return `${text.slice(0, maxLen)}...`
}
