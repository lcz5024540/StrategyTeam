import { apiClient } from './client'
import type { PositionMode } from '@/utils/constants'
import type {
  StrategyCapability,
  StrategyFactor,
  StrategyModeBehavior,
  StrategyOrderRow,
  StrategyOverview,
  StrategyPositionRow,
  StrategySummary,
  StrategyDetail,
  StrategyTimelinePoint,
} from '@/types/strategy'

type StrategyDetailApiResponse = Partial<StrategyDetail> & {
  totalOrders?: number
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
    tvlUsdc?: number
    todayPnlUsdc?: number
    capitalUsdc?: number
    volatility?: number
    sharpe?: number
    earlyExitOrderCount?: number
    earlyExitRealizedPnlUsdc?: number
    takeProfitOrderCount?: number
    takeProfitRealizedPnlUsdc?: number
    stopLossOrderCount?: number
    stopLossRealizedPnlUsdc?: number
  }
}

type StrategyOverviewApiResponse = Partial<StrategyOverview> & {
  strategies?: StrategySummaryApiResponse[]
}

type StrategySummaryApiResponse = Partial<StrategySummary> & {
  supportedModes?: string[]
  capabilities?: string[]
  modeBehavior?: string
}

function toNumber(value: unknown, fallback = 0): number {
  const num = Number(value)
  return Number.isFinite(num) ? num : fallback
}

function toOptionalNumber(value: unknown): number | undefined {
  if (value === undefined || value === null || value === '') return undefined
  const num = Number(value)
  return Number.isFinite(num) ? num : undefined
}

function normalizeDetail(detail: StrategyDetailApiResponse): StrategyDetail {
  const summary = detail?.summary || {}
  const metrics = detail?.metrics || {}

  return {
    code: String(detail?.code ?? summary.code ?? ''),
    name: String(detail?.name ?? summary.name ?? ''),
    description: String(detail?.description ?? summary.description ?? ''),
    tvlUsdc: toOptionalNumber(detail?.tvlUsdc ?? metrics.tvlUsdc),
    todayPnlUsdc: toOptionalNumber(detail?.todayPnlUsdc ?? metrics.todayPnlUsdc),
    capitalUsdc: toOptionalNumber(detail?.capitalUsdc ?? metrics.capitalUsdc),
    volatility: toOptionalNumber(detail?.volatility ?? metrics.volatility),
    sharpe: toOptionalNumber(detail?.sharpe ?? metrics.sharpe),
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
    earlyExitOrderCount: toNumber(detail?.earlyExitOrderCount ?? metrics.earlyExitOrderCount),
    earlyExitRealizedPnlUsdc: toNumber(detail?.earlyExitRealizedPnlUsdc ?? metrics.earlyExitRealizedPnlUsdc),
    takeProfitOrderCount: toNumber(detail?.takeProfitOrderCount ?? metrics.takeProfitOrderCount),
    takeProfitRealizedPnlUsdc: toNumber(
      detail?.takeProfitRealizedPnlUsdc ?? metrics.takeProfitRealizedPnlUsdc
    ),
    stopLossOrderCount: toNumber(detail?.stopLossOrderCount ?? metrics.stopLossOrderCount),
    stopLossRealizedPnlUsdc: toNumber(detail?.stopLossRealizedPnlUsdc ?? metrics.stopLossRealizedPnlUsdc),
    winRate: toNumber(detail?.winRate ?? metrics.winRate ?? summary.winRate),
    totalTrades: toNumber(detail?.totalTrades ?? detail?.totalOrders ?? metrics.totalOrders ?? summary.totalOrders),
    winTrades: toNumber(detail?.winTrades ?? metrics.winningPositions),
    lossTrades: toNumber(detail?.lossTrades ?? metrics.losingPositions),
  }
}

function normalizeSummary(summary: StrategySummaryApiResponse): StrategySummary {
  return {
    code: String(summary.code ?? ''),
    name: String(summary.name ?? ''),
    category: summary.category ? String(summary.category) : undefined,
    description: String(summary.description ?? ''),
    status: String(summary.status ?? 'PLANNED'),
    openPositions: toNumber(summary.openPositions),
    compositePnlUsdc: toNumber(summary.compositePnlUsdc),
    netPnlUsdc: toNumber(summary.netPnlUsdc),
    realizedPnlUsdc: toNumber(summary.realizedPnlUsdc),
    unrealizedPnlUsdc: toNumber(summary.unrealizedPnlUsdc),
    pendingSettlementPnlUsdc: toNumber(summary.pendingSettlementPnlUsdc),
    winRate: toNumber(summary.winRate),
    totalOrders: toNumber(summary.totalOrders),
    supportedModes: normalizeModes(summary.supportedModes),
    capabilities: normalizeCapabilities(summary.capabilities),
    modeBehavior: normalizeModeBehavior(summary.modeBehavior),
  }
}

