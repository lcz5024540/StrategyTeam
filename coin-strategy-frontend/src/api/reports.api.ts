import { apiClient } from './client'
import type { PositionMode } from '@/utils/constants'
import type { Attribution, DailyReport, ConflictMarket, FollowAddressDailyReport, TimelinePoint } from '@/types/attribution'
import type { OffsetPageView, SmartWalletMarketMonitorItem, SmartWalletMarketMonitoringSummary } from '@/types/smartwallet'
import { TABLE_FETCH_LIMIT } from '@/utils/table'

type AttributionApiResponse = Partial<Attribution> & {
  key?: string
  walletAddress?: string
  orderCount?: number
  tradedVolumeUsdc?: number
}

function toNumber(value: unknown, fallback = 0): number {
  const num = Number(value)
  return Number.isFinite(num) ? num : fallback
}

function normalizeAttribution(item: AttributionApiResponse, type: 'wallet' | 'market'): Attribution {
  const key = String(item.key || '')
  const label = String(item.label || item.question || key || '-')
  return {
    label,
    address: type === 'wallet' ? String(item.address || item.walletAddress || key || '') : item.address,
    marketId: type === 'market' ? String(item.marketId || key || '') : item.marketId,
    question: type === 'market' ? String(item.question || item.label || '') : item.question,
    netPnlUsdc: toNumber(item.netPnlUsdc),
    realizedPnlUsdc: toNumber(item.realizedPnlUsdc),
    unrealizedPnlUsdc: toNumber(item.unrealizedPnlUsdc),
    pendingSettlementPnlUsdc: toNumber(item.pendingSettlementPnlUsdc),
    totalVolumeUsdc: toNumber(item.totalVolumeUsdc ?? item.tradedVolumeUsdc),
    tradeCount: toNumber(item.tradeCount ?? item.orderCount),
    winRate: toNumber(item.winRate),
  }
}

export const reportsApi = {
  getWalletAttribution: (limit = 10, mode: PositionMode = 'PAPER'): Promise<Attribution[]> =>
    apiClient
      .get('/strategy/api/smart-wallet/reports/wallets', { params: { limit, mode } })
      .then((rows) => ((rows || []) as unknown as AttributionApiResponse[]).map((item) => normalizeAttribution(item, 'wallet'))),

  getMarketAttribution: (limit = 10, mode: PositionMode = 'PAPER'): Promise<Attribution[]> =>
    apiClient
      .get('/strategy/api/smart-wallet/reports/markets', { params: { limit, mode } })
      .then((rows) => ((rows || []) as unknown as AttributionApiResponse[]).map((item) => normalizeAttribution(item, 'market'))),

  getDailyReports: (limit = 30, mode: PositionMode = 'PAPER'): Promise<DailyReport[]> =>
    apiClient.get('/strategy/api/smart-wallet/reports/daily', { params: { limit, mode } }),

  getPnlTimeline: (days = 30, bucketHours = 24, mode: PositionMode = 'PAPER'): Promise<TimelinePoint[]> =>
    apiClient.get('/strategy/api/smart-wallet/pnl/timeline', { params: { days, bucketHours, mode } }),

  getFollowAddressDailyReports: (limit = TABLE_FETCH_LIMIT, mode: PositionMode = 'PAPER', reportDate?: string): Promise<FollowAddressDailyReport[]> =>
    apiClient.get('/strategy/api/smart-wallet/reports/follow-addresses/daily', { params: { limit, mode, reportDate } }),

  getConflictingMarkets: (limit = 50, openOnly = false): Promise<ConflictMarket[]> =>
    apiClient.get('/strategy/api/smart-wallet/reports/conflicting-markets', { params: { limit, openOnly } }),

  getMarketMonitoringSummary: (limit = 40, lookbackDays = 14, trendDays = 7): Promise<SmartWalletMarketMonitoringSummary> =>
    apiClient.get('/strategy/api/smart-wallet/reports/market-monitoring', { params: { limit, lookbackDays, trendDays } }),

  getMarketMonitoringTablePage: (params: {
    page?: number
    pageSize?: number
    lookbackDays?: number
    keyword?: string
    category?: string
    concentrationBand?: string
    hasPosition?: boolean | null
    positionDirection?: string
  }): Promise<OffsetPageView<SmartWalletMarketMonitorItem>> =>
    apiClient.get('/strategy/api/smart-wallet/reports/market-monitoring/page', { params }),
}
