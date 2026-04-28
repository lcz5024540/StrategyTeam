import { type StrategyCode } from './constants'

export interface StrategyPageConfig {
  key: string
  label: string
  route: string
  title: string
}

const FALLBACK_PAGES: StrategyPageConfig[] = [
  { key: 'dashboard', label: '总览', route: '/dashboard', title: 'Strategy Dashboard' },
  { key: 'factors', label: '因子', route: '/dashboard/factors', title: 'Factor Analysis' },
]

export const STRATEGY_PAGE_CONFIG: Record<StrategyCode, StrategyPageConfig[]> = {
  POLYMARKET_COPY: [
    { key: 'dashboard', label: '总览', route: '/dashboard', title: 'Polymarket Dashboard' },
    { key: 'addresses', label: '地址监控', route: '/dashboard/addresses', title: 'Address Monitor' },
    { key: 'markets', label: '市场监控', route: '/dashboard/markets', title: 'Market Monitor' },
    { key: 'signals', label: '任务信号', route: '/dashboard/signals', title: 'Signal Tasks' },
    { key: 'charts', label: '图表归因', route: '/dashboard/charts', title: 'Attribution Charts' },
    { key: 'factors', label: '因子', route: '/dashboard/factors', title: 'Factor Analysis' },
  ],
  FUNDING_ARB: [
    { key: 'dashboard', label: '总览', route: '/dashboard', title: 'Funding Dashboard' },
    { key: 'execution', label: '执行控制台', route: '/dashboard/funding-arb', title: 'Execution Console' },
    { key: 'positions', label: '持仓订单', route: '/dashboard/funding-arb/positions', title: 'Positions & Orders' },
    { key: 'settlement', label: '结算窗口', route: '/dashboard/funding-arb/settlement', title: 'Settlement Windows' },
    { key: 'factors', label: '因子', route: '/dashboard/factors', title: 'Factor Analysis' },
  ],
  ROBOT: FALLBACK_PAGES,
  INTELLIGENT: FALLBACK_PAGES,
}

export function getStrategyPages(strategyCode: StrategyCode): StrategyPageConfig[] {
  return STRATEGY_PAGE_CONFIG[strategyCode] || FALLBACK_PAGES
}

export function getDefaultStrategyRoute(strategyCode: StrategyCode): string {
  return getStrategyPages(strategyCode)[0]?.route || '/dashboard'
}

export function getStrategyPageTitle(strategyCode: StrategyCode, pathname: string): string {
  const matched = getStrategyPages(strategyCode).find((page) => page.route === pathname)
  return matched?.title || 'Strategy Dashboard'
}
