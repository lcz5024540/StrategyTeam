export interface Task {
  id: number
  walletAddress: string
  marketId: string
  question: string
  outcome: string
  marketEndAt: number
  marketEndAtBjt: string
  side: string
  triggerPrice: number
  triggerSizeUsdc: number
  recommendedStakeUsdc: number
  status: string
  executionMode: string
  consensusWallets: number
  consensusScore: number
  estimatedFeeUsdc?: number
  estimatedSlippageUsdc?: number
  followProbability?: number
  calibratedFollowProbability?: number
  slippageSpikeProbability?: number
  estimateSampleSize?: number
  taskExecutable: boolean
  overrideApplied: boolean
  appliedPreset?: string
  blockerCount: number
  primaryBlockerCategory?: string
  blockerCategories: string[]
  riskReasons: string[]
  scheduledAt: string
  executedAt?: string
}

export interface BlockerStat {
  blockerCategory: string
  blockedTaskCount: number
  sampleTasks: Task[]
}
