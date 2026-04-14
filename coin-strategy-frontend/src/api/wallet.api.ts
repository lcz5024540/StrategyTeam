import { apiClient } from './client'
import type { Position, Order, Fill } from '@/types/position'

export const walletApi = {
  getPositionsLive: (): Promise<Position[]> =>
    apiClient.get('/strategy/api/smart-wallet/positions/live'),

  getNextSettlements: (limit = 10): Promise<Position[]> =>
    apiClient.get('/strategy/api/smart-wallet/positions/next-settlements', { params: { limit } }),

  getPositionsHistory: (closedOnly = false): Promise<Position[]> =>
    apiClient.get('/strategy/api/smart-wallet/positions/history', { params: { closedOnly } }),

  getOrders: (limit = 100): Promise<Order[]> =>
    apiClient.get('/strategy/api/smart-wallet/orders', { params: { limit } }),

  getFills: (limit = 100): Promise<Fill[]> =>
    apiClient.get('/strategy/api/smart-wallet/fills', { params: { limit } }),

  getHealth: (): Promise<Record<string, unknown>> =>
    apiClient.get('/strategy/api/smart-wallet/health'),
}
