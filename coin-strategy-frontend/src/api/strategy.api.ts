import { apiClient } from './client'
import type { PositionMode } from '@/utils/constants'
import type { StrategyOverview, StrategyDetail } from '@/types/strategy'

type StrategyDetailApiResponse = Partial<StrategyDetail> & {
  summary?: {
    code?: string
    name?: string
    description?: string
    openPositions?: number
    winRate?: number
    totalOrders?: number
  }
  metrics?: {
    totalOrders?: number
    openPositions?: number
    closedPositions?: number
    winningPositions?: number
    losingPositions?: number
    winRate?: number
    realizedPnlUsdc?: number
    unrealizedPnlUsdc?: number
    pendingSettlementPnlUsdc?: number
    compositePnlUsdc?: number
    netPnlUsdc?: number
    maxDrawdownUsdc?: number
    openConflictingMarkets?: number
    remainingPositionSlots?: number
    remainingCapitalBudgetUsdc?: number
    availableCapitalUsdc?: number
    lockedClaimProceedsUsdc?: number
  }
}

function toNumber(value: unknown, fallback = 0): number {
  const num = Number(value)
  return Number.isFinite(num) ? num : fallback
}

function normalizeDetail(detail: StrategyDetailApiResponse): StrategyDetail {
  const summary = detail?.summary || {}
  const metrics = detail?.metrics || {}

  return {
    code: String(detail?.code ?? summary.code ?? ''),
    name: String(detail?.name ?? summary.name ?? ''),
    description: String(detail?.description ?? summary.description ?? ''),
    netPnlUsdc: toNumber(detail?.netPnlUsdc ?? metrics.netPnlUsdc),
    compositePnlUsdc: toNumber(
      detail?.compositePnlUsdc ?? metrics.compositePnlUsdc ?? detail?.netPnlUsdc ?? metrics.netPnlUsdc
    ),
    realizedPnlUsdc: toNumber(detail?.realizedPnlUsdc ?? metrics.realizedPnlUsdc),
    unrealizedPnlUsdc: toNumber(detail?.unrealizedPnlUsdc ?? metrics.unrealizedPnlUsdc),
    pendingSettlementPnlUsdc: toNumber(
      detail?.pendingSettlementPnlUsdc ??
        metrics.pendingSettlementPnlUsdc ??
        detail?.unrealizedPnlUsdc ??
        metrics.unrealizedPnlUsdc
    ),
    openPositions: toNumber(detail?.openPositions ?? metrics.openPositions ?? summary.openPositions),
    closedPositions: toNumber(detail?.closedPositions ?? metrics.closedPositions),
    openConflictingMarkets: toNumber(detail?.openConflictingMarkets ?? metrics.openConflictingMarkets),
    maxDrawdownUsdc: toNumber(detail?.maxDrawdownUsdc ?? metrics.maxDrawdownUsdc),
    remainingPositionSlots: toNumber(detail?.remainingPositionSlots ?? metrics.remainingPositionSlots),
    remainingCapitalBudgetUsdc: toNumber(detail?.remainingCapitalBudgetUsdc ?? metrics.remainingCapitalBudgetUsdc),
    availableCapitalUsdc: toNumber(
      detail?.availableCapitalUsdc ??
        metrics.availableCapitalUsdc ??
        detail?.remainingCapitalBudgetUsdc ??
        metrics.remainingCapitalBudgetUsdc
    ),
    lockedClaimProceedsUsdc: toNumber(detail?.lockedClaimProceedsUsdc ?? metrics.lockedClaimProceedsUsdc),
    winRate: toNumber(detail?.winRate ?? metrics.winRate ?? summary.winRate),
    totalTrades: toNumber(detail?.totalTrades ?? detail?.totalOrders ?? metrics.totalOrders ?? summary.totalOrders),
    winTrades: toNumber(detail?.winTrades ?? metrics.winningPositions),
    lossTrades: toNumber(detail?.lossTrades ?? metrics.losingPositions),
  }
}

export const strategyApi = {
  getOverview: (mode: PositionMode = 'PAPER'): Promise<StrategyOverview> =>
    apiClient.get('/strategy-platform/strategy/overview', { params: { mode } }),

  getDetail: (code: string, mode: PositionMode = 'PAPER'): Promise<StrategyDetail> =>
    apiClient
      .get(`/strategy-platform/strategy/${code}`, { params: { mode } })
      .then((detail) => normalizeDetail((detail || {}) as StrategyDetailApiResponse)),
}
