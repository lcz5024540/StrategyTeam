export interface CursorPageView<T> {
  items: T[]
  nextCursor: string | null
  size: number
  hasMore: boolean
}

export interface WalletScreenerView {
  walletAddress: string
  alias: string | null
  archetype: string | null
  followLane: string | null
  screenerTier: 'EXECUTABLE_A' | 'EXECUTABLE_B' | 'OBSERVE' | 'BLOCKLIST' | string
  highQualityAddress: boolean | null
  highFrequencyTradingAddress: boolean | null
  winSourceType: string | null
  qualityScore: number | null
  replicabilityScore: number | null
  stabilityScore: number | null
  recentWinRate30d: number | null
  orderWinRate: number | null
  averageOrderRoiRate: number | null
  medianOrderRoiRate: number | null
  settlementWinRate: number | null
  followabilityRate: number | null
  concentrationMarket30d: number | null
  freshnessScore: number | null
  recommendedExitLane: string | null
  lastTradeAt: string | null
  summary: string | null
}

export interface WalletOrderAnalysisView {
  walletAddress: string
  marketId: string
  question: string | null
  outcome: string | null
  openedAt: string | null
  closedAt: string | null
  marketEndAt: number | null
  investedUsdc: number | null
  realizedPnlUsdc: number | null
  roiRate: number | null
  holdMinutes: number | null
  exitStyle: string | null
  exitLeadMinutes: number | null
  isWin: boolean | null
  sourceConfidence: number | null
}

export interface WalletDiagnosisView {
  walletAddress: string
  alias: string | null
  archetype: string | null
  followLane: string | null
  screenerTier: string | null
  highQualityAddress: boolean | null
  highFrequencyTradingAddress: boolean | null
  winSourceType: string | null
  qualityScore: number | null
  replicabilityScore: number | null
  stabilityScore: number | null
  settlementWinRate: number | null
  latencyAlphaRate: number | null
  swingAlphaRate: number | null
  medianHoldMinutes: number | null
  recommendedExitLane: string | null
  summary: string | null
  strengths: string[]
  risks: string[]
}

export interface SmartWalletProfileOverrideRequest {
  manualWatchEnabled?: boolean | null
  clearManualWatchEnabled?: boolean | null
  manualRecommendedExitLane?: string | null
  clearManualRecommendedExitLane?: boolean | null
  note?: string | null
}
