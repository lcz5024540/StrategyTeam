import { apiClient } from './client'
import type { PositionMode } from '@/utils/constants'
import type { Position, Order, Fill } from '@/types/position'

/** 游标分页响应 */
export interface CursorPage<T> {
  items: T[]
  nextCursor: string | null
  size: number
  hasMore: boolean
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
  ): Promise<CursorPage<Order>> =>
    apiClient.get('/strategy/api/smart-wallet/orders/page', {
      params: { limit, lastId: lastId ?? undefined, mode },
    }),

  getFills: (limit = 100, mode?: PositionMode): Promise<Fill[]> =>
    apiClient.get('/strategy/api/smart-wallet/fills', { params: { limit, mode } }),

  getHealth: (): Promise<Record<string, unknown>> =>
    apiClient.get('/strategy/api/smart-wallet/health'),
}
