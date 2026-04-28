import type { ReactNode } from 'react'
import type { StrategyCode } from '@/utils/constants'
import { getStrategyVisual } from '@/utils/strategyVisuals'

export function PrimeCard({
  children,
  className = '',
  pad = true,
}: {
  children: ReactNode
  className?: string
  pad?: boolean
}) {
  return <section className={`ms-card ${pad ? '' : 'no-pad'} ${className}`}>{children}</section>
}

export function CardHeader({
  label,
  sub,
  right,
}: {
  label: string
  sub?: string
  right?: ReactNode
}) {
  return (
    <div className="ms-card-header">
      <div>
        <div className="ms-card-label">{label}</div>
        {sub ? <div className="ms-card-sub">{sub}</div> : null}
      </div>
      {right ? <div className="ms-card-right">{right}</div> : null}
    </div>
  )
}

export function StatTile({
  label,
  value,
  sub,
  tone = 'neutral',
  large = false,
}: {
  label: string
  value: string
  sub?: string
  tone?: 'positive' | 'negative' | 'neutral' | 'warn'
  large?: boolean
}) {
  return (
    <article className="ms-stat-tile">
      <div className="ms-stat-label">{label}</div>
      <div className={`ms-stat-value ${tone} ${large ? 'large' : ''}`}>{value}</div>
      {sub ? <div className="ms-stat-sub">{sub}</div> : null}
    </article>
  )
}

export function TypeBadge({ strategyCode, small = false }: { strategyCode: StrategyCode; small?: boolean }) {
  const visual = getStrategyVisual(strategyCode)
  return (
    <span className={`ms-type-badge ${visual.className} ${small ? 'small' : ''}`}>
      <span>{visual.icon}</span>
      {visual.label}
    </span>
  )
}

export function StatusPill({ status }: { status?: string }) {
  const normalized = String(status || 'UNKNOWN').toUpperCase()
  const tone = normalized === 'ACTIVE' || normalized === 'RUNNING'
    ? 'running'
    : normalized === 'PLANNED'
      ? 'planned'
      : 'stopped'

  return <span className={`ms-status-pill ${tone}`}>{tone === 'running' ? '●' : '■'} {normalized}</span>
}

export function MiniBar({
  value,
  max,
  tone = 'positive',
}: {
  value: number
  max: number
  tone?: 'positive' | 'negative' | 'neutral' | 'warn' | 'accent'
}) {
  const width = max > 0 ? Math.min(100, Math.abs(value) / max * 100) : 0
  return (
    <div className="ms-mini-bar">
      <i className={tone} style={{ width: `${width}%` }} />
    </div>
  )
}
