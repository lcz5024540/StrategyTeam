import { apiClient } from './client'
import type {
  CursorPageView,
  SmartWalletProfileOverrideRequest,
  WalletDiagnosisView,
  WalletOrderAnalysisView,
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

export const profilesApi = {
  getScreener: (params: ScreenerParams = {}): Promise<CursorPageView<WalletScreenerView>> =>
    apiClient.get('/strategy/api/smart-wallet/profiles/screener', { params }),

  getDiagnosis: (walletAddress: string, refreshOrderAnalysis = false): Promise<WalletDiagnosisView> =>
    apiClient.get(`/strategy/api/smart-wallet/profiles/${walletAddress}/diagnosis`, {
      params: { refreshOrderAnalysis },
    }),

  getOrderAnalysis: (
    walletAddress: string,
    params: OrderAnalysisParams = {}
  ): Promise<CursorPageView<WalletOrderAnalysisView>> =>
    apiClient.get(`/strategy/api/smart-wallet/profiles/${walletAddress}/order-analysis`, { params }),

  updateProfileOverride: (
    walletAddress: string,
    payload: SmartWalletProfileOverrideRequest
  ): Promise<unknown> =>
    apiClient.post(`/strategy/api/smart-wallet/profiles/${walletAddress}/override`, payload),
}
