import { apiClient } from './client'
import type { PositionMode } from '@/utils/constants'
import type { Attribution, DailyReport, ConflictMarket, FollowAddressDailyReport, TimelinePoint } from '@/types/attribution'
import { TABLE_FETCH_LIMIT } from '@/utils/table'

export const reportsApi = {
  getWalletAttribution: (limit = 10, mode: PositionMode = 'PAPER'): Promise<Attribution[]> =>
    apiClient.get('/strategy/api/smart-wallet/reports/wallets', { params: { limit, mode } }),

  getMarketAttribution: (limit = 10, mode: PositionMode = 'PAPER'): Promise<Attribution[]> =>
    apiClient.get('/strategy/api/smart-wallet/reports/markets', { params: { limit, mode } }),

  getDailyReports: (limit = 30, mode: PositionMode = 'PAPER'): Promise<DailyReport[]> =>
    apiClient.get('/strategy/api/smart-wallet/reports/daily', { params: { limit, mode } }),

  getPnlTimeline: (days = 30, bucketHours = 24, mode: PositionMode = 'PAPER'): Promise<TimelinePoint[]> =>
    apiClient.get('/strategy/api/smart-wallet/pnl/timeline', { params: { days, bucketHours, mode } }),

  getFollowAddressDailyReports: (limit = TABLE_FETCH_LIMIT, mode: PositionMode = 'PAPER', reportDate?: string): Promise<FollowAddressDailyReport[]> =>
    apiClient.get('/strategy/api/smart-wallet/reports/follow-addresses/daily', { params: { limit, mode, reportDate } }),

  getConflictingMarkets: (limit = 50, openOnly = false): Promise<ConflictMarket[]> =>
    apiClient.get('/strategy/api/smart-wallet/reports/conflicting-markets', { params: { limit, openOnly } }),
}
