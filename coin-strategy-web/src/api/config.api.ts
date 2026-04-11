import { apiClient } from './client'
import type { BaselineModelConfig } from '@/types/config'

export const configApi = {
  getBaselineModel: (): Promise<BaselineModelConfig> =>
    apiClient.get('/smart-wallet/config/baseline-model'),

  updateBaselineModel: (config: BaselineModelConfig): Promise<BaselineModelConfig> =>
    apiClient.post('/smart-wallet/config/baseline-model', config),
}
