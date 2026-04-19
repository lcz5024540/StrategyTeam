// 单边对冲订单（hedge_order）
export interface HedgeOrder {
  id: number
  hedgePositionId: number
  marketId: string
  tokenId: string
  side: 'YES' | 'NO'
  externalOrderId: string | null
  orderType: string
  price: number
  requestedQuantity: number
  filledQuantity: number
  costUsdc: number
  feeUsdc: number
  status: 'PENDING' | 'FILLED' | 'PARTIAL' | 'FAILED' | 'CANCELLED'
  errorMessage: string | null
  submittedAt: string | null
  filledAt: string | null
}

// 聚合后的对冲持仓对（hedge_position + 两笔订单）
export interface HedgePair {
  positionId: number
  marketId: string
  question: string
  yesEntryPrice: number
  noEntryPrice: number
  probDeviation: number
  grossEdgeRate: number
  quantity: number
  yesCostUsdc: number
  noCostUsdc: number
  totalCostUsdc: number
  expectedProfitUsdc: number
  executionAddress: string
  status: 'OPEN' | 'SETTLING' | 'SETTLED' | 'CANCELLED'
  finalOutcome: string | null
  claimedUsdc: number
  feeUsdc: number
  realizedPnlUsdc: number
  marketEndAt: number | null
  openedAt: string | null
  settledAt: string | null
  yesOrder: HedgeOrder | null
  noOrder: HedgeOrder | null
}

// 仪表盘汇总
export interface HedgeDashboard {
  openPositionCount: number
  settlingPositionCount: number
  settledPositionCount: number
  cancelledPositionCount: number
  totalInvestedUsdc: number
  expectedProfitUsdc: number
  realizedPnlUsdc: number
  totalFeeUsdc: number
  netPnlUsdc: number
  averageGrossEdgeRate: number
  winCount: number
  lossCount: number
  winRate: number
  availableCapitalUsdc: number
  maxDrawdownUsdc: number
}

// 套利扫描日志
export interface HedgeArbitrageLog {
  id: number
  marketId: string
  question: string
  yesPrice: number
  noPrice: number
  probDeviation: number
  grossEdgeRate: number
  action: string
  message: string
  createdAt: string
}

// 全局风险敞口
export interface HedgeRiskExposure {
  totalOpenPositions: number
  totalLockedUsdc: number
  totalExpectedProfitUsdc: number
  largestSinglePositionUsdc: number
  averageEdgeRate: number
  expiringIn24hCount: number
  expiringIn7dCount: number
}
