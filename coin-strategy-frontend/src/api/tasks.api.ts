import { apiClient } from './client'
import type { Task, BlockerStat } from '@/types/task'

export const tasksApi = {
  getExecutable: (limit = 8): Promise<Task[]> =>
    apiClient.get('/strategy/api/smart-wallet/follow-tasks/executable', { params: { limit } }),

  getOpportunities: (limit = 8, maxBlockers = 3): Promise<Task[]> =>
    apiClient.get('/strategy/api/smart-wallet/follow-tasks/opportunities', { params: { limit, maxBlockers } }),

  getBlockerStats: (taskLimit = 200): Promise<BlockerStat[]> =>
    apiClient.get('/strategy/api/smart-wallet/follow-tasks/blocker-stats', { params: { taskLimit } }),

  getManualAllowlist: (limit = 30, includeBlocked = true): Promise<Task[]> =>
    apiClient.get('/strategy/api/smart-wallet/follow-tasks/manual-allowlist', { params: { limit, includeBlocked } }),

  getAll: (limit = 100, includeRiskEvaluation = true): Promise<Task[]> =>
    apiClient.get('/strategy/api/smart-wallet/follow-tasks', { params: { limit, includeRiskEvaluation } }),
}
