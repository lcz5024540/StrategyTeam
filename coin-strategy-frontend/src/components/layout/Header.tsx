import { useEffect, useMemo, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { strategyApi } from '@/api'
import { useStrategyStore } from '@/store/useStrategyStore'
import { normalizeStrategyCode, type PositionMode } from '@/utils/constants'
import { getDefaultStrategyRoute, getStrategyPageTitle } from '@/utils/strategyPages'
import { toRegistryItems } from '@/utils/strategyViewModels'

export function Header() {
  const location = useLocation()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const [clock, setClock] = useState(() => new Date().toTimeString().slice(0, 8))
  const {
    selectedStrategy,
    positionMode,
    selectedDays,
    setSelectedStrategy,
    setPositionMode,
    setSelectedDays,
  } = useStrategyStore()

  const strategy = normalizeStrategyCode(searchParams.get('strategy') || selectedStrategy)
  const days = Number(searchParams.get('days')) || selectedDays
  const mode = (searchParams.get('mode') || positionMode) as PositionMode

  const { data: overview } = useQuery({
    queryKey: ['strategyOverview', mode],
    queryFn: () => strategyApi.getOverview(mode),
  })

  const strategies = useMemo(() => toRegistryItems(overview?.strategies), [overview?.strategies])
  const label = getStrategyPageTitle(strategy, location.pathname)

  useEffect(() => {
    const timer = window.setInterval(() => setClock(new Date().toTimeString().slice(0, 8)), 1000)
    return () => window.clearInterval(timer)
  }, [])

  useEffect(() => {
    if (searchParams.get('strategy') === strategy) return
    const newParams = new URLSearchParams(searchParams)
    newParams.set('strategy', strategy)
    setSearchParams(newParams, { replace: true })
  }, [searchParams, setSearchParams, strategy])

  const updateParams = (updates: Record<string, string>) => {
    const newParams = new URLSearchParams(searchParams)
    Object.entries(updates).forEach(([key, value]) => {
      newParams.set(key, value)
    })
    setSearchParams(newParams)
  }

  return (
    <header className="prime-topbar">
      <div className="prime-topbar-title">
        <span className="prime-wordmark">COIN<span>STRATEGY</span>PRIME</span>
        <span className="prime-page-label">{label}</span>
      </div>

      <div className="prime-topbar-controls">
        <select
          value={strategy}
          onChange={(event) => {
            const nextStrategy = normalizeStrategyCode(event.target.value)
            const nextParams = new URLSearchParams(searchParams)
            nextParams.set('strategy', nextStrategy)
            nextParams.delete('section')
            setSelectedStrategy(nextStrategy)
            navigate({
              pathname: getDefaultStrategyRoute(nextStrategy),
              search: nextParams.toString(),
            })
          }}
          aria-label="选择策略"
        >
          {strategies.map((item) => (
            <option key={item.code} value={item.code}>
              {item.name} · {item.status}
            </option>
          ))}
        </select>

        <select
          value={days}
          onChange={(event) => {
            setSelectedDays(Number(event.target.value))
            updateParams({ days: event.target.value })
          }}
          aria-label="选择时间范围"
        >
          <option value="7">7D</option>
          <option value="14">14D</option>
          <option value="30">30D</option>
          <option value="60">60D</option>
        </select>

        <div className="prime-mode-toggle" aria-label="选择运行模式">
          {(['PAPER', 'LIVE'] as PositionMode[]).map((item) => (
            <button
              key={item}
              type="button"
              className={mode === item ? 'active' : ''}
              onClick={() => {
                setPositionMode(item)
                updateParams({ mode: item })
              }}
            >
              {item}
            </button>
          ))}
        </div>

        <span className="prime-clock">{new Date().toLocaleDateString('en-US', { month: 'short', day: 'numeric' })} · {clock}</span>
      </div>
    </header>
  )
}
