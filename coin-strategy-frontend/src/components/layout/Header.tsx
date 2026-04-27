import { useEffect } from 'react'
import { useLocation, useSearchParams } from 'react-router-dom'
import { useStrategyStore } from '@/store/useStrategyStore'
import { DEFAULT_STRATEGY_CODE, STRATEGY_CATALOG, normalizeStrategyCode } from '@/utils/constants'

export function Header() {
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const { selectedStrategy, positionMode, selectedDays, setPositionMode, setSelectedDays } = useStrategyStore()
  const strategyParam = searchParams.get('strategy')

  const strategy = (() => {
    const matched = STRATEGY_CATALOG.find((item) => item.route !== '/dashboard' && location.pathname.startsWith(item.route))
    if (matched) return matched.code
    if (
      location.pathname === '/dashboard' ||
      location.pathname.startsWith('/dashboard/detail') ||
      location.pathname.startsWith('/dashboard/charts') ||
      location.pathname.startsWith('/dashboard/monitoring') ||
      location.pathname.startsWith('/dashboard/markets')
    ) {
      return DEFAULT_STRATEGY_CODE
    }
    return normalizeStrategyCode(strategyParam || selectedStrategy)
  })()
  const expectedStrategyParam = strategy
  const days = Number(searchParams.get('days')) || selectedDays
  const mode = (searchParams.get('mode') || positionMode) as 'PAPER' | 'LIVE'

  const currentStrategy = STRATEGY_CATALOG.find(s => s.code === strategy)

  useEffect(() => {
    if (strategyParam === expectedStrategyParam) return
    const newParams = new URLSearchParams(searchParams)
    newParams.set('strategy', expectedStrategyParam)
    setSearchParams(newParams, { replace: true })
  }, [expectedStrategyParam, searchParams, setSearchParams, strategyParam])

  const updateParams = (updates: Record<string, string>) => {
    const newParams = new URLSearchParams(searchParams)
    Object.entries(updates).forEach(([key, value]) => {
      newParams.set(key, value)
    })
    setSearchParams(newParams)
  }

  return (
    <header className="hero">
      <div>
        <h1>{currentStrategy?.name || '策略总览'}</h1>
      </div>
      <div className="filter-bar">
        <select
          value={days}
          onChange={(e) => {
            setSelectedDays(Number(e.target.value))
            updateParams({ days: e.target.value })
          }}
        >
          <option value="7">7 天</option>
          <option value="14">14 天</option>
          <option value="30">30 天</option>
          <option value="60">60 天</option>
        </select>
        <select
          value={mode}
          onChange={(e) => {
            setPositionMode(e.target.value as 'PAPER' | 'LIVE')
            updateParams({ mode: e.target.value })
          }}
        >
          <option value="PAPER">模拟</option>
          <option value="LIVE">实盘</option>
        </select>
      </div>
    </header>
  )
}
