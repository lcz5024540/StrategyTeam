import { apiClient } from './client'
import type {
  CursorPageView,
  SmartWalletManualAllowlistView,
  SmartWalletManualBlocklistView,
  SmartWalletProfileOverrideRequest,
  WalletDiagnosisView,
  WalletOrderAnalysisView,
  WalletOrderStatsView,
  WalletScreenerView,
} from '@/types'

interface ScreenerParams {
  scope?: 'candidates' | 'executable' | 'all'
  limit?: number
  cursor?: string | null
  sortBy?: string
  sortDirection?: 'asc' | 'desc'
  screenerTier?: string
  highQualityAddress?: boolean
  highFrequencyTradingAddress?: boolean
  winSourceType?: string
  recommendedExitLane?: string
  minQualityScore?: number
  minReplicabilityScore?: number
}

interface OrderAnalysisParams {
  limit?: number
  cursor?: string | null
  exitStyle?: string
  isWin?: boolean
  refresh?: boolean
}

interface OrderStatsParams {
  window?: '7d' | '30d' | 'all'
  refresh?: boolean
}

export const profilesApi = {
  getScreener: (params: ScreenerParams = {}): Promise<CursorPageView<WalletScreenerView>> =>
    apiClient.get('/strategy/api/smart-wallet/profiles/screener', { params }),

  getAllowlist: (limit = 100): Promise<SmartWalletManualAllowlistView[]> =>
    apiClient.get('/strategy/api/smart-wallet/allowlist', { params: { limit } }),

  getBlocklist: (limit = 200): Promise<SmartWalletManualBlocklistView[]> =>
    apiClient.get('/strategy/api/smart-wallet/blocklist', { params: { limit } }),

  getDiagnosis: (walletAddress: string, refreshOrderAnalysis = false): Promise<WalletDiagnosisView> =>
    apiClient.get(`/strategy/api/smart-wallet/profiles/${walletAddress}/diagnosis`, {
      params: { refreshOrderAnalysis },
    }),

  getOrderAnalysis: (
    walletAddress: string,
    params: OrderAnalysisParams = {}
  ): Promise<CursorPageView<WalletOrderAnalysisView>> =>
    apiClient.get(`/strategy/api/smart-wallet/profiles/${walletAddress}/order-analysis`, { params }),

  getOrderStats: (
    walletAddress: string,
    params: OrderStatsParams = {}
  ): Promise<WalletOrderStatsView> =>
    apiClient.get(`/strategy/api/smart-wallet/profiles/${walletAddress}/order-stats`, { params }),

  updateProfileOverride: (
    walletAddress: string,
    payload: SmartWalletProfileOverrideRequest
  ): Promise<unknown> =>
    apiClient.post(`/strategy/api/smart-wallet/profiles/${walletAddress}/override`, payload),
}
