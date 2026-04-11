import { apiClient } from './client'
import type { PositionMode } from '@/utils/constants'
import type { StrategyOverview, StrategyDetail } from '@/types/strategy'

export const strategyApi = {
  getOverview: (mode: PositionMode = 'PAPER'): Promise<StrategyOverview> =>
    apiClient.get('/strategy-platform/strategy/overview', { params: { mode } }),

  getDetail: (code: string, mode: PositionMode = 'PAPER'): Promise<StrategyDetail> =>
    apiClient.get(`/strategy-platform/strategy/${code}`, { params: { mode } }),
}
