import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { strategyApi } from '@/api'
import { useStrategyStore } from '@/store/useStrategyStore'
import {
  normalizeRunnableStrategyCode,
  normalizeStrategyCode,
  type PositionMode,
} from '@/utils/constants'
import { getAmountClass } from '@/utils/format'
import { TypeBadge, StatusPill } from '@/components/ui/PrimePrimitives'
import { getStrategyVisual } from '@/utils/strategyVisuals'
import { isEnterableStrategy, toRegistryItems } from '@/utils/strategyViewModels'

function formatSignedMoneyOrDash(value: number | null | undefined): string {
  if (value === undefined || value === null) return '--'
  const num = Number(value)
  if (!Number.isFinite(num)) return '--'
  const sign = num > 0 ? '+' : num < 0 ? '-' : ''
  const abs = Math.abs(num)
  if (abs >= 1_000_000) return `${sign}$${(abs / 1_000_000).toFixed(2)}M`
  if (abs >= 1_000) return `${sign}$${(abs / 1_000).toFixed(1)}K`
  return `${sign}$${abs.toFixed(0)}`
}

export function PrimeTopbar() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const [clock, setClock] = useState(() => new Date().toTimeString().slice(0, 8))
  const [strategyMenuOpen, setStrategyMenuOpen] = useState(false)
  const strategyMenuRef = useRef<HTMLDivElement | null>(null)
  const {
    selectedStrategy,
    positionMode,
    selectedDays,
    setSelectedStrategy,
    setPositionMode,
    setSelectedDays,
  } = useStrategyStore()

  const strategy = normalizeRunnableStrategyCode(searchParams.get('strategy') || selectedStrategy)
  const mode = (searchParams.get('mode') || positionMode) as PositionMode
  const days = Number(searchParams.get('days')) || selectedDays

  const { data: overview } = useQuery({
    queryKey: ['strategyOverview', mode],
    queryFn: () => strategyApi.getOverview(mode),
  })

  const strategies = useMemo(() => toRegistryItems(overview?.strategies), [overview?.strategies])
  const activeSummary = strategies.find((item) => item.code === strategy)
  const activeVisual = getStrategyVisual(strategy)

  useEffect(() => {
    const timer = window.setInterval(() => setClock(new Date().toTimeString().slice(0, 8)), 1000)
    return () => window.clearInterval(timer)
  }, [])

  useEffect(() => {
    if (searchParams.get('strategy') === strategy) return
    const params = new URLSearchParams(searchParams)
    params.set('strategy', strategy)
    setSearchParams(params, { replace: true })
  }, [searchParams, setSearchParams, strategy])

  useEffect(() => {
    const handlePointerDown = (event: MouseEvent) => {
      if (!strategyMenuRef.current?.contains(event.target as Node)) {
        setStrategyMenuOpen(false)
      }
    }
    window.addEventListener('mousedown', handlePointerDown)
    return () => window.removeEventListener('mousedown', handlePointerDown)
  }, [])

  const updateParams = (updates: Record<string, string>) => {
    const params = new URLSearchParams(searchParams)
    Object.entries(updates).forEach(([key, value]) => params.set(key, value))
    setSearchParams(params)
  }

  const chooseStrategy = (nextCode: string) => {
    const target = strategies.find((item) => item.code === nextCode)
    if (!target || !isEnterableStrategy(target)) return
    const nextStrategy = normalizeRunnableStrategyCode(nextCode)
    const params = new URLSearchParams(searchParams)
    params.set('strategy', nextStrategy)
    setSelectedStrategy(nextStrategy)
    setStrategyMenuOpen(false)
    navigate({ pathname: '/dashboard', search: params.toString() })
  }

  return (
    <header className="ms-topbar">
      <button
        className="ms-brand"
        type="button"
        onClick={() => navigate(`/dashboard?strategy=${strategy}&mode=${mode}&days=${days}`)}
      >
        <span className="ms-brand-mark">◆</span>
        <span>MULTI<span>STRAT</span></span>
      </button>

      <div className="ms-topbar-divider" />

      <div className="ms-strategy-picker">
        <span>Strategy</span>
        <div className="ms-strategy-select" ref={strategyMenuRef}>
          <button
            type="button"
            className={`ms-strategy-select-trigger ${activeVisual.className}`}
            aria-haspopup="listbox"
            aria-expanded={strategyMenuOpen}
            onClick={() => setStrategyMenuOpen((open) => !open)}
          >
            <i>{activeVisual.icon}</i>
            <strong>{activeSummary?.name || strategy}</strong>
            <small>{activeSummary?.status || 'UNKNOWN'}</small>
            <b>⌄</b>
          </button>
          {strategyMenuOpen ? (
            <div className="ms-strategy-menu" role="listbox">
              {strategies.map((item) => {
                const itemCode = normalizeStrategyCode(item.code)
                const visual = getStrategyVisual(itemCode)
                const selected = itemCode === strategy
                const enterable = isEnterableStrategy(item)
                return (
                  <button
                    key={item.code}
                    type="button"
                    className={`ms-strategy-menu-item ${visual.className} ${selected ? 'selected' : ''} ${enterable ? '' : 'disabled'}`}
                    role="option"
                    aria-selected={selected}
                    aria-disabled={!enterable}
                    disabled={!enterable}
                    onClick={() => chooseStrategy(item.code)}
                  >
                    <i>{visual.icon}</i>
                    <span>
                      <strong>{item.name}</strong>
                      <small>{item.description}</small>
                    </span>
                    <em>{item.status}</em>
                  </button>
                )
              })}
            </div>
          ) : null}
        </div>
        <TypeBadge strategyCode={strategy} small />
        <StatusPill status={activeSummary?.status} />
      </div>

      <div className="ms-topbar-stats">
        <div>
          <span>Net PnL</span>
          <strong className={activeSummary == null ? '' : getAmountClass(activeSummary.netPnlUsdc)}>
            {formatSignedMoneyOrDash(activeSummary?.netPnlUsdc)}
          </strong>
        </div>
        <div>
          <span>Open</span>
          <strong>{activeSummary?.openPositions ?? '--'}</strong>
        </div>
      </div>

      <div className="ms-topbar-spacer" />

      <select
        className="ms-compact-select"
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

      <div className="ms-mode-toggle" aria-label="选择运行模式">
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

      <span className="ms-clock">{new Date().toLocaleDateString('en-US', { month: 'short', day: 'numeric' })} · {clock}</span>
      <span className="ms-avatar">Q</span>
    </header>
  )
}
