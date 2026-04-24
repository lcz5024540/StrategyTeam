import { createBrowserRouter, Navigate } from 'react-router-dom'
import { App } from '../App'
import { HomePage } from '../pages/HomePage'
import { DetailPage } from '../pages/DetailPage'
import { ChartsPage } from '../pages/ChartsPage'
import { MonitoringPage } from '../pages/MonitoringPage'
import { PolymarketHedgePage } from '../pages/PolymarketHedgePage'
import { FundingArbPage } from '../pages/FundingArbPage'
import { MarketsPage } from '../pages/MarketsPage'

export const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,
    children: [
      {
        index: true,
        element: <Navigate to="/dashboard" replace />,
      },
      {
        path: 'dashboard',
        element: <HomePage />,
      },
      {
        path: 'dashboard/detail',
        element: <DetailPage />,
      },
      {
        path: 'dashboard/charts',
        element: <ChartsPage />,
      },
      {
        path: 'dashboard/monitoring',
        element: <MonitoringPage />,
      },
      {
        path: 'dashboard/markets',
        element: <MarketsPage />,
      },
      {
        path: 'dashboard/polymarket-hedge',
        element: <PolymarketHedgePage />,
      },
      {
        path: 'dashboard/funding-arb',
        element: <FundingArbPage />,
      },
    ],
  },
  {
    path: '*',
    element: <Navigate to="/dashboard" replace />,
  },
])
