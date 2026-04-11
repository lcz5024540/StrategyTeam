export interface FollowProbabilityConfig {
  intercept: number
  walletScoreCoef: number
  consensusScoreCoef: number
  priceDeviationCoef: number
  volumeRatioCoef: number
  timeDecayCoef: number
  marketLiquidityCoef: number
  [key: string]: number
}

export interface SlippageConfig {
  baseCost: number
  spreadCost: number
  impactCost: number
  [key: string]: number
}

export interface SlippageSpikeConfig {
  lowLiquidityThreshold: number
  highVolatilityThreshold: number
  spreadThreshold: number
  [key: string]: number
}

export interface CalibrationConfig {
  enabled: boolean
  plattA: number
  plattB: number
  [key: string]: number | boolean
}

export interface BaselineModelConfig {
  followProbability: FollowProbabilityConfig
  slippage: SlippageConfig
  slippageSpike: SlippageSpikeConfig
  calibration: CalibrationConfig
}
