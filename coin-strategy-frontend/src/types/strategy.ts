export interface StrategySummary {
  code: string
  name: string
  description: string
  status: string
  openPositions: number
  netPnlUsdc: number
  winRate: number
}

export interface StrategyOverview {
  strategies: StrategySummary[]
  totalNetPnlUsdc: number
  totalOpenPositions: number
}

export interface StrategyDetail {
  code: string
  name: string
  description: string
  // 收益指标
  netPnlUsdc: number
  compositePnlUsdc: number
  realizedPnlUsdc: number
  unrealizedPnlUsdc: number
  pendingSettlementPnlUsdc: number
  // 持仓指标
  openPositions: number
  closedPositions: number
  openConflictingMarkets: number
  // 风险指标
  maxDrawdownUsdc: number
  remainingPositionSlots: number
  remainingCapitalBudgetUsdc: number
  availableCapitalUsdc: number
  lockedClaimProceedsUsdc: number
  earlyExitOrderCount: number
  earlyExitRealizedPnlUsdc: number
  takeProfitOrderCount: number
  takeProfitRealizedPnlUsdc: number
  stopLossOrderCount: number
  stopLossRealizedPnlUsdc: number
  // 胜率
  winRate: number
  totalTrades: number
  winTrades: number
  lossTrades: number
}
