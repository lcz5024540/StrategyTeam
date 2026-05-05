import { useEffect } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { strategyApi } from '@/api'
import { StrategyFactorsPanel } from '@/components/strategy/StrategyFactorsPanel'
import { useStrategyStore } from '@/store/useStrategyStore'
import { STRATEGY_CATALOG, normalizeRunnableStrategyCode, type PositionMode } from '@/utils/constants'

function normalizeMode(value: string | null, fallback: PositionMode): PositionMode {
  if (value === 'LIVE' || value === 'PAPER') return value
  return fallback
}

export function StrategyFactorsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const {
    selectedStrategy,
    positionMode,
    selectedDays,
    setSelectedStrategy,
    setPositionMode,
    setSelectedDays,
  } = useStrategyStore()

  const strategy = normalizeRunnableStrategyCode(searchParams.get('strategy') || selectedStrategy)
  const mode = normalizeMode(searchParams.get('mode'), positionMode)
  const days = Number(searchParams.get('days')) || selectedDays
  const strategyMeta = STRATEGY_CATALOG.find((item) => item.code === strategy)
  const supportsFactors = Boolean(strategyMeta?.capabilities.includes('FACTORS'))

  useEffect(() => {
    if (searchParams.get('strategy') !== strategy) {
      const params = new URLSearchParams(searchParams)
      params.set('strategy', strategy)
      setSearchParams(params, { replace: true })
    }
    setSelectedStrategy(strategy)
    setPositionMode(mode)
    setSelectedDays(days)
  }, [days, mode, searchParams, setPositionMode, setSearchParams, setSelectedDays, setSelectedStrategy, strategy])

  const { data: factors = [], isLoading } = useQuery({
    queryKey: ['strategyFactorsPage', strategy, mode, `${days}D`],
    queryFn: () => strategyApi.getFactors(strategy, mode, `${days}D`),
    enabled: supportsFactors,
    retry: false,
  })

  return (
    <>
      <section className="panel strategy-context-panel">
        <div>
          <p className="eyebrow">Strategy Factors</p>
          <h2>{strategyMeta?.name || strategy}</h2>
          <p className="table-secondary">
            当前因子页跟随策略、模式和时间窗口变化：{mode} · {days}D。不同策略可以返回不同字段，特殊数据放在 metadata 中展示。
          </p>
        </div>
        <div className="strategy-context-meta">
          <span className="badge accent">{strategy}</span>
          <span className="badge">{mode}</span>
          <span className="badge">{days}D</span>
        </div>
      </section>

      {supportsFactors ? (
        <StrategyFactorsPanel factors={factors} loading={isLoading} />
      ) : (
        <section className="panel">
          <div className="section-head">
            <h2>策略因子</h2>
          </div>
          <div className="empty-state">当前策略未声明 FACTORS capability，暂不展示因子面板。</div>
        </section>
      )}
    </>
  )
}
