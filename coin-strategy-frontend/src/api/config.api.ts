import { apiClient } from './client'
import type { BaselineModelConfig, RuntimeConfigCatalog, RuntimeConfigItem, UpdateRuntimeConfigRequest } from '@/types/config'

export const configApi = {
  getBaselineModel: (): Promise<BaselineModelConfig> =>
    apiClient.get('/strategy/api/smart-wallet/config/baseline-model'),

  updateBaselineModel: (config: BaselineModelConfig): Promise<BaselineModelConfig> =>
    apiClient.post('/strategy/api/smart-wallet/config/baseline-model', config),

  getRuntimeCatalog: (strategyCode = 'POLYMARKET_COPY'): Promise<RuntimeConfigCatalog> =>
    apiClient.get('/strategy/api/smart-wallet/config/runtime/catalog', { params: { strategyCode } }),

  getRuntimeConfig: (strategyCode = 'POLYMARKET_COPY'): Promise<RuntimeConfigItem[]> =>
    apiClient.get('/strategy/api/smart-wallet/config/runtime', { params: { strategyCode } }),

  updateRuntimeConfig: (payload: UpdateRuntimeConfigRequest, strategyCode = 'POLYMARKET_COPY'): Promise<RuntimeConfigItem[]> =>
    apiClient.post('/strategy/api/smart-wallet/config/runtime', payload, { params: { strategyCode } }),

  resetRuntimeConfig: (keys: string[], strategyCode = 'POLYMARKET_COPY'): Promise<RuntimeConfigItem[]> =>
    apiClient.post('/strategy/api/smart-wallet/config/runtime/reset', { keys }, { params: { strategyCode } }),
}
