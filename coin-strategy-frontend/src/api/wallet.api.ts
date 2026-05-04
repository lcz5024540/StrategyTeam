import { apiClient } from './client'
import type { PositionMode } from '@/utils/constants'
import type { Position, Order, Fill, Trade, TradeOrder } from '@/types/position'

/** 游标分页响应 */
export interface CursorPage<T> {
  items: T[]
  nextCursor: string | null
  size: number
  hasMore: boolean
}

export interface OrderPageParams {
  pnl?: 'ALL' | 'PROFIT' | 'LOSS' | 'ZERO'
  settlement?: 'ALL' | 'SETTLED' | 'UNSETTLED' | 'EARLY_EXIT'
  status?: string
  q?: string
}

export interface MarketLinkView {
  conditionId: string
  question?: string | null
  outcome?: string | null
  marketSlug?: string | null
  marketUrl?: string | null
  externalMarketUrl?: string | null
  internalMarketUrl?: string | null
  linkStatus?: string | null
  metadataSource?: string | null
  category?: string | null
  marketEndAt?: number | null
  active?: boolean | null
  closed?: boolean | null
  acceptingOrders?: boolean | null
  resolutionStatus?: string | null
  resolvedOutcome?: string | null
  winningTokenId?: string | null
  outcomes?: string[]
  outcomeTokenIds?: Record<string, string>
}

export const walletApi = {
  getPositionsLive: (mode?: PositionMode): Promise<Position[]> =>
    apiClient.get('/strategy/api/smart-wallet/positions/live', { params: { mode } }),

  getNextSettlements: (limit = 10, mode?: PositionMode): Promise<Position[]> =>
    apiClient.get('/strategy/api/smart-wallet/positions/next-settlements', { params: { limit, mode } }),

  getPositionsHistory: (closedOnly = false, mode?: PositionMode): Promise<Position[]> =>
    apiClient.get('/strategy/api/smart-wallet/positions/history', { params: { closedOnly, mode } }),

  /** @deprecated 使用 getOrdersPage 替代 */
  getOrders: (limit = 100, mode?: PositionMode): Promise<Order[]> =>
    apiClient.get('/strategy/api/smart-wallet/orders', { params: { limit, mode } }),

  /** 游标分页查询订单，首页不传 lastId，后续传 nextCursor */
  getOrdersPage: (
    limit = 50,
    lastId?: number | null,
    mode?: PositionMode,
    filters: OrderPageParams = {},
  ): Promise<CursorPage<Order>> =>
    apiClient.get('/strategy/api/smart-wallet/orders/page', {
      params: {
        limit,
        lastId: lastId ?? undefined,
        mode,
        pnl: filters.pnl && filters.pnl !== 'ALL' ? filters.pnl : undefined,
        settlement: filters.settlement && filters.settlement !== 'ALL' ? filters.settlement : undefined,
        status: filters.status || undefined,
        q: filters.q || undefined,
      },
    }),

  getTradesPage: (
    limit = 50,
    lastId?: number | null,
    mode?: PositionMode,
    filters: OrderPageParams = {},
  ): Promise<CursorPage<Trade>> =>
    apiClient.get('/strategy/api/smart-wallet/trades/page', {
      params: {
        limit,
        lastId: lastId ?? undefined,
        mode,
        pnl: filters.pnl && filters.pnl !== 'ALL' ? filters.pnl : undefined,
        settlement: filters.settlement && filters.settlement !== 'ALL' ? filters.settlement : undefined,
        status: filters.status || undefined,
        q: filters.q || undefined,
      },
    }),

  getTradeOrdersPage: (
    limit = 50,
    lastId?: number | null,
    mode?: PositionMode,
    filters: OrderPageParams = {},
  ): Promise<CursorPage<TradeOrder>> =>
    apiClient.get('/strategy/api/smart-wallet/trade-orders/page', {
      params: {
        limit,
        lastId: lastId ?? undefined,
        mode,
        pnl: filters.pnl && filters.pnl !== 'ALL' ? filters.pnl : undefined,
        settlement: filters.settlement && filters.settlement !== 'ALL' ? filters.settlement : undefined,
        status: filters.status || undefined,
        q: filters.q || undefined,
      },
    }),

  getFills: (limit = 100, mode?: PositionMode): Promise<Fill[]> =>
    apiClient.get('/strategy/api/smart-wallet/fills', { params: { limit, mode } }),

  getMarketLink: (conditionId: string, question?: string | null, outcome?: string | null): Promise<MarketLinkView> =>
    apiClient.get(`/strategy/api/smart-wallet/polymarket/markets/${encodeURIComponent(conditionId)}/link`, {
      params: {
        question: question || undefined,
        outcome: outcome || undefined,
      },
    }),

  getClobMarket: (conditionId: string): Promise<any> =>
    apiClient.get(`/strategy/api/smart-wallet/clob/markets/${encodeURIComponent(conditionId)}`),

  getMarketPriceReference: (
    conditionId: string,
    outcome?: string | null,
    tokenId?: string | null,
    fallbackPrice?: number | null,
    question?: string | null,
  ): Promise<Record<string, any>> =>
    apiClient.get(`/strategy/api/smart-wallet/clob/markets/${encodeURIComponent(conditionId)}/price-reference`, {
      params: {
        outcome: outcome || undefined,
        tokenId: tokenId || undefined,
        fallbackPrice: fallbackPrice ?? undefined,
        question: question || undefined,
      },
    }),

  exportOrdersCsv: (limit = 5000, mode?: PositionMode): Promise<Blob> =>
    apiClient.get('/strategy/api/smart-wallet/orders/export', {
      params: { limit, mode },
      responseType: 'blob',
    }),

  exportFillsCsv: (limit = 5000, mode?: PositionMode): Promise<Blob> =>
    apiClient.get('/strategy/api/smart-wallet/fills/export', {
      params: { limit, mode },
      responseType: 'blob',
    }),

  exportLivePositionsCsv: (mode?: PositionMode): Promise<Blob> =>
    apiClient.get('/strategy/api/smart-wallet/positions/live/export', {
      params: { mode },
      responseType: 'blob',
    }),

  exportNextSettlementsCsv: (limit = 5000, mode?: PositionMode): Promise<Blob> =>
    apiClient.get('/strategy/api/smart-wallet/positions/next-settlements/export', {
      params: { limit, mode },
      responseType: 'blob',
    }),

  exportPositionHistoryCsv: (closedOnly = false, mode?: PositionMode): Promise<Blob> =>
    apiClient.get('/strategy/api/smart-wallet/positions/history/export', {
      params: { closedOnly, mode },
      responseType: 'blob',
    }),

  getHealth: (): Promise<Record<string, unknown>> =>
    apiClient.get('/strategy/api/smart-wallet/health'),
}
