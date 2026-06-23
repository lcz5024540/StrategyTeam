import { Navigate, useSearchParams } from 'react-router-dom'

export function DetailPage() {
  const [searchParams] = useSearchParams()
  const query = searchParams.toString()
  return <Navigate to={query ? `/dashboard?${query}` : '/dashboard'} replace />
}
