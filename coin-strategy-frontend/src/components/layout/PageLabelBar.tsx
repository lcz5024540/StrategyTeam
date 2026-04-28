import { useLocation, useSearchParams } from 'react-router-dom'
import { useStrategyStore } from '@/store/useStrategyStore'
import { STRATEGY_CATALOG, normalizeRunnableStrategyCode } from '@/utils/constants'
import { getStrategyVisual, PRIME_NAV_ITEMS } from '@/utils/strategyVisuals'

function getActiveNav(pathname: string) {
  if (pathname.startsWith('/dashboard/monitor')) return PRIME_NAV_ITEMS[1]
  if (pathname.startsWith('/dashboard/factors')) return PRIME_NAV_ITEMS[2]
  if (pathname.startsWith('/dashboard/config')) return PRIME_NAV_ITEMS[3]
  if (pathname.startsWith('/dashboard/logs')) return PRIME_NAV_ITEMS[4]
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

  return (
    <div className="ms-label-bar">
      <span>{nav.icon} {nav.label}</span>
      {nav.id === 'dashboard' ? (
        <>
          <i>›</i>
          <strong className={visual.className}>{strategyMeta?.name || strategy}</strong>
        </>
      ) : null}
    </div>
  )
}
