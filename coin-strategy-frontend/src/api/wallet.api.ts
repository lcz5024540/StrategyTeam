import { apiClient } from './client'
import type { PositionMode } from '@/utils/constants'
import type { Position, Order, Fill } from '@/types/position'

export const walletApi = {
  getPositionsLive: (mode?: PositionMode): Promise<Position[]> =>
    apiClient.get('/strategy/api/smart-wallet/positions/live', { params: { mode } }),

  getNextSettlements: (limit = 10, mode?: PositionMode): Promise<Position[]> =>
    apiClient.get('/strategy/api/smart-wallet/positions/next-settlements', { params: { limit, mode } }),

  getPositionsHistory: (closedOnly = false, mode?: PositionMode): Promise<Position[]> =>
    apiClient.get('/strategy/api/smart-wallet/positions/history', { params: { closedOnly, mode } }),

  getOrders: (limit = 100, mode?: PositionMode): Promise<Order[]> =>
    apiClient.get('/strategy/api/smart-wallet/orders', { params: { limit, mode } }),

  getFills: (limit = 100, mode?: PositionMode): Promise<Fill[]> =>
    apiClient.get('/strategy/api/smart-wallet/fills', { params: { limit, mode } }),

  getHealth: (): Promise<Record<string, unknown>> =>
    apiClient.get('/strategy/api/smart-wallet/health'),
}
