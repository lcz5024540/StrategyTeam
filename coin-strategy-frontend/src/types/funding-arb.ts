export interface FundingArbExecuteParams {
  symbols: string
  amount: number
  minRate: number
  leverage: number
}

export interface FundingArbExecutionSummaryItem {
  symbol: string
  status: string
}

export interface FundingArbExecutionSummary {
  total: number
  opened: number
  skipped: number
  failed: number
  items: FundingArbExecutionSummaryItem[]
}
