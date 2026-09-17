import { Outlet, useLocation } from 'react-router-dom'
import { PrimeTopbar } from './components/layout/PrimeTopbar'
import { PrimeRail } from './components/layout/PrimeRail'
import { PageLabelBar } from './components/layout/PageLabelBar'
import { StrategySubnav } from './components/layout/StrategySubnav'

export function App() {
  const location = useLocation()
  // The strategy console is intentionally isolated from the legacy Polymarket workspace.
  if (location.pathname.startsWith('/dashboard/arbitrage')) {
    return <main className="ms-main strategy-console"><div className="ms-content" tabIndex={0}><Outlet /></div></main>
  }
  return (
    <div className="ms-shell">
      <PrimeTopbar />
      <div className="ms-body">
        <PrimeRail />
        <main className="ms-main">
          <PageLabelBar />
          <StrategySubnav />
          <div className="ms-content" tabIndex={0}>
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  )
}
