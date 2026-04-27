export const STRATEGY_CATALOG = [
  {
    code: 'POLYMARKET_COPY',
    name: 'Polymarket 跟单',
    description: '跟踪聪明钱包的交易信号，自动复制优质交易',
    route: '/dashboard',
    status: 'ACTIVE' as const,
  },
  {
    code: 'FUNDING_ARB',
    name: '资金费套利策略',
    description: '利用永续合约资金费率差异进行套利',
    route: '/dashboard/funding-arb',
    status: 'ACTIVE' as const,
  },
  {
    code: 'ROBOT',
    name: '机器人策略',
    description: '自动化量化交易机器人',
    route: '/dashboard/robot',
    status: 'PLANNED' as const,
  },
  {
    code: 'INTELLIGENT',
    name: '智能策略',
    description: 'AI 驱动的智能交易策略',
    route: '/dashboard/intelligent',
    status: 'PLANNED' as const,
  },
]

export type StrategyCode = typeof STRATEGY_CATALOG[number]['code']
export type PositionMode = 'PAPER' | 'LIVE'
