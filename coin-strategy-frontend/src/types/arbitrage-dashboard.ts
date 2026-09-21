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
  summary: TrendSummary
  ledgerSummary?: TrendLedgerSummary
  performance?: TrendPerformanceSummary
  runtime: TrendRuntimeSnapshot
  accountSummaries: TrendAccountSummary[]
  activePositions: TrendPosition[]
  recentPositions: TrendPosition[]
  dailyPnl: TrendDailyPnl[]
  sidePnl: TrendDimensionPnl[]
  todaySidePnl: TrendDimensionPnl[]
  exitReasonPnl: TrendDimensionPnl[]
  recentOrders: TrendOrder[]
  recentLedgerEvents?: TrendLedgerEvent[]
  recentSignals: TrendSignal[]
  signalRollup: TrendSignalRollup[]
  recentCandidateSignals: TrendCandidateSignal[]
}

export interface TrendSummary {
  totalPositions: number
  activePositions: number
  closedPositions: number
  winningPositions: number
  todayClosedPositions: number
  todayWinningPositions: number
  totalTurnover: number
  todayTurnover: number
  totalFees: number
  todayFees: number
  realizedProfit: number
  todayRealizedProfit: number
  netRealizedProfit: number
  todayNetRealizedProfit: number
}


export interface TrendLedgerSummary {
  ledgerEventCount: number
  ledgerTotalTurnover: number
  ledgerTodayTurnover: number
  ledgerTotalFees: number
  ledgerTodayFees: number
  ledgerNetRealizedProfit: number
  ledgerTodayNetRealizedProfit: number
  latestLedgerAt: string | null
}

export interface TrendPerformanceSummary {
  closedPositions: number
  winningPositions: number
  losingPositions: number
  winRate: number
  averageWin: number
  averageLoss: number
  payoffRatio: number
  profitFactor: number
  expectancy: number
  netRealizedProfit: number
  todayClosedPositions: number
  todayWinningPositions: number
  todayLosingPositions: number
  todayWinRate: number
  todayAverageWin: number
  todayAverageLoss: number
  todayPayoffRatio: number
  todayProfitFactor: number
  todayExpectancy: number
  todayNetRealizedProfit: number
}

export interface TrendAccountSummary {
  accountLabel: string | null
  accountRole: string | null
  accountEnabled: boolean
  trendEnabled: boolean
  mirrorEnabled: boolean
  totalPositions: number
  activePositions: number
  closedPositions: number
  winningPositions: number
  todayClosedPositions: number
  todayWinningPositions: number
  totalTurnover: number
  todayTurnover: number
  totalFees: number
  todayFees: number
  realizedProfit: number
  todayRealizedProfit: number
  netRealizedProfit: number
  todayNetRealizedProfit: number
  totalOrders: number
  todayOrders: number
  openOrders: number
  entryOrders: number
  closeOrders: number
  orderNotional: number
  todayOrderNotional: number
  orderFees: number
  todayOrderFees: number
  estimatedOrderFees: number
  todayEstimatedOrderFees: number
  orderRealizedProfit: number
  todayOrderRealizedProfit: number
  ledgerEventCount?: number
  ledgerTotalTurnover?: number
  ledgerTodayTurnover?: number
  ledgerTotalFees?: number
  ledgerTodayFees?: number
  ledgerNetRealizedProfit?: number
  ledgerTodayNetRealizedProfit?: number
  latestLedgerAt?: string | null
  latestOrderAt: string | null
  latestPositionAt: string | null
}

export interface TrendRuntimeSnapshot {
  latestSignalAt: string | null
  latestOrderAt: string | null
  latestPositionAt: string | null
  latestCandidateAt: string | null
  signalsLast30m: number
  scannedContractsLast30m: number
  longSignalsLast30m: number
  shortSignalsLast30m: number
  flatSignalsLast30m: number
  candidateSignalsLast30m: number
  candidateLongLast30m: number
  candidateShortLast30m: number
  candidateFlatLast30m: number
}

export interface TrendPosition {
  id: number
  contractCode: string
  status: string
  side: string
  contractVolume: number
  entryFilledContracts: number
  closeFilledContracts: number
  entryAvgPrice: number | null
  closeAvgPrice: number | null
  entryFee: number
  closeFee: number
  realizedProfit: number | null
  netRealizedProfit: number | null
  turnover: number | null
  stopPrice: number | null
  trailingStopPrice: number | null
  entrySignal: string | null
  exitReason: string | null
  message: string | null
  accountLabel: string | null
  entryFeeRate: number | null
  estimatedEntryFee: number | null
  estimatedCloseFee: number | null
  expectedNetAfterFees: number | null
  sizingMultiplier: number | null
  sizingReason: string | null
  entryGateReason: string | null
  createdAt: string
  updatedAt: string
}

export interface TrendDailyPnl {
  tradeDate: string
  closedPositions: number
  realizedProfit: number
  fees: number
  netProfit: number
  turnover: number
}

export interface TrendDimensionPnl {
  bucket: string | null
  side: string | null
  closedPositions: number
  winningPositions: number
  realizedProfit: number
  fees: number
  netProfit: number
  turnover: number
}

export interface TrendOrder {
  id: number
  positionId: number
  contractCode: string
  leg: string
  trendSide: string
  orderSide: string
  positionSide: string
  reduceOnly: boolean
  clientOrderId: string
  exchangeOrderId: number | null
  requestVolume: number
  filledContracts: number
  averagePrice: number | null
  fee: number
  realizedProfit: number | null
  notional: number | null
  exchangeState: string | null
  accountLabel: string | null
  feeRateUsed: number | null
  estimatedFee: number | null
  estimatedOpenFee: number | null
  estimatedCloseFee: number | null
  sizingMultiplier: number | null
  sizingReason: string | null
  entryGateReason: string | null
  status: string
  reason: string | null
  message: string | null
  createdAt: string
  updatedAt: string
}

export interface TrendLedgerEvent {
  id: number
  eventKey: string | null
  eventType: string | null
  accountLabel: string | null
  contractCode: string | null
  positionId: number
  orderId: number | null
  clientOrderId: string | null
  side: string | null
  executionPriceUsdt: number | null
  entryPriceUsdt: number | null
  closePriceUsdt: number | null
  filledContracts: number | null
  contractSize: number | null
  amountUsdt: number | null
  feeUsdt: number | null
  grossPnlUsdt: number | null
  netPnlAfterFee: number | null
  reason: string | null
  occurredAt: string | null
}

export interface TrendSignal {
  id: number
  contractCode: string
  period: string
  barTime: number | null
  trendSignal: string
  closePrice: number | null
  emaFast: number | null
  emaSlow: number | null
  breakoutHigh: number | null
  breakoutLow: number | null
  atr: number | null
  atrPct: number | null
  adx: number | null
  spreadRate: number | null
  fundingRate: number | null
  reason: string | null
  createdAt: string
}

export interface TrendSignalRollup {
  trendSignal: string
  reasonBucket: string | null
  signals: number
  contracts: number
  avgAdx: number
  avgAtrPct: number
  latestAt: string | null
}

export interface TrendCandidateSignal {
  id: number
  contractCode: string
  period: string
  barTime: number | null
  direction: string
  regime: string
  signalScore: number
  longScore: number
  shortScore: number
  scoreGap: number
  closePrice: number | null
  atrPct: number | null
  adx: number | null
  momentumPct: number | null
  volumeRatio: number | null
  entryReason: string | null
  invalidationReason: string | null
  modelVersion: string | null
  createdAt: string
}
