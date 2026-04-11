import { createBrowserRouter, Navigate } from 'react-router-dom'
import { App } from '../App'
import { HomePage } from '../pages/HomePage'
import { DetailPage } from '../pages/DetailPage'
import { ChartsPage } from '../pages/ChartsPage'

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
    ],
  },
  {
    path: '*',
    element: <Navigate to="/dashboard" replace />,
  },
])
