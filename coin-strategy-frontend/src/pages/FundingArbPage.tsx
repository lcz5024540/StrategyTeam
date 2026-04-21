import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { fundingArbApi } from '@/api'
import { useStrategyStore } from '@/store/useStrategyStore'
import { formatNumber } from '@/utils/format'
import type { FundingArbExecuteParams, FundingArbExecutionSummary } from '@/types/funding-arb'

const DEFAULT_FORM: FundingArbExecuteParams = {
  symbols: 'BTCUSDT,ETHUSDT',
  amount: 50,
  minRate: 0.0005,
  leverage: 3,
}

function normalizeSymbols(raw: string): string[] {
  return Array.from(
    new Set(
      raw
        .split(',')
        .map((item) => item.trim().toUpperCase())
        .filter(Boolean)
    )
  )
}

function buildPayload(form: FundingArbExecuteParams): FundingArbExecuteParams {
  return {
    symbols: normalizeSymbols(form.symbols).join(','),
    amount: Number(form.amount),
    minRate: Number(form.minRate),
    leverage: Number(form.leverage),
  }
}

function validatePayload(payload: FundingArbExecuteParams): string | null {
  if (!payload.symbols) return '请至少输入一个交易对，多个交易对请用逗号分隔。'
  if (!Number.isFinite(payload.amount) || payload.amount <= 0) return '单币种投入金额必须大于 0。'
  if (!Number.isFinite(payload.minRate) || payload.minRate < 0) return '最小资金费率阈值不能小于 0。'
  if (!Number.isInteger(payload.leverage) || payload.leverage <= 0) return '杠杆倍数必须是正整数。'
  return null
}

function parseExecutionSummary(rawLog: string | null): FundingArbExecutionSummary | null {
  if (!rawLog) return null

  const summaryMarker = '=== Summary ==='
  const markerIndex = rawLog.indexOf(summaryMarker)
  if (markerIndex < 0) return null

  const items = rawLog
    .slice(markerIndex + summaryMarker.length)
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.includes('->'))
    .map((line) => {
      const [symbol, ...statusParts] = line.split('->')
      return {
        symbol: symbol.trim(),
        status: statusParts.join('->').trim(),
      }
    })
    .filter((item) => item.symbol && item.status)

  if (items.length === 0) return null

  const opened = items.filter((item) => /^Opened$/i.test(item.status)).length
  const failed = items.filter((item) => /^Failed/i.test(item.status)).length
  const skipped = items.length - opened - failed

  return {
    total: items.length,
    opened,
    skipped,
    failed,
    items,
  }
}

function getStatusBadge(status: string): string {
  if (/^Opened$/i.test(status)) return 'accent'
  if (/^Failed/i.test(status)) return 'danger'
  return 'warn'
}

