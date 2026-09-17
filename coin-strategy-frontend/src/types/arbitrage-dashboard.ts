export type ArbitrageMode = 'CROSS_PRICE' | 'CROSS_FUNDING' | 'SINGLE_EXCHANGE' | 'HTX_TREND' | 'HTX_GRID'
export type ArbitrageStatus = 'QUALIFIED' | 'OBSERVING' | 'REJECTED'

export interface ArbitrageLeg {
  exchange: string
  contract: string
  funding: string
  interval: string
  countdown: string
  vwap: string
  volume: string
  oi: string
}

export interface ArbitrageOpportunity {
  id: string
  kind: ArbitrageMode
  symbol: string
  a: ArbitrageLeg
  b: ArbitrageLeg
  gross: string
  cost: string
  net: number
  netText: string
  annualized: string
  capacity: number
  capacityText: string
  basis: string
  historical: [string, string, string]
  status: ArbitrageStatus
  reason: string
  stable: string
  aDepth: Array<[string, string]>
  bDepth: Array<[string, string]>
}

export interface ArbitrageMarketHealth {
  exchange: string
  healthy: boolean
  success: number
  latency: number
  note: string
}

export interface ArbitrageDashboardSnapshot {
  asOf: string
  source: string
  partial: boolean
  missingExchanges: string[]
  coverage: { total: number; displayed: number; qualified: number; missingL2: number; stale: number; unavailable: number }
  health: ArbitrageMarketHealth[]
  opportunities: ArbitrageOpportunity[]
}

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

export interface HtxTrendDashboardResponse {
  asOf: string
  source: string
  summary: { totalPositions: number; activePositions: number; closedPositions: number; winningPositions: number; totalTurnover: number; totalFees: number; realizedProfit: number; netRealizedProfit: number }
  activePositions: TrendPosition[]
  recentPositions: TrendPosition[]
  dailyPnl: Array<{ tradeDate: string; closedPositions: number; realizedProfit: number; fees: number; netProfit: number }>
}
export interface TrendPosition { id: number; contractCode: string; status: string; side: string; contractVolume: number; entryAvgPrice: number | null; closeAvgPrice: number | null; entryFee: number; closeFee: number; realizedProfit: number | null; stopPrice: number | null; trailingStopPrice: number | null; entrySignal: string | null; exitReason: string | null; createdAt: string; updatedAt: string }
