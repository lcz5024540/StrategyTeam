import { apiClient } from './client'
import type { FundingArbExecuteParams } from '@/types/funding-arb'

export const fundingArbApi = {
  execute: ({ symbols, amount, minRate, leverage }: FundingArbExecuteParams): Promise<string> =>
    apiClient.post('/strategy/api/binance/strategy/arbitrage/execute', null, {
      params: {
        symbols,
        amount,
        minRate,
        leverage,
      },
      headers: {
        Accept: 'text/plain',
      },
      responseType: 'text',
    }),
}
