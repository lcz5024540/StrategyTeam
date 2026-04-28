import { NavLink, useSearchParams } from 'react-router-dom'
import { useStrategyStore } from '@/store/useStrategyStore'
import { normalizeRunnableStrategyCode } from '@/utils/constants'
import { PRIME_NAV_ITEMS } from '@/utils/strategyVisuals'

export function PrimeRail() {
  const [searchParams] = useSearchParams()
  const { selectedStrategy } = useStrategyStore()
  const strategy = normalizeRunnableStrategyCode(searchParams.get('strategy') || selectedStrategy)

  const withContext = (route: string) => {
    const params = new URLSearchParams(searchParams)
    params.set('strategy', strategy)
    return `${route}?${params.toString()}`
  }

  return (
    <aside className="ms-rail">
      {PRIME_NAV_ITEMS.map((item) => (
        <NavLink
          key={item.id}
          to={withContext(item.route)}
          end={item.route === '/dashboard'}
          className={({ isActive }) => isActive ? 'active' : ''}
          title={item.label}
        >
          <span>{item.icon}</span>
        </NavLink>
      ))}
      <div className="ms-live-dot" title="Live session" />
    </aside>
  )
}
