import { NavLink, useLocation, useSearchParams } from 'react-router-dom'
import { normalizeRunnableStrategyCode } from '@/utils/constants'
import { getStrategyPages } from '@/utils/strategyPages'
import { useStrategyStore } from '@/store/useStrategyStore'

export function StrategySubnav() {
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const { selectedStrategy } = useStrategyStore()
  const strategy = normalizeRunnableStrategyCode(searchParams.get('strategy') || selectedStrategy)
  const pages = getStrategyPages(strategy)
  const isStrategyPage = pages.some((page) => page.route === location.pathname)

  if (!isStrategyPage) {
    return null
  }

  const withContext = (route: string) => {
    const params = new URLSearchParams(searchParams)
    params.set('strategy', strategy)
    params.delete('section')
    if (route !== '/dashboard/orders') {
      params.delete('orders')
    }
    return `${route}?${params.toString()}`
  }

  return (
    <nav className="ms-strategy-tabs" aria-label="Strategy pages">
      <div className="ms-strategy-tabs-inner">
        {pages.map((page) => (
          <NavLink
            key={page.key}
            to={withContext(page.route)}
            end
            className={({ isActive }) => isActive ? 'active' : ''}
          >
            {page.label}
          </NavLink>
        ))}
      </div>
    </nav>
  )
}
