export interface FollowProbabilityConfig {
  intercept: number
  walletScoreWeight: number
  consensusScoreWeight: number
  positiveRateWeight: number
  triggerSizeWeight: number
  distinctWalletWeight: number
  imbalanceWeight: number
  spreadPenaltyWeight: number
  volatilityPenaltyWeight: number
  triggerSizeCapUsdc: number
  distinctWalletCap: number
  spreadPenaltyScaleBps: number
  volatilityPenaltyScale: number
}

export interface SlippageConfig {
  spreadCostWeight: number
  impactCostWeight: number
  volatilityCostWeight: number
}

export interface SlippageSpikeConfig {
  intercept: number
  spreadRiskWeight: number
  volatilityRiskWeight: number
  sizeDepthRiskWeight: number
  historicalSpikeWeight: number
  spreadRiskScaleBps: number
  volatilityRiskScale: number
  spikeThresholdBps: number
}

export interface CalibrationConfig {
  followProbabilityScale: number
  followProbabilityBias: number
  slippageSpikeScale: number
  slippageSpikeBias: number
}

export interface BaselineModelConfig {
  grossEdgePriorWeight: number
  followProbability: FollowProbabilityConfig
  slippage: SlippageConfig
  slippageSpike: SlippageSpikeConfig
  calibration: CalibrationConfig
}

export type RuntimeConfigValueType = 'BOOLEAN' | 'INTEGER' | 'DECIMAL' | 'STRING' | 'STRING_LIST' | 'JSON'

export interface RuntimeConfigItem {
  strategyCode: string
  key: string
  value: string
  defaultValue: string
  valueType: RuntimeConfigValueType
  groupName: string
  label: string
  description: string
  editable: boolean
  source: 'YAML' | 'DB' | 'RUNTIME' | string
  updatedAt?: string | null
}

export interface RuntimeConfigGroup {
  name: string
  label: string
  description: string
}

export interface RuntimeConfigCatalog {
  strategyCode: string
  groups: RuntimeConfigGroup[]
  items: RuntimeConfigItem[]
}

export interface UpdateRuntimeConfigRequest {
  updatedBy?: string
  items: Array<{ key: string; value: string }>
}
