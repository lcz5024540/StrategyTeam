import { apiClient } from './client'
import type { HedgeDashboard, HedgePair, HedgeOrder, HedgeArbitrageLog, HedgeRiskExposure } from '@/types/hedge'

// 后端 /api/hedge/positions/open 和 /settling 返回 hedge_position 列表
// /api/hedge/positions/{id}/orders 返回该仓位的两笔订单
// 前端将其聚合为 HedgePair

async function fetchPairs(path: string): Promise<HedgePair[]> {
  const positions: HedgePairRaw[] = await apiClient.get(path)
  if (!positions || positions.length === 0) return []

  const pairs: HedgePair[] = await Promise.all(
    positions.map(async (pos) => {
      let yesOrder: HedgeOrder | null = null
      let noOrder: HedgeOrder | null = null
      try {
        const orders: HedgeOrder[] = await apiClient.get(`/api/hedge/positions/${pos.id}/orders`)
        yesOrder = orders.find((o) => o.side === 'YES') ?? null
        noOrder = orders.find((o) => o.side === 'NO') ?? null
      } catch {
        // 忽略单个订单请求失败
      }
      return mapToPair(pos, yesOrder, noOrder)
    })
  )
  return pairs
}

function mapToPair(pos: HedgePairRaw, yesOrder: HedgeOrder | null, noOrder: HedgeOrder | null): HedgePair {
  return {
    positionId: pos.id,
    marketId: pos.marketId ?? '',
    question: pos.question ?? '',
    yesEntryPrice: Number(pos.yesEntryPrice ?? 0),
    noEntryPrice: Number(pos.noEntryPrice ?? 0),
    probDeviation: Number(pos.probDeviation ?? 0),
    grossEdgeRate: Number(pos.grossEdgeRate ?? 0),
    quantity: Number(pos.quantity ?? 0),
    yesCostUsdc: Number(pos.yesCostUsdc ?? 0),
    noCostUsdc: Number(pos.noCostUsdc ?? 0),
    totalCostUsdc: Number(pos.totalCostUsdc ?? 0),
    expectedProfitUsdc: Number(pos.expectedProfitUsdc ?? 0),
    executionAddress: pos.executionAddress ?? '',
    status: pos.status ?? 'OPEN',
    finalOutcome: pos.finalOutcome ?? null,
    claimedUsdc: Number(pos.claimedUsdc ?? 0),
    feeUsdc: Number(pos.feeUsdc ?? 0),
    realizedPnlUsdc: Number(pos.realizedPnlUsdc ?? 0),
    marketEndAt: pos.marketEndAt ?? null,
    openedAt: pos.openedAt ?? null,
    settledAt: pos.settledAt ?? null,
    yesOrder,
    noOrder,
  }
}

// 原始后端返回结构（camelCase 字段）
interface HedgePairRaw {
  id: number
  marketId?: string
  question?: string
  yesEntryPrice?: number | string
  noEntryPrice?: number | string
  probDeviation?: number | string
  grossEdgeRate?: number | string
  quantity?: number | string
  yesCostUsdc?: number | string
  noCostUsdc?: number | string
  totalCostUsdc?: number | string
  expectedProfitUsdc?: number | string
  executionAddress?: string
  status?: 'OPEN' | 'SETTLING' | 'SETTLED' | 'CANCELLED'
  finalOutcome?: string | null
  claimedUsdc?: number | string
  feeUsdc?: number | string
  realizedPnlUsdc?: number | string
  marketEndAt?: number | null
  openedAt?: string | null
  settlingAt?: string | null
  settledAt?: string | null
}

export const hedgeApi = {
  getDashboard: (): Promise<HedgeDashboard> =>
    apiClient.get('/api/hedge/dashboard'),

  getOpenPairs: (): Promise<HedgePair[]> =>
    fetchPairs('/api/hedge/positions/open'),

  getSettlingPairs: (): Promise<HedgePair[]> =>
    fetchPairs('/api/hedge/positions/settling'),

  getPositionOrders: (positionId: number): Promise<HedgeOrder[]> =>
    apiClient.get(`/api/hedge/positions/${positionId}/orders`),

  getArbitrageLogs: (limit = 20): Promise<HedgeArbitrageLog[]> =>
    apiClient.get('/api/hedge/arbitrage-logs', { params: { limit } }),

  getRiskExposure: (): Promise<HedgeRiskExposure> =>
    apiClient.get('/api/hedge/risk/global-exposure'),
}
