import { Outlet } from 'react-router-dom'
import { PrimeTopbar } from './components/layout/PrimeTopbar'
import { PrimeRail } from './components/layout/PrimeRail'
import { PageLabelBar } from './components/layout/PageLabelBar'

export function App() {
  return (
    <div className="ms-shell">
      <PrimeTopbar />
      <div className="ms-body">
        <PrimeRail />
        <main className="ms-main">
          <PageLabelBar />
          <div className="ms-content">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  )
}