export function FundingArbPage() {
  const { setSelectedStrategy } = useStrategyStore()
  const [form, setForm] = useState<FundingArbExecuteParams>(DEFAULT_FORM)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [rawLog, setRawLog] = useState<string | null>(null)

  useEffect(() => {
    setSelectedStrategy('FUNDING_ARB')
  }, [setSelectedStrategy])

  const normalizedSymbols = useMemo(() => normalizeSymbols(form.symbols), [form.symbols])
  const summary = useMemo(() => parseExecutionSummary(rawLog), [rawLog])

  const handleFieldChange = <K extends keyof FundingArbExecuteParams>(key: K, value: FundingArbExecuteParams[K]) => {
    setForm((current) => ({
      ...current,
      [key]: value,
    }))
  }

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()

    const payload = buildPayload(form)
    const validationError = validatePayload(payload)
    if (validationError) {
      setError(validationError)
      return
    }

    setSubmitting(true)
    setError(null)

    try {
      const log = await fundingArbApi.execute(payload)
      setForm(payload)
      setRawLog(log)
    } catch (submitError) {
      setError(submitError instanceof Error ? submitError.message : '执行请求失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      <section className="panel">
        <div className="section-head">
          <h2>策略定位</h2>
          <p>当前页面优先服务人工触发与执行结果审阅，不伪造后端尚未提供的监控面板。</p>
        </div>
        <div className="metric-grid">
          <article className="metric-card">
            <div className="label">支持交易所</div>
            <div className="value neutral">BINANCE</div>
            <div className="hint">单交易所现货多 + 合约空</div>
          </article>
          <article className="metric-card">
            <div className="label">执行粒度</div>
            <div className="value neutral">批量触发</div>
            <div className="hint">主输入支持逗号分隔多个 symbol</div>
          </article>
          <article className="metric-card">
            <div className="label">后端能力</div>
            <div className="value neutral">EXECUTE ONLY</div>
            <div className="hint">当前仅接入执行接口与文本日志返回</div>
          </article>
          <article className="metric-card">
            <div className="label">页面口径</div>
            <div className="value neutral">MODE AGNOSTIC</div>
            <div className="hint">右上角 PAPER / LIVE 与 days 暂不影响本页</div>
          </article>
        </div>
      </section>

      <div className="split-grid funding-arb-main-grid">
        <section className="panel">
          <div className="section-head">
            <h2>执行控制台</h2>
            <p>按资金费率阈值手动触发 Binance 资金费套利，支持单次批量评估多个交易对。</p>
          </div>
          <form className="funding-arb-form" onSubmit={handleSubmit}>
            <label className="funding-arb-field">
              <span>交易对列表</span>
              <textarea
                value={form.symbols}
                onChange={(event) => handleFieldChange('symbols', event.target.value)}
                placeholder="BTCUSDT,ETHUSDT"
                rows={4}
              />
              <small>多个 symbol 用英文逗号分隔；提交前会自动大写、去重、去空格。</small>
            </label>

            <div className="funding-arb-form-grid">
              <label className="funding-arb-field">
                <span>单币种投入金额</span>
                <input
                  type="number"
                  min="0"
                  step="0.01"
                  value={form.amount}
                  onChange={(event) => handleFieldChange('amount', Number(event.target.value))}
                />
                <small>单位 USDT，按每个 symbol 单独下发。</small>
              </label>

              <label className="funding-arb-field">
                <span>最小资金费率阈值</span>
                <input
                  type="number"
                  min="0"
                  step="0.0001"
                  value={form.minRate}
                  onChange={(event) => handleFieldChange('minRate', Number(event.target.value))}
                />
                <small>低于该阈值时后端会直接跳过。</small>
              </label>

              <label className="funding-arb-field">
                <span>杠杆倍数</span>
                <input
                  type="number"
                  min="1"
                  step="1"
                  value={form.leverage}
                  onChange={(event) => handleFieldChange('leverage', Number(event.target.value))}
                />
                <small>仅作用于期货腿；非正整数会被前端拦截。</small>
              </label>
            </div>

            <div className="funding-arb-inline-summary">
              <span className="badge accent">{normalizedSymbols.length || 0} 个标的</span>
              <span className="badge">{normalizedSymbols.join(', ') || '等待输入'}</span>
              <span className="badge">Amount {formatNumber(form.amount, 2)} USDT</span>
              <span className="badge">MinRate {formatNumber(form.minRate, 4)}</span>
              <span className="badge">Leverage {formatNumber(form.leverage, 0)}x</span>
            </div>

            {error ? <div className="funding-arb-error">{error}</div> : null}

            <div className="funding-arb-actions">
              <button type="submit" className="btn btn-primary" disabled={submitting}>
                {submitting ? '执行中...' : '执行套利'}
              </button>
              <button
                type="button"
                className="btn btn-secondary"
                disabled={submitting}
                onClick={() => {
                  setForm(DEFAULT_FORM)
                  setError(null)
                }}
              >
                重置参数
              </button>
            </div>
          </form>
        </section>

        <section className="panel">
          <div className="section-head">
            <h2>执行约束</h2>
            <p>本页当前只覆盖已有后端能力，不把未实现的监控数据伪装成实时看板。</p>
          </div>
          <div className="funding-arb-note-list">
            <article className="funding-arb-note-card">
              <h3>已接入</h3>
              <p>人工触发 FundingArb 执行、批量 symbol 输入、后端文本日志回显、Summary 结果解析。</p>
            </article>
            <article className="funding-arb-note-card">
              <h3>未接入</h3>
              <p>持仓列表、订单明细、收益时间线、已实现 PnL 聚合、历史执行任务回放。</p>
            </article>
            <article className="funding-arb-note-card">
              <h3>风险提醒</h3>
              <p>后端仍会基于资金费率阈值、基差方向和风控结果决定是否开仓；前端不会绕过这些限制。</p>
            </article>
            <article className="funding-arb-note-card">
              <h3>结果来源</h3>
              <p>执行成功或跳过的判定完全以后端返回日志为准；页面只负责结构化展示。</p>
            </article>
          </div>
        </section>
      </div>

      <section className="panel">
        <div className="section-head">
          <h2>执行摘要</h2>
          <p>解析后端日志中的 Summary 段，快速查看本次批量触发的整体结果。</p>
        </div>
        {summary ? (
          <>
            <div className="metric-grid">
              <article className="metric-card">
                <div className="label">总标的数</div>
                <div className="value neutral">{summary.total}</div>
                <div className="hint">本次 Summary 中返回的 symbol 总数</div>
              </article>
              <article className="metric-card">
                <div className="label">Opened</div>
                <div className="value positive">{summary.opened}</div>
                <div className="hint">后端确认已开仓</div>
              </article>
              <article className="metric-card">
                <div className="label">Skipped</div>
                <div className="value neutral">{summary.skipped}</div>
                <div className="hint">包含阈值不足、风险拒绝等未执行情形</div>
              </article>
              <article className="metric-card">
                <div className="label">Failed</div>
                <div className="value negative">{summary.failed}</div>
                <div className="hint">执行时抛出异常或下单失败</div>
              </article>
            </div>

            <div className="table-wrap" style={{ marginTop: '1rem' }}>
              <table>
                <thead>
                  <tr>
                    <th>Symbol</th>
                    <th>结果</th>
                  </tr>
                </thead>
                <tbody>
                  {summary.items.map((item) => (
                    <tr key={`${item.symbol}-${item.status}`}>
                      <td>{item.symbol}</td>
                      <td>
                        <span className={`badge ${getStatusBadge(item.status)}`}>{item.status}</span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        ) : (
          <div className="empty-state">尚未产生可解析的执行摘要，先提交一次执行请求。</div>
        )}
      </section>

      <section className="panel">
        <div className="section-head">
          <h2>原始执行日志</h2>
          <p>保留后端原始返回，便于核对资金费率、计划价格、风控拒绝原因和最终执行结果。</p>
        </div>
        {rawLog ? (
          <pre className="funding-arb-log">
            <code>{rawLog}</code>
          </pre>
        ) : (
          <div className="empty-state">执行日志为空，提交请求后会在这里展示后端原文。</div>
        )}
      </section>
    </>
  )
}
