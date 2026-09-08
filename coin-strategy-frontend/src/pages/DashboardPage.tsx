import { useSearchParams } from 'react-router-dom'
import { useStrategyStore } from '@/store/useStrategyStore'
import { normalizeRunnableStrategyCode } from '@/utils/constants'
import { HomePage } from './HomePage'
import { StockHarborFundPage } from './StockHarborFundPage'

export function DashboardPage() {
  const [searchParams] = useSearchParams()
  const { selectedStrategy } = useStrategyStore()
  const strategy = normalizeRunnableStrategyCode(searchParams.get('strategy') || selectedStrategy)

  if (strategy === 'STOCK_HARBOR') {
    return <StockHarborFundPage />
  }

  return <HomePage />
}
