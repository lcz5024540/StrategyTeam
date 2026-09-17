import { createBrowserRouter, Navigate } from 'react-router-dom'
import { App } from '../App'
import { DashboardPage } from '../pages/DashboardPage'
import { DetailPage } from '../pages/DetailPage'
import { ChartsPage } from '../pages/ChartsPage'
import { MonitoringPage } from '../pages/MonitoringPage'
import { FundingArbPage } from '../pages/FundingArbPage'
import { MarketsPage } from '../pages/MarketsPage'
import { StrategyFactorsPage } from '../pages/StrategyFactorsPage'
import { PolymarketSignalsPage } from '../pages/PolymarketSignalsPage'
import { PolymarketMarketDetailPage } from '../pages/PolymarketMarketDetailPage'
import { MultiStrategyMonitorPage } from '../pages/MultiStrategyMonitorPage'
import { StrategyConfigPage } from '../pages/StrategyConfigPage'
import { StrategyLogsPage } from '../pages/StrategyLogsPage'
import { PolymarketOrdersPage } from '../pages/PolymarketOrdersPage'
import { ArbitrageScannerPage } from '../pages/ArbitrageScannerPage'

export const router = createBrowserRouter([
  {
    path: '/',
    element: <App />,
    children: [
      {
        index: true,
        element: <Navigate to="/dashboard/htx-trend" replace />,
      },
      {
        path: 'dashboard',
        element: <DashboardPage />,
      },
      {
        path: 'dashboard/monitor',
        element: <MultiStrategyMonitorPage />,
      },
      {
        path: 'dashboard/detail',
        element: <DetailPage />,
      },
      {
        path: 'dashboard/signals',
        element: <PolymarketSignalsPage />,
      },
      {
        path: 'dashboard/charts',
        element: <ChartsPage />,
      },
      {
        path: 'dashboard/orders',
        element: <PolymarketOrdersPage />,
      },
      {
        path: 'dashboard/polymarket/markets/:conditionId',
        element: <PolymarketMarketDetailPage />,
      },
      {
        path: 'dashboard/monitoring',
        element: <MonitoringPage />,
      },
      {
        path: 'dashboard/addresses',
        element: <MonitoringPage />,
      },
      {
        path: 'dashboard/markets',
        element: <MarketsPage />,
      },
      {
        path: 'dashboard/factors',
        element: <StrategyFactorsPage />,
      },
      {
        path: 'dashboard/config',
        element: <StrategyConfigPage />,
      },
      {
        path: 'dashboard/logs',
        element: <StrategyLogsPage />,
      },
      {
        path: 'dashboard/funding-arb',
        element: <FundingArbPage />,
      },
      {
        path: 'dashboard/funding-arb/positions',
        element: <FundingArbPage />,
      },
      {
        path: 'dashboard/funding-arb/settlement',
        element: <FundingArbPage />,
      },
      {
        path: 'dashboard/htx-trend',
        element: <Navigate to="/dashboard/arbitrage?mode=HTX_TREND" replace />,
      },
      {
        path: 'dashboard/arbitrage',
        element: <ArbitrageScannerPage />,
      },
    ],
  },
  {
    path: '*',
    element: <Navigate to="/dashboard/htx-trend" replace />,
  },
])
