import { apiClient } from './client'
import type {
  ArbitrageOpportunity,
  ArbitrageExecutionOrder,
  ArbitrageOverview,
  ArbitragePnlTimePoint,
  ArbitragePosition,
  DashboardEnvelope,
  DataQuality,
  MarketHealth,
  OpportunityPage,
} from '@/types/arbitrage-dashboard'

const base = '/strategy/api/strategy-platform/arbitrage-dashboard'

export const arbitrageDashboardApi = {
  overview: (): Promise<DashboardEnvelope<ArbitrageOverview>> => apiClient.get(`${base}/overview`),
  opportunities: (params: Record<string, string | number | undefined>): Promise<DashboardEnvelope<OpportunityPage>> =>
    apiClient.get(`${base}/opportunities`, { params }),
  opportunity: (id: number): Promise<DashboardEnvelope<ArbitrageOpportunity>> =>
    apiClient.get(`${base}/opportunities/${id}`),
  positions: (params: Record<string, string | undefined> = {}): Promise<DashboardEnvelope<ArbitragePosition[]>> =>
    apiClient.get(`${base}/positions`, { params }),
  executions: (positionCode: string): Promise<DashboardEnvelope<ArbitrageExecutionOrder[]>> =>
    apiClient.get(`${base}/positions/${encodeURIComponent(positionCode)}/executions`),
  timeseries: (): Promise<DashboardEnvelope<ArbitragePnlTimePoint[]>> => apiClient.get(`${base}/timeseries`),
  marketHealth: (): Promise<DashboardEnvelope<MarketHealth[]>> => apiClient.get(`${base}/market-health`),
  dataQuality: (): Promise<DashboardEnvelope<DataQuality>> => apiClient.get(`${base}/data-quality`),
}
