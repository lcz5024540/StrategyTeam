import type { StrategyCapability, StrategyModeBehavior } from '@/types/strategy'

export type StrategyCode = 'POLYMARKET_COPY' | 'STOCK_HARBOR' | 'FUNDING_ARB' | 'ROBOT' | 'INTELLIGENT'
export type PositionMode = 'PAPER' | 'LIVE'

interface StrategyCatalogEntry {
  code: StrategyCode
  name: string
  description: string
  route: string
  status: 'ACTIVE' | 'PLANNED'
  supportedModes: readonly PositionMode[]
  capabilities: readonly StrategyCapability[]
  modeBehavior: StrategyModeBehavior
}

export const STRATEGY_CATALOG: readonly StrategyCatalogEntry[] = [
  {
    code: 'POLYMARKET_COPY',
    name: 'Polymarket 跟单',
    description: '跟踪聪明钱包的交易信号，自动复制优质交易',
    route: '/dashboard',
    status: 'ACTIVE',
    supportedModes: ['PAPER', 'LIVE'],
    capabilities: ['POSITIONS', 'ORDERS', 'TIMELINE', 'TASKS', 'ATTRIBUTION', 'FACTORS', 'CONFLICTS'],
    modeBehavior: 'ISOLATED',
  },
  {
    code: 'STOCK_HARBOR',
    name: 'StockHarbor 基金金库',
    description: '链上股票策略金库：经理保证金、TEE 证明、受限交易和白名单股票订单',
    route: '/dashboard',
    status: 'ACTIVE',
    supportedModes: ['PAPER', 'LIVE'],
    capabilities: [],
    modeBehavior: 'ISOLATED',
  },
  {
    code: 'FUNDING_ARB',
    name: '资金费套利策略',
    description: '利用永续合约资金费率差异进行套利',
    route: '/dashboard',
    status: 'ACTIVE',
    supportedModes: ['PAPER', 'LIVE'],
    capabilities: ['POSITIONS', 'ORDERS', 'TIMELINE', 'FACTORS'],
    modeBehavior: 'SHARED',
  },
  {
    code: 'ROBOT',
    name: '机器人策略',
    description: '自动化量化交易机器人',
    route: '/dashboard',
    status: 'PLANNED',
    supportedModes: ['PAPER'],
    capabilities: ['FACTORS'],
    modeBehavior: 'UNSUPPORTED',
  },
  {
    code: 'INTELLIGENT',
    name: '智能策略',
    description: 'AI 驱动的智能交易策略',
    route: '/dashboard',
    status: 'PLANNED',
    supportedModes: ['PAPER'],
    capabilities: ['FACTORS'],
    modeBehavior: 'UNSUPPORTED',
  },
]

export const DEFAULT_STRATEGY_CODE: StrategyCode = 'POLYMARKET_COPY'

export function normalizeStrategyCode(code: unknown): StrategyCode {
  const value = typeof code === 'string' ? code.trim() : ''
  const matched = STRATEGY_CATALOG.find((strategy) => strategy.code === value)
  return matched?.code || DEFAULT_STRATEGY_CODE
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
