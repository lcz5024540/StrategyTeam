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
  executionAddress: string
  marketId: string
  question: string
  outcome: string
  side: string
  orderType?: string
  earlyExit?: boolean
  exitReason?: string
  notionalUsdc: number
  realizedPnlUsdc?: number
  status: string
  placedAt: string
  filledAt?: string
  model?: string
  preset?: string
}

export interface Fill {
  id: number
  orderId: string
  executionAddress: string
  marketId: string
  question: string
  outcome: string
  side: string
  price: number
  size: number
  notionalUsdc: number
  feeUsdc: number
  filledAt: string
}
