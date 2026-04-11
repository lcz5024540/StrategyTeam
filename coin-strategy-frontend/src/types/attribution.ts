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
  realizedPnlUsdc: number
  unrealizedPnlUsdc: number
  netPnlUsdc: number
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

export interface TimelinePoint {
  date: string
  cumulativePnlUsdc: number
  dailyPnlUsdc: number
  winRate: number
}
