import { NavLink, useSearchParams } from 'react-router-dom'
import { DEFAULT_STRATEGY_CODE, normalizeStrategyCode } from '@/utils/constants'
import { getStrategyPages } from '@/utils/strategyPages'
import { useStrategyStore } from '@/store/useStrategyStore'

export function Sidebar() {
  const [searchParams] = useSearchParams()
  const { selectedStrategy } = useStrategyStore()
  const activeStrategyCode = normalizeStrategyCode(searchParams.get('strategy') || selectedStrategy)
  const strategyPages = getStrategyPages(activeStrategyCode)
  const monitorPage = strategyPages.find((page) => ['addresses', 'execution'].includes(page.key)) || strategyPages[0]
  const factorsPage = strategyPages.find((page) => page.key === 'factors') || strategyPages[0]

  const withParams = (path: string, extra?: Record<string, string>) => {
    const params = new URLSearchParams(searchParams)
    params.set('strategy', activeStrategyCode || DEFAULT_STRATEGY_CODE)
    Object.entries(extra || {}).forEach(([key, value]) => params.set(key, value))
    return `${path}?${params.toString()}`
  }

  return (
    <aside className="prime-rail">
      <NavLink className="prime-logo" to={withParams('/dashboard')} title="CoinStrategy">
        <span>CS</span>
      </NavLink>

      <nav className="prime-rail-nav" aria-label="Primary">
        <NavLink to={withParams('/dashboard')} title="Dashboard">
          <span>◈</span>
        </NavLink>
        <NavLink to={withParams(monitorPage.route)} title={monitorPage.label}>
          <span>⬡</span>
        </NavLink>
        <NavLink to={withParams(factorsPage.route)} title={factorsPage.label}>
          <span>⬙</span>
        </NavLink>
      </nav>

      <div className="prime-live-dot" title="Live session" />
    </aside>
  )
}
