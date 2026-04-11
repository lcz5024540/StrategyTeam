export const STRATEGY_CATALOG = [
  {
    code: 'POLYMARKET_COPY',
    name: 'Polymarket 跟单',
    description: '跟踪聪明钱包的交易信号，自动复制优质交易',
    status: 'ACTIVE' as const,
  },
  {
    code: 'HEDGE',
    name: '对冲策略',
    description: '多空对冲，降低风险敞口',
    status: 'PLANNED' as const,
  },
  {
    code: 'ROBOT',
    name: '机器人策略',
    description: '自动化量化交易机器人',
    status: 'PLANNED' as const,
  },
  {
    code: 'INTELLIGENT',
    name: '智能策略',
    description: 'AI 驱动的智能交易策略',
    status: 'PLANNED' as const,
  },
]

export type StrategyCode = typeof STRATEGY_CATALOG[number]['code']
export type PositionMode = 'PAPER' | 'LIVE'
