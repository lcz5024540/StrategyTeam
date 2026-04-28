import type { PositionMode } from '@/utils/constants'

export type StrategyCapability =
  | 'POSITIONS'
  | 'ORDERS'
  | 'TIMELINE'
  | 'TASKS'
  | 'ATTRIBUTION'
  | 'FACTORS'
  | 'CONFLICTS'

export type StrategyModeBehavior = 'ISOLATED' | 'SHARED' | 'UNSUPPORTED'

export interface StrategySummary {
  code: string
  name: string
  category?: string
  description: string
  status: string
  openPositions: number
  compositePnlUsdc?: number
  netPnlUsdc: number
  realizedPnlUsdc?: number
  unrealizedPnlUsdc?: number
  pendingSettlementPnlUsdc?: number
  winRate: number
  totalOrders?: number
  supportedModes: PositionMode[]
  capabilities: StrategyCapability[]
  modeBehavior: StrategyModeBehavior
}

export interface StrategyRegistryItem {
  code: string
  name: string
  category?: string
  description: string
  status: string
  supportedModes: PositionMode[]
  capabilities: StrategyCapability[]
  modeBehavior: StrategyModeBehavior
  openPositions: number
  netPnlUsdc: number
  winRate: number
  totalOrders: number
}

export interface StrategyOverview {
  strategies: StrategySummary[]
  generatedAtBjt?: string
  selectedStrategyCode?: string
  totalNetPnlUsdc?: number
  totalOpenPositions?: number
}

export interface StrategyDetail {
  code: string
  name: string
  description: string
  tvlUsdc?: number
  todayPnlUsdc?: number
  capitalUsdc?: number
  volatility?: number
  sharpe?: number
  // 收益指标
  netPnlUsdc: number
  compositePnlUsdc: number
  realizedPnlUsdc: number
  unrealizedPnlUsdc: number
  pendingSettlementPnlUsdc: number
  // 持仓指标
  openPositions: number
  closedPositions: number
  openConflictingMarkets: number
  // 风险指标
  maxDrawdownUsdc: number
  remainingPositionSlots: number
  remainingCapitalBudgetUsdc: number
  availableCapitalUsdc: number
  lockedClaimProceedsUsdc: number
  earlyExitOrderCount: number
  earlyExitRealizedPnlUsdc: number
  takeProfitOrderCount: number
  takeProfitRealizedPnlUsdc: number
  stopLossOrderCount: number
  stopLossRealizedPnlUsdc: number
  // 胜率
  winRate: number
  totalTrades: number
  winTrades: number
  lossTrades: number
}

export interface StrategyDashboardMetrics {
  code: string
  name: string
  description: string
  tvlUsdc?: number
  todayPnlUsdc?: number
  capitalUsdc?: number
  netPnlUsdc: number
  realizedPnlUsdc: number
  pendingSettlementPnlUsdc: number
  totalOrders: number
  openPositions: number
  closedPositions: number
  winRate: number
  winTrades: number
  lossTrades: number
  maxDrawdownUsdc: number
}

export interface StrategyPositionRow {
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

export interface StrategyOrderRow {
  id: number
  followTaskId?: number | null
  executionMode?: string | null
  question: string
  outcome: string
  side: string
  orderType?: string | null
  earlyExit?: boolean
  exitReason?: string | null
  status: string
  price: number
  size: number
  notionalUsdc: number
  costBasisUsdc?: number
  realizedPnlUsdc?: number
  estimatedFeeUsdc?: number
  estimatedSlippageUsdc?: number
  followProbability?: number | null
  calibratedFollowProbability?: number | null
  slippageSpikeProbability?: number | null
  estimateSampleSize?: number | null
  marketEndAtBjt?: string | null
  placedAt?: string | null
}

export interface StrategyOperationalSummary {
  positionCount: number
  orderCount: number
  exposureUsdc: number
  realizedPnlUsdc: number
  unrealizedPnlUsdc: number
  pendingPnlUsdc: number
  orderNotionalUsdc: number
}

export interface StrategyModuleView {
  code: string
  title: string
  strategyCode: string
  status: 'READY' | 'EMPTY' | 'UNSUPPORTED'
  description?: string
  metadata?: Record<string, unknown>
}

export interface StrategyTimelinePoint {
  bucketAt: string
  netPnlUsdc: number
  cumulativeNetPnlUsdc: number
}

export interface StrategyFactor {
  code: string
  name: string
  category: string
  status: string
  direction: 'POSITIVE' | 'NEGATIVE' | string
  weight: number
  updatedAtBjt: string
  value: number
  displayValue: string
  score: number
  signal: string
  ic: number
  rankIc: number
  ir: number
  coverage: number
  sampleSize: number
  metadata: Record<string, unknown>
}
