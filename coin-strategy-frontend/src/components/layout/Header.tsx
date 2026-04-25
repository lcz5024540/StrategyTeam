import { useSearchParams } from 'react-router-dom'
import { useStrategyStore } from '@/store/useStrategyStore'
import { STRATEGY_CATALOG } from '@/utils/constants'

export function Header() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { selectedStrategy, positionMode, selectedDays, setPositionMode, setSelectedDays } = useStrategyStore()

  const strategy = searchParams.get('strategy') || selectedStrategy
  const days = Number(searchParams.get('days')) || selectedDays
  const mode = (searchParams.get('mode') || positionMode) as 'PAPER' | 'LIVE'

  const currentStrategy = STRATEGY_CATALOG.find(s => s.code === strategy)

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
