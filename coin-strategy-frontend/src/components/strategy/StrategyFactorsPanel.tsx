import { useMemo, useState } from 'react'
import type { StrategyFactor } from '@/types/strategy'
import { formatNumber, formatPercent } from '@/utils/format'

function scoreClass(score: number) {
  if (score >= 70) return 'positive'
  if (score <= 35) return 'negative'
  return 'neutral'
}

function directionLabel(direction: string) {
  if (direction === 'POSITIVE') return '正向'
  if (direction === 'NEGATIVE') return '反向'
  return direction || '-'
}

function metadataRows(metadata: Record<string, unknown>) {
  return Object.entries(metadata || {}).filter(([, value]) => value !== undefined && value !== null && value !== '')
}

export function StrategyFactorsPanel({
  factors,
  loading = false,
}: {
  factors: StrategyFactor[]
  loading?: boolean
}) {
  const [selectedCode, setSelectedCode] = useState<string | null>(null)
  const selected = useMemo(
    () => factors.find((factor) => factor.code === selectedCode) || factors[0],
    [factors, selectedCode]
  )

  if (loading) {
    return (
      <section className="panel">
        <div className="section-head">
          <h2>策略因子</h2>
        </div>
        <div className="loading">因子加载中...</div>
      </section>
    )
  }

  if (factors.length === 0) {
    return (
      <section className="panel">
        <div className="section-head">
          <h2>策略因子</h2>
        </div>
        <div className="empty-state">当前策略尚未接入因子数据</div>
      </section>
    )
  }

  return (
    <section className="panel">
      <div className="section-head">
        <h2>策略因子</h2>
      </div>
      <div className="factor-panel">
        <div className="factor-list">
          {factors.map((factor) => (
            <button
              key={factor.code}
              className={`factor-row ${selected?.code === factor.code ? 'active' : ''}`}
              type="button"
              onClick={() => setSelectedCode(factor.code)}
            >
              <span className="factor-row-main">
                <span>{factor.name}</span>
                <span className="badge">{factor.category}</span>
              </span>
              <span className="factor-row-metrics">
                <span className={scoreClass(factor.score)}>{formatNumber(factor.score, 1)}</span>
                <span>{factor.displayValue}</span>
              </span>
            </button>
          ))}
        </div>

        {selected ? (
          <div className="factor-detail">
            <div className="factor-detail-head">
              <div>
                <h3>{selected.name}</h3>
                <p>{selected.code} · {directionLabel(selected.direction)} · {selected.signal}</p>
              </div>
              <div className={`factor-score ${scoreClass(selected.score)}`}>{formatNumber(selected.score, 1)}</div>
            </div>

            <div className="factor-stat-grid">
              <article>
                <span>权重</span>
                <strong>{formatPercent(selected.weight)}</strong>
              </article>
              <article>
                <span>IC</span>
                <strong>{formatNumber(selected.ic, 3)}</strong>
              </article>
              <article>
                <span>Rank IC</span>
                <strong>{formatNumber(selected.rankIc, 3)}</strong>
              </article>
              <article>
                <span>IR</span>
                <strong>{formatNumber(selected.ir, 2)}</strong>
              </article>
              <article>
                <span>覆盖率</span>
                <strong>{formatPercent(selected.coverage)}</strong>
              </article>
              <article>
                <span>样本数</span>
                <strong>{formatNumber(selected.sampleSize, 0)}</strong>
              </article>
            </div>

            <div className="factor-sparkline" aria-hidden="true">
              {Array.from({ length: 18 }).map((_, index) => {
                const height = 18 + ((selected.score + index * 9) % 58)
                return <i key={index} style={{ height: `${height}%` }} />
              })}
            </div>

            <div className="factor-meta">
              {metadataRows(selected.metadata).map(([key, value]) => (
                <div key={key}>
                  <span>{key}</span>
                  <strong>{String(value)}</strong>
                </div>
              ))}
            </div>
            <div className="table-secondary">Updated {selected.updatedAtBjt || '-'}</div>
          </div>
        ) : null}
      </div>
    </section>
  )
}
