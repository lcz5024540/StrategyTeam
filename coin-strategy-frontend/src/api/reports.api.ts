import { apiClient } from './client'
import type { PositionMode } from '@/utils/constants'
import type { Attribution, DailyReport, ConflictMarket } from '@/types/attribution'

export const reportsApi = {
  getWalletAttribution: (limit = 10, mode: PositionMode = 'PAPER'): Promise<Attribution[]> =>
    apiClient.get('/strategy/api/smart-wallet/reports/wallets', { params: { limit, mode } }),

  getMarketAttribution: (limit = 10, mode: PositionMode = 'PAPER'): Promise<Attribution[]> =>
    apiClient.get('/strategy/api/smart-wallet/reports/markets', { params: { limit, mode } }),

  getDailyReports: (limit = 30, mode: PositionMode = 'PAPER'): Promise<DailyReport[]> =>
    apiClient.get('/strategy/api/smart-wallet/reports/daily', { params: { limit, mode } }),

  getConflictingMarkets: (limit = 50, openOnly = false): Promise<ConflictMarket[]> =>
    apiClient.get('/strategy/api/smart-wallet/reports/conflicting-markets', { params: { limit, openOnly } }),
}
