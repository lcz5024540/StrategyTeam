export interface Attribution {
  label: string
  address?: string
  marketId?: string
  question?: string
  netPnlUsdc: number
  realizedPnlUsdc: number
  unrealizedPnlUsdc: number
  pendingSettlementPnlUsdc: number
  totalVolumeUsdc: number
  tradeCount: number
  winRate: number
}

export interface DailyReport {
  reportDate: string
  executionAddress: string
  mode: string
  openPositions: number
  closedPositions: number
  totalVolumeUsdc: number
  feeUsdc: number
  profitPnlUsdc: number
  lossPnlUsdc: number
  realizedPnlUsdc: number
  unrealizedPnlUsdc: number
  netPnlUsdc: number
  winningPositions: number
  losingPositions: number
  winRate: number
}

export interface ConflictMarket {
  marketId: string
  question: string
  outcomes: string[]
  positionCount: number
  totalCostUsdc: number
  netPnlUsdc: number
  status: string
  marketEndAt: number
  marketEndAtBjt: string
}

export interface FollowAddressDailyReport {
  reportDate: string
  walletAddress: string
  totalOrders: number
  filledOrders: number
  openPositions: number
  closedPositions: number
  winningPositions: number
  losingPositions: number
  breakevenPositions: number
  distinctMarkets: number
  tradedVolumeUsdc: number
  grossExposureUsdc: number
  feeUsdc: number
  realizedPnlUsdc: number
  unrealizedPnlUsdc: number
  netPnlUsdc: number
  winRate: number
  averageWinUsdc: number
  averageLossUsdc: number
  largestWinUsdc: number
  largestLossUsdc: number
}

export interface TimelinePoint {
  bucketAt: string
  profitPnlUsdc: number
  lossPnlUsdc: number
  realizedPnlUsdc: number
  feeUsdc: number
  netPnlUsdc: number
  cumulativeLossPnlUsdc: number
  cumulativeRealizedPnlUsdc: number
  cumulativeFeeUsdc: number
  cumulativeNetPnlUsdc: number
}

export interface OrderPnlCategoryRow {
  key: string
  label: string
  walletAddress?: string
  category?: string
  profitOrderCount: number
  lossOrderCount: number
  totalOrderCount: number
}

export interface OrderPnlCategoryWindow {
  window: '30d' | '7d' | 'all' | string
  label: string
  marketCategories: OrderPnlCategoryRow[]
  walletCategories: OrderPnlCategoryRow[]
}

export interface OrderPnlCategoryAttribution {
  windows: OrderPnlCategoryWindow[]
}
