export const STRATEGY_CATALOG = [
  {
    code: 'POLYMARKET_COPY',
    name: 'Polymarket 跟单',
    description: '跟踪聪明钱包的交易信号，自动复制优质交易',
    route: '/dashboard',
    status: 'ACTIVE' as const,
    supportedModes: ['PAPER', 'LIVE'] as const,
    capabilities: ['POSITIONS', 'ORDERS', 'TIMELINE', 'TASKS', 'ATTRIBUTION', 'FACTORS', 'CONFLICTS'] as const,
    modeBehavior: 'ISOLATED' as const,
  },
  {
    code: 'FUNDING_ARB',
    name: '资金费套利策略',
    description: '利用永续合约资金费率差异进行套利',
    route: '/dashboard',
    status: 'ACTIVE' as const,
    supportedModes: ['PAPER', 'LIVE'] as const,
    capabilities: ['POSITIONS', 'ORDERS', 'TIMELINE', 'FACTORS'] as const,
    modeBehavior: 'SHARED' as const,
  },
  {
    code: 'ROBOT',
    name: '机器人策略',
    description: '自动化量化交易机器人',
    route: '/dashboard',
    status: 'PLANNED' as const,
    supportedModes: ['PAPER'] as const,
    capabilities: ['FACTORS'] as const,
    modeBehavior: 'UNSUPPORTED' as const,
  },
  {
    code: 'INTELLIGENT',
    name: '智能策略',
    description: 'AI 驱动的智能交易策略',
    route: '/dashboard',
    status: 'PLANNED' as const,
    supportedModes: ['PAPER'] as const,
    capabilities: ['FACTORS'] as const,
    modeBehavior: 'UNSUPPORTED' as const,
  },
]

export const DEFAULT_STRATEGY_CODE = 'POLYMARKET_COPY'

export type StrategyCode = typeof STRATEGY_CATALOG[number]['code']
export type PositionMode = 'PAPER' | 'LIVE'

export function normalizeStrategyCode(code: unknown): StrategyCode {
  const value = typeof code === 'string' ? code.trim() : ''
  const matched = STRATEGY_CATALOG.find((strategy) => strategy.code === value)
  return (matched?.code || DEFAULT_STRATEGY_CODE) as StrategyCode
}

export function isRunnableStrategyCode(code: unknown): code is StrategyCode {
  const value = normalizeStrategyCode(code)
  const matched = STRATEGY_CATALOG.find((strategy) => strategy.code === value)
  return matched?.status === 'ACTIVE'
}

export function normalizeRunnableStrategyCode(code: unknown): StrategyCode {
  const value = normalizeStrategyCode(code)
  return isRunnableStrategyCode(value) ? value : DEFAULT_STRATEGY_CODE
}
