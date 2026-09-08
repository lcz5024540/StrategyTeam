import type { StrategyCode } from './constants'

export type StrategyVisualType = 'polymarket' | 'funding' | 'stock-harbor' | 'bot' | 'trend'

export interface StrategyVisualMeta {
  type: StrategyVisualType
  label: string
  icon: string
  className: string
}

export const STRATEGY_VISUALS: Record<StrategyCode, StrategyVisualMeta> = {
  POLYMARKET_COPY: {
    type: 'polymarket',
    label: 'Polymarket',
    icon: '⬡',
    className: 'polymarket',
  },
  FUNDING_ARB: {
    type: 'funding',
    label: 'Funding Arb',
    icon: '⇌',
    className: 'funding',
  },
  STOCK_HARBOR: {
    type: 'stock-harbor',
    label: 'Secure Vaults',
    icon: '⌬',
    className: 'stock-harbor',
  },
  ROBOT: {
    type: 'bot',
    label: 'Bot / HFT',
    icon: '⚡',
    className: 'bot',
  },
  INTELLIGENT: {
    type: 'trend',
    label: 'Smart Trend',
    icon: '↗',
    className: 'trend',
  },
}

export const PRIME_NAV_ITEMS = [
  { id: 'dashboard', label: 'Dashboard', icon: '◈', route: '/dashboard' },
  { id: 'monitor', label: 'Monitor', icon: '⬢', route: '/dashboard/monitor' },
  { id: 'factor', label: 'Factors', icon: '⬙', route: '/dashboard/factors' },
  { id: 'logs', label: 'Logs', icon: '≡', route: '/dashboard/logs' },
] as const

export function getStrategyVisual(code: StrategyCode): StrategyVisualMeta {
  return STRATEGY_VISUALS[code] || STRATEGY_VISUALS.POLYMARKET_COPY
}
