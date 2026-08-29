export type ArbitrageMode = 'CROSS_PRICE' | 'CROSS_FUNDING' | 'SINGLE_EXCHANGE'
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
