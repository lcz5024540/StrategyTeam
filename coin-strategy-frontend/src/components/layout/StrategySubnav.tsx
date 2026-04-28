import { NavLink, useSearchParams } from 'react-router-dom'
import { normalizeStrategyCode } from '@/utils/constants'
import { getStrategyPages } from '@/utils/strategyPages'
import { useStrategyStore } from '@/store/useStrategyStore'

export function StrategySubnav() {
  const [searchParams] = useSearchParams()
  const { selectedStrategy } = useStrategyStore()
  const strategy = normalizeStrategyCode(searchParams.get('strategy') || selectedStrategy)
  const pages = getStrategyPages(strategy)

  const withContext = (route: string) => {
    const params = new URLSearchParams(searchParams)
    params.set('strategy', strategy)
    params.delete('section')
    return `${route}?${params.toString()}`
  }

  return (
    <nav className="prime-subnav" aria-label="Strategy pages">
      <div className="prime-subnav-inner">
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