function normalizeOverview(overview: StrategyOverviewApiResponse): StrategyOverview {
  const strategies = (overview.strategies || []).map((item) => normalizeSummary(item))
  return {
    generatedAtBjt: overview.generatedAtBjt,
    selectedStrategyCode: overview.selectedStrategyCode,
    strategies,
    totalNetPnlUsdc: strategies.reduce((sum, item) => sum + Number(item.netPnlUsdc || 0), 0),
    totalOpenPositions: strategies.reduce((sum, item) => sum + Number(item.openPositions || 0), 0),
  }
}

function normalizeModes(modes?: string[]): PositionMode[] {
  const normalized = (modes || [])
    .map((item) => String(item || '').toUpperCase())
    .filter((item): item is PositionMode => item === 'PAPER' || item === 'LIVE')
  return normalized.length > 0 ? normalized : ['PAPER', 'LIVE']
}

function normalizeCapabilities(capabilities?: string[]): StrategyCapability[] {
  const known = new Set(['POSITIONS', 'ORDERS', 'TIMELINE', 'TASKS', 'ATTRIBUTION', 'FACTORS', 'CONFLICTS'])
  return (capabilities || [])
    .map((item) => String(item || '').toUpperCase())
    .filter((item): item is StrategyCapability => known.has(item))
}

function normalizeModeBehavior(value?: string): StrategyModeBehavior {
  const normalized = String(value || '').toUpperCase()
  if (normalized === 'SHARED' || normalized === 'UNSUPPORTED') return normalized
  return 'ISOLATED'
}

export const strategyApi = {
  getOverview: (mode: PositionMode = 'PAPER'): Promise<StrategyOverview> =>
    apiClient
      .get('/strategy/api/strategy-platform/strategy/overview', { params: { mode } })
      .then((overview) => normalizeOverview((overview || {}) as StrategyOverviewApiResponse)),

  /** @deprecated 使用下方拆分接口替代 */
  getDetail: (code: string, mode: PositionMode = 'PAPER'): Promise<StrategyDetail> =>
    apiClient
      .get(`/strategy/api/strategy-platform/strategy/${code}`, { params: { mode } })
      .then((detail) => normalizeDetail((detail || {}) as StrategyDetailApiResponse)),

  // ── 拆分子接口 ──

  /** 策略概要（summary + metrics + insights），首屏快速加载 */
  getSummary: (code: string, mode: PositionMode = 'PAPER'): Promise<StrategyDetail> =>
    apiClient
      .get(`/strategy/api/strategy-platform/strategy/${code}/summary`, { params: { mode } })
      .then((detail) => normalizeDetail((detail || {}) as StrategyDetailApiResponse)),

  /** 当前持仓列表 */
  getPositions: (code: string, mode: PositionMode = 'PAPER') =>
    apiClient.get(`/strategy/api/strategy-platform/strategy/${code}/positions`, { params: { mode } }) as Promise<StrategyPositionRow[]>,

  /** 待结算持仓列表 */
  getNextSettlements: (code: string, limit = 8, mode: PositionMode = 'PAPER') =>
    apiClient.get(`/strategy/api/strategy-platform/strategy/${code}/next-settlements`, {
      params: { limit, mode },
    }) as Promise<StrategyPositionRow[]>,

  /** 最近订单列表 */
  getOrders: (code: string, limit = 30, mode: PositionMode = 'PAPER') =>
    apiClient.get(`/strategy/api/strategy-platform/strategy/${code}/orders`, {
      params: { limit, mode },
    }) as Promise<StrategyOrderRow[]>,

  /** 收益时间线 */
  getTimeline: (code: string, days = 30, mode: PositionMode = 'PAPER') =>
    apiClient.get(`/strategy/api/strategy-platform/strategy/${code}/timeline`, {
      params: { days, mode },
    }) as Promise<StrategyTimelinePoint[]>,

  /** 冲突市场列表 */
  getConflicts: (code: string, limit = 10, mode: PositionMode = 'PAPER') =>
    apiClient.get(`/strategy/api/strategy-platform/strategy/${code}/conflicts`, {
      params: { limit, mode },
    }),

  getFactors: (code: string, mode: PositionMode = 'PAPER', window = '30D'): Promise<StrategyFactor[]> =>
    apiClient.get(`/strategy/api/strategy-platform/strategy/${code}/factors`, {
      params: { mode, window },
    }),

  getFactor: (code: string, factorCode: string, mode: PositionMode = 'PAPER', window = '30D'): Promise<StrategyFactor> =>
    apiClient.get(`/strategy/api/strategy-platform/strategy/${code}/factors/${factorCode}`, {
      params: { mode, window },
    }),
}
