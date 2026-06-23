export interface CursorPageView<T> {
  items: T[]
  nextCursor: string | null
  size: number
  hasMore: boolean
}

export interface OffsetPageView<T> {
  items: T[]
  page: number
  pageSize: number
  totalItems: number
  totalPages: number
}

export interface WalletScreenerView {
  walletAddress: string
  alias: string | null
  archetype: string | null
  followLane: string | null
  archetypeReason: string | null
  archetypeConfidence: number | null
  screenerTier: 'EXECUTABLE_A' | 'EXECUTABLE_B' | 'OBSERVE' | 'BLOCKLIST' | string
  highQualityAddress: boolean | null
  highFrequencyTradingAddress: boolean | null
  winSourceType: string | null
  qualityScore: number | null
  replicabilityScore: number | null
  stabilityScore: number | null
  recentWinRate7d: number | null
  recentWinRate30d: number | null
  dedupOrderCount7d: number | null
  dedupOrderCount30d: number | null
  medianHoldMinutes: number | null
  dataQuality: string | null
  orderWinRate: number | null
  averageOrderRoiRate: number | null
  medianOrderRoiRate: number | null
  settlementWinRate: number | null
  followabilityRate: number | null
  concentrationMarket30d: number | null
  freshnessScore: number | null
  monitorTier: 'HOT' | 'WARM' | 'COLD' | string | null
  monitorReason: string | null
  recommendedExitLane: string | null
  lastTradeAt: string | null
  summary: string | null
  behaviorComputedAt: string | null
  behaviorStale: boolean | null
  fallbackUsed: boolean | null
}

export interface SmartWalletManualAllowlistView {
  id: number
  walletAddress: string
  source: string
  status: string
  allowedExecutionModes: string
  stakeMultiplier: number | null
  maxLiveOrdersPerDay: number | null
  maxLiveOrdersPerRun: number | null
  expiresAt: string | null
  reason: string | null
  createdBy: string | null
  active: boolean
  createdAt: string | null
  updatedAt: string | null
}

export interface SmartWalletManualBlocklistView {
  id: number
  walletAddress: string
  source: string
  status: string
  reason: string | null
  createdBy: string | null
  active: boolean
  createdAt: string | null
  updatedAt: string | null
}

export interface WalletOrderAnalysisView {
  walletAddress: string
  marketId: string
  question: string | null
  outcome: string | null
  openedAt: string | null
  closedAt: string | null
  settlementAt: string | null
  marketEndAt: number | null
  investedUsdc: number | null
  realizedPnlUsdc: number | null
  roiRate: number | null
  holdMinutes: number | null
  exitStyle: string | null
  exitLeadMinutes: number | null
  isWin: boolean | null
  sourceConfidence: number | null
  pendingSettlement: boolean | null
  earlySettlement: boolean | null
}

export interface WalletOrderStatsView {
  walletAddress: string
  window: string
  generatedAt: string | null
  closedOrderCount: number
  winCount: number
  lossCount: number
  drawCount: number
  closedWinRate: number | null
  closedRealizedPnlUsdc: number | null
  activeUnrealizedPnlUsdc: number | null
  netPnlUsdc: number | null
  averageRoiRate: number | null
  activePositionCount: number
  pendingSettlementCount: number
  earlySettlementRate: number | null
  settlementWinRate: number | null
}

export interface WalletDiagnosisView {
  walletAddress: string
  alias: string | null
  archetype: string | null
  followLane: string | null
  archetypeReason: string | null
  archetypeConfidence: number | null
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
  earlySettlementRate: number | null
  dedupOrderCount7d: number | null
  dedupOrderCount30d: number | null
  medianHoldMinutes: number | null
  dataQuality: string | null
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

export interface SmartWalletMarketMonitorItem {
  marketId: string
  question: string | null
  marketSlug: string | null
  outcomePrices: string | null
  marketEndAt: number | null
  marketEndAtBjt: string | null
  marketState: string
  marketLifecycle: string
  sourceCategory: string | null
  normalizedCategory: string | null
  lastMetadataSyncAt: string | null
  metadataStale: boolean
  lastSignalAt: string | null
  lastSignalAtBjt: string | null
  signalCount: number
  uniqueWalletCount: number
  pendingTaskCount: number
  openPositionCount: number
  openCostUsdc: number
  positionDirection: string
  positionDirectionCode: string
  realizedPnlUsdc: number
  unrealizedPnlUsdc: number
  activeMarket: boolean
  effectiveMarket: boolean
  concentrationAvailable: boolean
  observedHolders: number
  top1Pct: number
  top3Pct: number
  top5Pct: number
  hhi: number
  highlyConcentrated: boolean
  extremelyConcentrated: boolean
  concentrationBand: 'HEALTHY' | 'HIGH' | 'EXTREME' | 'UNKNOWN' | string
  lastConcentrationSnapshotAt: string | null
  concentrationStale: boolean
  topHolderAddresses: string[]
}

export interface SmartWalletMarketMonitorTrendPoint {
  date: string
  monitoredMarketCount: number
  effectiveMarketCount: number
  concentratedMarketCount: number
  extremeConcentratedMarketCount: number
  signalCount: number
  pendingTaskCount: number
}

export interface SmartWalletMarketMonitoringSummary {
  generatedAt: string
  lookbackDays: number
  trendDays: number
  monitoredMarketCount: number
  activeMarketCount: number
  effectiveMarketCount: number
  concentratedMarketCount: number
  extremeConcentratedMarketCount: number
  openPositionMarketCount: number
  pendingTaskMarketCount: number
  signalDrivenMarketCount: number
  concentrationCoverageRate: number
  trend: SmartWalletMarketMonitorTrendPoint[]
  items: SmartWalletMarketMonitorItem[]
}
