import { STRATEGY_CATALOG } from '@/utils/constants'
import type { PositionMode } from '@/utils/constants'
import type {
  StrategyCapability,
  StrategyDashboardMetrics,
  StrategyDetail,
  StrategyOperationalSummary,
  StrategyOrderRow,
  StrategyPositionRow,
  StrategyRegistryItem,
  StrategySummary,
} from '@/types/strategy'

function toNumber(value: unknown, fallback?: number): number {
  const num = Number(value)
  return Number.isFinite(num) ? num : Number(fallback || 0)
}

function firstNumber(...values: unknown[]): number | undefined {
  for (const value of values) {
    if (value === undefined || value === null || value === '') continue
    const num = Number(value)
    if (Number.isFinite(num)) return num
  }
  return undefined
}

function catalogRegistryItem(item: typeof STRATEGY_CATALOG[number]): StrategyRegistryItem {
  return {
    code: item.code,
    name: item.name,
    description: item.description,
    status: item.status,
    supportedModes: [...item.supportedModes] as PositionMode[],
    capabilities: [...item.capabilities] as StrategyCapability[],
    modeBehavior: item.modeBehavior,
    openPositions: 0,
    netPnlUsdc: 0,
    winRate: 0,
    totalOrders: 0,
  }
}

export function toRegistryItem(summary: StrategySummary, fallback?: StrategyRegistryItem): StrategyRegistryItem {
  return {
    code: summary.code || fallback?.code || '',
    name: summary.name || fallback?.name || summary.code || '',
    category: summary.category || fallback?.category,
    description: summary.description || fallback?.description || '',
    status: summary.status || fallback?.status || 'PLANNED',
    supportedModes: summary.supportedModes?.length ? summary.supportedModes : fallback?.supportedModes || ['PAPER', 'LIVE'],
    capabilities: summary.capabilities?.length ? summary.capabilities : fallback?.capabilities || [],
    modeBehavior: summary.modeBehavior || fallback?.modeBehavior || 'ISOLATED',
    openPositions: toNumber(summary.openPositions, fallback?.openPositions),
    netPnlUsdc: toNumber(summary.netPnlUsdc, fallback?.netPnlUsdc),
    winRate: toNumber(summary.winRate, fallback?.winRate),
    totalOrders: toNumber(summary.totalOrders, fallback?.totalOrders),
  }
}

export function toRegistryItems(remote: StrategySummary[] = []): StrategyRegistryItem[] {
  const map = new Map<string, StrategyRegistryItem>()
  STRATEGY_CATALOG.forEach((item) => map.set(item.code, catalogRegistryItem(item)))
  remote.forEach((item) => {
    const fallback = map.get(item.code)
    map.set(item.code, toRegistryItem(item, fallback))
  })
  return Array.from(map.values())
}

export function isEnterableStrategy(strategy?: Pick<StrategyRegistryItem, 'status' | 'modeBehavior'>) {
  return strategy?.status === 'ACTIVE' && strategy.modeBehavior !== 'UNSUPPORTED'
}

export function hasCapability(
  strategy: Pick<StrategyRegistryItem, 'capabilities'> | undefined,
  capability: StrategyCapability
) {
  return Boolean(strategy?.capabilities?.includes(capability))
}

export function toDashboardMetrics(detail: StrategyDetail | undefined): StrategyDashboardMetrics | undefined {
  if (!detail) return undefined

  return {
    code: detail.code,
    name: detail.name,
    description: detail.description,
    tvlUsdc: firstNumber(detail.tvlUsdc, detail.capitalUsdc, detail.availableCapitalUsdc),
    todayPnlUsdc: firstNumber(detail.todayPnlUsdc),
    capitalUsdc: firstNumber(detail.capitalUsdc, detail.availableCapitalUsdc),
    netPnlUsdc: toNumber(detail.netPnlUsdc),
    realizedPnlUsdc: toNumber(detail.realizedPnlUsdc),
    pendingSettlementPnlUsdc: toNumber(detail.pendingSettlementPnlUsdc),
    totalOrders: toNumber(detail.totalTrades),
    openPositions: toNumber(detail.openPositions),
    closedPositions: toNumber(detail.closedPositions),
    winRate: toNumber(detail.winRate),
    winTrades: toNumber(detail.winTrades),
    lossTrades: toNumber(detail.lossTrades),
    maxDrawdownUsdc: toNumber(detail.maxDrawdownUsdc),
  }
}

export function toOperationalSummary(
  positions: StrategyPositionRow[] = [],
  orders: StrategyOrderRow[] = []
): StrategyOperationalSummary {
  return {
    positionCount: positions.length,
    orderCount: orders.length,
    exposureUsdc: positions.reduce((sum, row) => sum + toNumber(row.costBasisUsdc), 0),
    realizedPnlUsdc: positions.reduce((sum, row) => sum + toNumber(row.realizedPnlUsdc), 0),
    unrealizedPnlUsdc: positions.reduce((sum, row) => sum + toNumber(row.unrealizedPnlUsdc), 0),
    pendingPnlUsdc: positions.reduce((sum, row) => sum + toNumber(row.pendingSettlementNetPnlUsdc), 0),
    orderNotionalUsdc: orders.reduce((sum, row) => sum + toNumber(row.notionalUsdc), 0),
  }
}
