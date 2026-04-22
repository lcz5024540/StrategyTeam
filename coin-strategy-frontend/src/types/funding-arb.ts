export interface FundingArbExecuteParams {
  symbols: string
  amount: number
  minRate: number
  leverage: number
}

export interface FundingArbExecutionSummaryItem {
  symbol: string
  status: string
}

export interface FundingArbExecutionSummary {
  total: number
  opened: number
  skipped: number
  failed: number
  items: FundingArbExecutionSummaryItem[]
}

export interface FundingArbPositionView {
  id: number
  marketId: string
  question: string
  outcome: string
  status: string
  quantity: number
  averageEntryPrice: number
  costBasisUsdc: number
  originalCostBasisUsdc: number
  realizedPnlUsdc: number
  unrealizedPnlUsdc: number
  pendingSettlementNetPnlUsdc: number
  claimableProceedsUsdc: number
  marketEndAtBjt?: string | null
}

export interface FundingArbOrderView {
  id: number
  followTaskId: number
  executionMode: string
  question: string
  outcome: string
  side: string
  orderType: string
  earlyExit?: boolean
  exitReason?: string | null
  status: string
  price: number
  size: number
  notionalUsdc: number
  realizedPnlUsdc: number
  estimatedFeeUsdc: number
  estimatedSlippageUsdc: number
  followProbability?: number | null
  calibratedFollowProbability?: number | null
  slippageSpikeProbability?: number | null
  estimateSampleSize?: number | null
  marketEndAtBjt?: string | null
  placedAt?: string | null
}

export interface FundingArbTimelinePoint {
  bucketAt: string
  netPnlUsdc: number
  cumulativeNetPnlUsdc: number
}
