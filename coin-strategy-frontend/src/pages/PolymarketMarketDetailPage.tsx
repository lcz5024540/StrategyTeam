import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { walletApi } from '@/api'
import { truncateText } from '@/utils/format'

function asText(value: unknown): string {
  if (value == null || value === '') return '-'
  if (Array.isArray(value)) return value.join(', ')
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function formatMarketTime(value?: number | null): string {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

export function PolymarketMarketDetailPage() {
  const { conditionId = '' } = useParams()
  const [searchParams] = useSearchParams()
  const questionHint = searchParams.get('question')
  const outcomeHint = searchParams.get('outcome')
  const decodedConditionId = useMemo(() => decodeURIComponent(conditionId), [conditionId])

  const linkQuery = useQuery({
    queryKey: ['polymarket-market-link', decodedConditionId, questionHint, outcomeHint],
    queryFn: () => walletApi.getMarketLink(decodedConditionId, questionHint, outcomeHint),
    enabled: Boolean(decodedConditionId),
  })

  const metadataQuery = useQuery({
    queryKey: ['polymarket-clob-market', decodedConditionId],
    queryFn: () => walletApi.getClobMarket(decodedConditionId),
    enabled: Boolean(decodedConditionId),
  })

  const priceQuery = useQuery({
    queryKey: ['polymarket-price-reference', decodedConditionId, outcomeHint],
    queryFn: () => walletApi.getMarketPriceReference(decodedConditionId, outcomeHint || undefined, undefined, undefined, questionHint),
    enabled: Boolean(decodedConditionId),
    retry: false,
  })

  const link = linkQuery.data
  const metadata = metadataQuery.data || {}
  const outcomeTokenIds = link?.outcomeTokenIds || metadata.outcomeTokenIds || {}
  const outcomes = link?.outcomes || metadata.outcomes || Object.keys(outcomeTokenIds)
  const externalUrl = link?.externalMarketUrl || ''

  return (
    <div className="page-shell">
      <div className="section-head">
        <div>
          <p>Polymarket 市场</p>
          <h2>{truncateText(link?.question || metadata.question || questionHint || decodedConditionId, 84)}</h2>
        </div>
        <div className="toolbar">
          <Link className="btn btn-secondary" to="/dashboard/orders?strategy=POLYMARKET_COPY">
            返回订单
          </Link>
          {externalUrl ? (
            <a className="btn btn-primary" href={externalUrl} target="_blank" rel="noreferrer">
              打开 Polymarket
            </a>
          ) : null}
        </div>
      </div>

      {linkQuery.isLoading || metadataQuery.isLoading ? (
        <div className="loading">加载市场信息中...</div>
      ) : linkQuery.error && metadataQuery.error ? (
        <div className="query-state">
          <strong>市场信息暂时不可用</strong>
          <div className="table-subtext">{String(linkQuery.error.message || metadataQuery.error.message)}</div>
        </div>
      ) : (
        <>
          <div className="metric-grid">
            <div className="metric-card">
              <div className="label">链接状态</div>
              <div className="value">{link?.linkStatus || 'UNKNOWN'}</div>
              <div className="hint">{link?.metadataSource || metadata.resolutionSource || '-'}</div>
            </div>
            <div className="metric-card">
              <div className="label">市场状态</div>
              <div className="value">{link?.active === false || metadata.closed ? '非活跃' : '活跃'}</div>
              <div className="hint">closed={asText(link?.closed ?? metadata.closed)} / accepting={asText(link?.acceptingOrders ?? metadata.acceptingOrders)}</div>
            </div>
            <div className="metric-card">
              <div className="label">预计结算</div>
              <div className="value">{formatMarketTime(link?.marketEndAt || metadata.endAt)}</div>
              <div className="hint">{link?.category || metadata.category || '-'}</div>
            </div>
          </div>

          <div className="table-wrap">
            <table>
              <tbody>
                <tr>
                  <th>conditionId</th>
                  <td className="mono">{decodedConditionId}</td>
                </tr>
                <tr>
                  <th>slug</th>
                  <td>{link?.marketSlug || metadata.slug || '-'}</td>
                </tr>
                <tr>
                  <th>outcome</th>
                  <td>{outcomeHint || link?.outcome || '-'}</td>
                </tr>
                <tr>
                  <th>outcomes</th>
                  <td>{asText(outcomes)}</td>
                </tr>
                <tr>
                  <th>token 映射</th>
                  <td className="mono">{asText(outcomeTokenIds)}</td>
                </tr>
                <tr>
                  <th>价格参考</th>
                  <td className="mono">{priceQuery.data ? asText(priceQuery.data) : priceQuery.isError ? '暂不可用' : '加载中...'}</td>
                </tr>
                <tr>
                  <th>外部链接</th>
                  <td>
                    {externalUrl ? (
                      <a className="market-link" href={externalUrl} target="_blank" rel="noreferrer">
                        {externalUrl}
                      </a>
                    ) : (
                      '尚未定位到 Polymarket 精确页面'
                    )}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  )
}
