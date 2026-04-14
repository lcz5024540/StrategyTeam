import { apiClient } from './client'
import type { BaselineModelConfig } from '@/types/config'

export const configApi = {
  getBaselineModel: (): Promise<BaselineModelConfig> =>
    apiClient.get('/strategy/api/smart-wallet/config/baseline-model'),

  updateBaselineModel: (config: BaselineModelConfig): Promise<BaselineModelConfig> =>
    apiClient.post('/strategy/api/smart-wallet/config/baseline-model', config),
}
