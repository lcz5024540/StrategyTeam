import { apiClient } from './client'
import type { Task, BlockerStat } from '@/types/task'

export const tasksApi = {
  getExecutable: (limit = 8): Promise<Task[]> =>
    apiClient.get('/smart-wallet/follow-tasks/executable', { params: { limit } }),

  getOpportunities: (limit = 8, maxBlockers = 3): Promise<Task[]> =>
    apiClient.get('/smart-wallet/follow-tasks/opportunities', { params: { limit, maxBlockers } }),

  getBlockerStats: (taskLimit = 200): Promise<BlockerStat[]> =>
    apiClient.get('/smart-wallet/follow-tasks/blocker-stats', { params: { taskLimit } }),

  getAll: (limit = 100, includeRiskEvaluation = true): Promise<Task[]> =>
    apiClient.get('/smart-wallet/follow-tasks', { params: { limit, includeRiskEvaluation } }),
}
