import { apiClient } from './client'
import type { FundingRateMatrixResponse, HtxTrendDashboardResponse } from '@/types/arbitrage-dashboard'

/**
 * 只读看板接口。Vite 开发环境会把 /strategy/api 代理给 Web 服务；生产环境由网关路由。
 * 交易、开仓和 Job 内网端口均不属于此前端的访问范围。
 */
export const arbitrageDashboardApi = {
  getFundingRateMatrix: (): Promise<FundingRateMatrixResponse> =>
    apiClient.get('/strategy/api/strategy-platform/arbitrage-dashboard/funding-rate-matrix'),
  getHtxTrendDashboard: (mode: 'PAPER' | 'LIVE' = 'LIVE'): Promise<HtxTrendDashboardResponse> =>
    apiClient.get('/strategy/api/strategy-platform/arbitrage-dashboard/htx-trend', { params: { mode } }),
}
