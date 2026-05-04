export interface Position {
  id: number
  executionAddress: string
  marketId: string
  tokenId: string
  question: string
  outcome: string
  marketEndAt: number
  marketEndAtBjt: string
  status: string
  quantity: number
  averageEntryPrice: number
  costBasisUsdc: number
  originalCostBasisUsdc: number
  feePolTotal?: number
  realizedPnlUsdc: number
  unrealizedPnlUsdc: number
  pendingSettlementNetPnlUsdc?: number
  claimableProceedsUsdc?: number
  lastMarkPrice: number
  netSide: string
  openedAt: string
  lastFilledAt: string
  closedAt?: string
}

export interface Order {
  id: string
  walletAddress?: string
  executionAddress: string
  marketId: string
  marketSlug?: string
  marketUrl?: string | null
  externalMarketUrl?: string | null
  internalMarketUrl?: string | null
  marketLinkStatus?: string | null
  tokenId?: string
  question: string
  outcome: string
  marketEndAt?: number | null
  marketEndAtBjt?: string | null
  marketLifecycle?: string | null
  activeMarket?: boolean | null
  closed?: boolean | null
  acceptingOrders?: boolean | null
  normalizedCategory?: string | null
  sourceCategory?: string | null
  side: string
  orderType?: string
  earlyExit?: boolean
  exitReason?: string
  notionalUsdc: number
  entryQuantity?: number
  entryAvgPrice?: number
  entryCostUsdc?: number
  roiBaseUsdc?: number
  roiAnomalyReason?: string | null
  costBasisUsdc?: number
  feeUsdc?: number
  tradingFeeUsdc?: number
  feePol?: number
  gasFeeUsdc?: number
  realizedPnlUsdc?: number
  status: string
  placedAt: string
  filledAt?: string
  model?: string
  preset?: string
}

export interface Trade {
  tradeId: number
  executionAddress: string
  marketId: string
  marketSlug?: string | null
  tokenId?: string | null
  question: string
  outcome: string
  status: string
  netSide?: string
  entryQuantity: number
  entryAvgPrice: number
  entryCostUsdc: number
  exitQuantity: number
  exitAvgPrice: number
  exitProceedsUsdc: number
  remainingQuantity: number
  realizedPnlUsdc: number
  unrealizedPnlUsdc: number
  totalPnlUsdc: number
  roiRate: number
  roiBaseUsdc?: number
  roiAnomalyReason?: string | null
  lastMarkPrice: number
  exitReason?: string | null
  entryOrderCount: number
  exitOrderCount: number
  entryOrderIds: number[]
  exitOrderIds: number[]
  marketEndAt?: number | null
  marketEndAtBjt?: string | null
  openedAt?: string
  lastFilledAt?: string
  closedAt?: string
}

export interface TradeOrder {
  id: number
  positionId: number
  marketInfoId?: number
  executionAddress: string
  walletAddress?: string | null
  sourceFollowTaskId?: number | null
  marketId: string
  marketSlug?: string | null
  marketUrl?: string | null
  externalMarketUrl?: string | null
  internalMarketUrl?: string | null
  marketLinkStatus?: string | null
  tokenId?: string | null
  question: string
  outcome: string
  marketEndAt?: number | null
  marketEndAtBjt?: string | null
  marketLifecycle?: string | null
  activeMarket?: boolean | null
  closed?: boolean | null
  acceptingOrders?: boolean | null
  normalizedCategory?: string | null
  sourceCategory?: string | null
  entryQuantity: number
  entryAvgPrice: number
  entryCostUsdc: number
  entryFeeUsdc: number
  openedAt?: string
  exitQuantity: number
  exitAvgPrice: number
  exitProceedsUsdc: number
  exitRealizedPnlUsdc: number
  exitFeeUsdc: number
  lastExitAt?: string | null
  remainingQuantity: number
  remainingCostBasisUsdc: number
  lastMarkPrice: number
  unrealizedPnlUsdc: number
  settlementPrice?: number | null
  settlementProceedsUsdc: number
  settlementPnlUsdc: number
  settledAt?: string | null
  totalRealizedPnlUsdc: number
  totalPnlUsdc: number
  roiRate: number
  status: string
  entryOrderIds: number[]
  exitOrderIds: number[]
  fillIds: number[]
}

export interface Fill {
  id: number
  orderId: string
  executionAddress: string
  marketId: string
  tokenId?: string
  question: string
  outcome: string
  side: string
  price: number
  size: number
  notionalUsdc: number
  feeUsdc: number
  tradingFeeUsdc?: number
  feePol?: number
  gasFeeUsdc?: number
  realizedPnlUsdc?: number
  filledAt: string
}
