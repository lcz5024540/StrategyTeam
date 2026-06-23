import { useLocation, useSearchParams } from 'react-router-dom'
import { useStrategyStore } from '@/store/useStrategyStore'
import { STRATEGY_CATALOG, normalizeRunnableStrategyCode } from '@/utils/constants'
import { getStrategyPages } from '@/utils/strategyPages'
import { getStrategyVisual, PRIME_NAV_ITEMS } from '@/utils/strategyVisuals'

function getActiveNav(pathname: string) {
  if (pathname.startsWith('/dashboard/monitor')) return PRIME_NAV_ITEMS[1]
  if (pathname.startsWith('/dashboard/factors')) return PRIME_NAV_ITEMS[2]
  if (pathname.startsWith('/dashboard/config')) return { id: 'config', label: 'Config Disabled', icon: '⚙', route: '/dashboard/config' }
  if (pathname.startsWith('/dashboard/logs')) return PRIME_NAV_ITEMS[3]
  return PRIME_NAV_ITEMS[0]
}

export function PageLabelBar() {
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const { selectedStrategy } = useStrategyStore()
  const strategy = normalizeRunnableStrategyCode(searchParams.get('strategy') || selectedStrategy)
  const nav = getActiveNav(location.pathname)
  const strategyMeta = STRATEGY_CATALOG.find((item) => item.code === strategy)
  const visual = getStrategyVisual(strategy)
  const page = getStrategyPages(strategy).find((item) => item.route === location.pathname)

  return (
    <div className="ms-label-bar">
      <span>{nav.icon} {nav.label}</span>
      {nav.id === 'dashboard' ? (
        <>
          <i>›</i>
          <strong className={visual.className}>{strategyMeta?.name || strategy}</strong>
          {page && page.key !== 'dashboard' ? (
            <>
              <i>›</i>
              <strong>{page.label}</strong>
            </>
          ) : null}
        </>
      ) : null}
    </div>
  )
}
