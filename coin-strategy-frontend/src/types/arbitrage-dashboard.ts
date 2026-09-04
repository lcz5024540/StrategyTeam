export interface DashboardEnvelope<T> {
  asOf: string
  timezone: string
  source: string
  partial: boolean
  missingExchanges: string[]
  data: T
}

export interface ArbitrageOverview {
  executableOpportunities: number
  openPositions: number
  executableNotional: number
  expectedNetYield: number
  fundingPnl: number
  netDelta: number
  riskState: string
  evaluatedDirections: number
  rejectedDirections: number
  paperCapitalPerExchange: number
  maximumPaperLeverage: number
  maximumNotionalPerExchange: number
}

export interface ArbitrageOpportunity {
  id: number
  opportunityCode: string
  kind: string
  symbol: string
  longExchange: string
  shortExchange: string
  longFundingRate: number
  shortFundingRate: number
  fundingSpread: number
  estimatedNetYield: number
  executableNotional: number
  status: string
  rejectionReason?: string | null
  discoveredAt?: string | null
  updatedAt?: string | null
  longVwap?: number | null
  shortVwap?: number | null
  executableBasis?: number | null
  quoteAgeMs?: number | null
  longVolume24h?: number | null
  shortVolume24h?: number | null
  longOpenInterest?: number | null
  shortOpenInterest?: number | null
  funding24h?: number | null
  funding7d?: number | null
  funding30d?: number | null
}

export interface OpportunityPage {
  items: ArbitrageOpportunity[]
  total: number
  page: number
  size: number
  qualified: number
  rejected: number
  stale: number
}

export interface ArbitragePosition {
  positionCode: string
  kind: string
  symbol: string
  longExchange: string
  shortExchange: string
  longQuantity?: number | null
  shortQuantity?: number | null
  netDelta?: number | null
  notional?: number | null
  fundingPnl?: number | null
  basisPnl?: number | null
  status: string
  nextSettlementAt?: string | null
  openedAt?: string | null
  message?: string | null
}

export interface MarketHealth {
  exchange: string
  status: string
  successRate?: number | null
  p50LatencyMs?: number | null
  p95LatencyMs?: number | null
  quoteAgeMs?: number | null
  l2Completeness?: number | null
  lastError?: string | null
}

export interface DataQuality {
  discoveredContracts: number
  theoreticalDirections: number
  displayedDirections: number
  qualifiedDirections: number
  missingL2: number
  staleQuotes: number
  unavailableExchangeDirections: number
  exclusionReasons: Array<{ reason: string; count: number }>
}

export interface ArbitragePnlTimePoint {
  bucketAt: string
  fundingPnl: number
  tradingCashflowPnl: number
  fee: number
  netPnl: number
  cumulativeNetPnl: number
}

export interface ArbitrageExecutionFill {
  level: number
  price: number
  quantity: number
  notional: number
}

export interface ArbitrageExecutionOrder {
  clientOrderId: string
  exchange: string
  symbol: string
  side: string
  reduceOnly: boolean
  filledQuantity: number
  averagePrice: number
  bestPrice?: number | null
  worstPrice?: number | null
  impactBps?: number | null
  slippageAmount?: number | null
  feeAmount?: number | null
  levelsConsumed?: number | null
  bookReceivedAt?: string | null
  fills: ArbitrageExecutionFill[]
}

export type ArbitrageMode = 'CROSS_PRICE' | 'CROSS_FUNDING' | 'SINGLE_EXCHANGE'

export interface FundingRateMatrixRow {
  symbol: string
  ratesByExchange: Record<string, number | null>
  intervalHoursByExchange?: Record<string, number | null>
  longExchange: string | null
  shortExchange: string | null
  rawFundingSpread?: number | null
  settlementWindowHours?: number | null
  settlementWindowSpread?: number | null
  cadenceAdjusted?: boolean
  hourlyFundingSpread: number | null
  estimatedNetYield: number | null
  executableNotional: number | null
  status: string
  rejectionReason: string | null
  sampledAt: string | null
}

export interface FundingRateMatrixResponse {
  asOf: string
  timezone: string
  source: string
  partial: boolean
  missingExchanges: string[]
  data: {
    exchanges: string[]
    items: FundingRateMatrixRow[]
  }
}
