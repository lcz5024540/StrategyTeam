import { apiClient } from './client'
import type { BaselineModelConfig, RuntimeConfigCatalog, RuntimeConfigItem } from '@/types/config'

// 注：updateBaselineModel / updateRuntimeConfig / resetRuntimeConfig 三个 POST 调用已下线。
// 这些写配置端点是 job 进程内存绑定操作，按"不能本地化的接口只保留为 job 内网能力"原则，
// web 不再暴露；运维需修改配置时直接在 job:8081 内网执行 curl，或通过运维工具修改 YAML。
// 配置只读接口（baseline-model GET / runtime GET / runtime/catalog GET）保留，
// 仍 proxy 到 job:8081（属"job 进程自省"，前端可读，可接受 N 秒延迟）。

export const configApi = {
  getBaselineModel: (): Promise<BaselineModelConfig> =>
    apiClient.get('/strategy/api/smart-wallet/config/baseline-model'),

  getRuntimeCatalog: (strategyCode = 'POLYMARKET_COPY'): Promise<RuntimeConfigCatalog> =>
    apiClient.get('/strategy/api/smart-wallet/config/runtime/catalog', { params: { strategyCode } }),

  getRuntimeConfig: (strategyCode = 'POLYMARKET_COPY'): Promise<RuntimeConfigItem[]> =>
    apiClient.get('/strategy/api/smart-wallet/config/runtime', { params: { strategyCode } }),
}
