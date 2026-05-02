import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { strategyApi } from '@/api'
import { PrimeCard } from '@/components/ui/PrimePrimitives'
import type { StrategyFlowLogEvent, StrategyLogEntry, StrategyLogFilter, StrategyLogFlow, StrategyLogLevel, StrategyLogSource } from '@/types/strategy'
import { type PositionMode } from '@/utils/constants'
import { isEnterableStrategy, toRegistryItems } from '@/utils/strategyViewModels'

const SOURCES: Array<{ value: StrategyLogSource; label: string }> = [
  { value: 'app', label: 'Application' },
  { value: 'error', label: 'Errors' },
  { value: 'startup', label: 'Startup' },
]
const LEVELS: StrategyLogLevel[] = ['ALL', 'INFO', 'WARN', 'ERROR', 'DEBUG']
const FLOW_STAGES = [
  { code: 'S1_EVENT_CAPTURED', label: 'S1 事件捕获 / Event Captured' },
  { code: 'S2_PROFILE_ADMISSION', label: 'S2 钱包准入 / Profile Admission' },
  { code: 'S3_CANDIDATE_ENRICHED', label: 'S3 候选增强 / Candidate Enriched' },
  { code: 'S4_MARKET_CHECK', label: 'S4 市场检查 / Market Check' },
  { code: 'S5_RISK_EVALUATED', label: 'S5 风控评估 / Risk Evaluated' },
  { code: 'S6_TASK_CREATED', label: 'S6 任务创建 / Task Created' },
  { code: 'S7_TASK_CLAIMED', label: 'S7 任务抢占 / Task Claimed' },
  { code: 'S8_ORDER_EXECUTED', label: 'S8 订单执行 / Order Executed' },
]

type LogTab = 'flow' | 'raw'

export function StrategyLogsPage() {
  const [searchParams] = useSearchParams()
  const mode = (searchParams.get('mode') || 'PAPER') as PositionMode
  const [activeTab, setActiveTab] = useState<LogTab>('flow')
  const [source, setSource] = useState<StrategyLogSource>('app')
  const [strategyFilter, setStrategyFilter] = useState<StrategyLogFilter>('POLYMARKET_COPY')
  const [levelFilter, setLevelFilter] = useState<StrategyLogLevel>('ALL')
  const [search, setSearch] = useState('')
  const [wallet, setWallet] = useState('')
  const [eventId, setEventId] = useState('')
  const [followTaskId, setFollowTaskId] = useState('')
  const [marketId, setMarketId] = useState('')
  const [selectedFlowId, setSelectedFlowId] = useState<string | null>(null)
  const [autoRefresh, setAutoRefresh] = useState(true)

  const { data: overview } = useQuery({
    queryKey: ['strategyOverview', mode],
    queryFn: () => strategyApi.getOverview(mode),
  })

  const strategies = useMemo(() => toRegistryItems(overview?.strategies).filter(isEnterableStrategy), [overview?.strategies])

  const {
    data: flows = [],
    error: flowError,
    isError: flowIsError,
    isFetching: flowFetching,
    isLoading: flowLoading,
    refetch: refetchFlows,
  } = useQuery({
    queryKey: ['strategyLogFlows', source, strategyFilter, search, wallet, eventId, followTaskId, marketId],
    queryFn: () => strategyApi.getLogFlows({
      source,
      strategy: strategyFilter === 'ALL' ? 'POLYMARKET_COPY' : strategyFilter,
      q: search,
      wallet,
      eventId,
      followTaskId,
      marketId,
      limit: 200,
    }),
    refetchInterval: autoRefresh && activeTab === 'flow' ? 5000 : false,
    retry: false,
  })

  const {
    data: logs = [],
    error,
    isError,
    isFetching,
    isLoading,
    refetch,
  } = useQuery({
    queryKey: ['strategyLogs', source, levelFilter, strategyFilter, search],
    queryFn: () => strategyApi.getLogs({
      source,
      level: levelFilter,
      strategy: strategyFilter,
      q: search,
      limit: 200,
    }),
    refetchInterval: autoRefresh && activeTab === 'raw' ? 5000 : false,
    retry: false,
  })

  const selectedFlow = useMemo(() => {
    if (flows.length === 0) return null
    return flows.find((item) => item.flowId === selectedFlowId) || flows[0]
  }, [flows, selectedFlowId])

  return (
    <div className="ms-page">
      <div className="ms-log-tabs" role="tablist">
        <button type="button" className={activeTab === 'flow' ? 'active' : ''} onClick={() => setActiveTab('flow')}>Flow Timeline</button>
        <button type="button" className={activeTab === 'raw' ? 'active' : ''} onClick={() => setActiveTab('raw')}>Raw Logs</button>
      </div>

      <div className="ms-log-toolbar">
        <strong>{activeTab === 'flow' ? 'POLYMARKET FLOW' : 'RUNTIME LOGS'}</strong>
        <select value={source} onChange={(event) => setSource(event.target.value as StrategyLogSource)}>
          {SOURCES.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
        </select>
        <select value={strategyFilter} onChange={(event) => setStrategyFilter(event.target.value as StrategyLogFilter)}>
          <option value="ALL">All Strategies</option>
          <option value="SYSTEM">System</option>
          <option value="POLYMARKET_COPY">Polymarket Copy</option>
          {strategies.map((strategy) => <option key={strategy.code} value={strategy.code}>{strategy.name}</option>)}
        </select>
        {activeTab === 'raw' ? (
          <select value={levelFilter} onChange={(event) => setLevelFilter(event.target.value as StrategyLogLevel)}>
            {LEVELS.map((level) => <option key={level} value={level}>{level}</option>)}
          </select>
        ) : null}
        <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search flow, gate, reason..." />
        {activeTab === 'flow' ? (
          <>
            <input value={wallet} onChange={(event) => setWallet(event.target.value)} placeholder="Wallet" />
            <input value={eventId} onChange={(event) => setEventId(event.target.value)} placeholder="Event ID" />
            <input value={followTaskId} onChange={(event) => setFollowTaskId(event.target.value)} placeholder="Task ID" />
            <input value={marketId} onChange={(event) => setMarketId(event.target.value)} placeholder="Market ID" />
          </>
        ) : null}
        <label className="ms-log-toggle">
          <input type="checkbox" checked={autoRefresh} onChange={(event) => setAutoRefresh(event.target.checked)} />
          <span>Auto refresh</span>
        </label>
        <button type="button" className="ms-log-action" onClick={() => activeTab === 'flow' ? refetchFlows() : refetch()} disabled={activeTab === 'flow' ? flowFetching : isFetching}>
          {(activeTab === 'flow' ? flowFetching : isFetching) ? 'Refreshing' : 'Refresh'}
        </button>
        <span>{activeTab === 'flow' ? flows.length : logs.length} entries</span>
      </div>

      <div className="ms-log-note">
        {activeTab === 'flow'
          ? 'Grouped Polymarket flow logs from [POLY_FLOW]. Each row traces one signal through capture, risk, task creation and execution.'
          : 'Showing sanitized backend log files from the configured LOG_DIR. Results are capped to the latest 200 matching entries.'}
      </div>

      {activeTab === 'flow' ? (
        <FlowTimeline
          flows={flows}
          selectedFlow={selectedFlow}
          setSelectedFlowId={setSelectedFlowId}
          isLoading={flowLoading}
          isError={flowIsError}
          error={flowError}
        />
      ) : (
        <RawLogStream logs={logs} isLoading={isLoading} isError={isError} error={error} />
      )}
    </div>
  )
}

function FlowTimeline({
  flows,
  selectedFlow,
  setSelectedFlowId,
  isLoading,
  isError,
  error,
}: {
  flows: StrategyLogFlow[]
  selectedFlow: StrategyLogFlow | null
  setSelectedFlowId: (value: string) => void
  isLoading: boolean
  isError: boolean
  error: unknown
}) {
  if (isLoading) return <PrimeCard><div className="loading">流程日志加载中...</div></PrimeCard>
  if (isError) return <PrimeCard><div className="empty-state">流程日志接口请求失败：{error instanceof Error ? error.message : 'unknown error'}</div></PrimeCard>
  if (flows.length === 0) return <PrimeCard><div className="empty-state">当前筛选条件下暂无 Polymarket flow 日志</div></PrimeCard>

  return (
    <div className="ms-flow-layout">
      <PrimeCard pad={false}>
        <div className="ms-flow-list">
          {flows.map((flow) => (
            <button
              type="button"
              key={flow.flowId}
              className={selectedFlow?.flowId === flow.flowId ? 'active' : ''}
              onClick={() => setSelectedFlowId(flow.flowId)}
            >
              <span>{flow.latestTimestamp || '-'}</span>
              <strong>{shortAddress(flow.wallet) || flow.flowId}</strong>
              <em>{flow.marketId || '-'} / {flow.outcome || '-'}</em>
              <b className={statusClass(flow.status)}>{flow.status || 'ACTIVE'}</b>
              {flow.firstRejectGate ? <small>{flow.firstRejectGate}</small> : null}
            </button>
          ))}
        </div>
      </PrimeCard>

      <PrimeCard pad={false}>
        <div className="ms-flow-detail">
          <header>
            <div>
              <strong>{selectedFlow?.flowId}</strong>
              <FormattedFlowMessage message={selectedFlow?.summary} compact />
            </div>
            <div className="address-badge-stack">
              <span className={statusClass(selectedFlow?.status)}>{selectedFlow?.status || 'ACTIVE'}</span>
              {selectedFlow?.mode ? <span className="badge">{selectedFlow.mode}</span> : null}
              {selectedFlow?.entryLane ? <span className="badge accent">{selectedFlow.entryLane}</span> : null}
            </div>
          </header>
          <div className="ms-flow-meta">
            <span>wallet <b>{selectedFlow?.wallet || '-'}</b></span>
            <span>event <b>{selectedFlow?.eventId || '-'}</b></span>
            <span>task <b>{selectedFlow?.followTaskId || '-'}</b></span>
            <span>market <b>{selectedFlow?.marketId || '-'}</b></span>
            <span>stake <b>{selectedFlow?.stakeUsdc || '-'}</b></span>
            <span>gate <b>{selectedFlow?.firstRejectGate || selectedFlow?.gateHighWaterMark || '-'}</b></span>
          </div>
          <div className="ms-flow-stages">
            {FLOW_STAGES.map((stage) => {
              const entries = selectedFlow?.entries.filter((entry) => isFlowStage(entry.flowStage, stage)) || []
              return <FlowStage key={stage.code} stage={stage} entries={entries} />
            })}
          </div>
        </div>
      </PrimeCard>
    </div>
  )
}

function FlowStage({ stage, entries }: { stage: { code: string; label: string }; entries: StrategyFlowLogEvent[] }) {
  const latest = entries[entries.length - 1]
  return (
    <section className={`ms-flow-stage ${entries.length ? 'has-data' : ''}`}>
      <div className="ms-flow-stage-marker" />
      <div className="ms-flow-stage-body">
        <header>
          <StageTitle stage={stage} />
          {latest ? <span className={statusClass(latest.result)}>{latest.result}</span> : <span className="badge">waiting</span>}
        </header>
        {entries.length === 0 ? (
          <p>No log event captured for this stage.</p>
        ) : entries.map((entry, index) => (
          <article key={`${entry.timestamp}-${entry.action}-${index}`}>
            <div>
              <span>{entry.timestamp}</span>
              <b>{entry.action || '-'}</b>
            </div>
            <FormattedFlowMessage message={entry.reasonSummary || entry.rawMessage} />
            <div className="address-badge-stack">
              {entry.firstRejectGate ? <span className="badge danger">{entry.firstRejectGate}</span> : null}
              {entry.blockerCategories ? <span className="badge warn">{entry.blockerCategories}</span> : null}
              {entry.matchedStrategies ? <span className="badge accent">{entry.matchedStrategies}</span> : null}
              {entry.gateHighWaterMark ? <span className="badge">gate {entry.gateHighWaterMark}</span> : null}
            </div>
          </article>
        ))}
      </div>
    </section>
  )
}

function StageTitle({ stage }: { stage: { code: string; label: string } }) {
  const [index, ...rest] = stage.label.split(/\s+/)
  return (
    <strong className="ms-stage-title">
      <span>{index}</span>
      {rest.length ? <em>{rest.join(' ')}</em> : null}
    </strong>
  )
}

function isFlowStage(value: string | null | undefined, stage: { code: string; label: string }) {
  return value === stage.code || value === stage.label
}

function FormattedFlowMessage({ message, compact = false }: { message?: string | null; compact?: boolean }) {
  if (!message) return <div className="ms-flow-message">-</div>
  const sections = parseFlowMessage(message)
  return (
    <div className={compact ? 'ms-flow-message compact' : 'ms-flow-message'}>
      {sections.map((section, sectionIndex) => (
        <section key={`${section.label || 'message'}-${sectionIndex}`}>
          {section.label ? <span className="ms-flow-message-label">{section.label}</span> : null}
          <div className="ms-flow-message-content">
            {section.fields.map((field, fieldIndex) => (
              <span className="ms-flow-message-field" key={`${sectionIndex}-${field.key}-${fieldIndex}`}>
                <b>{field.key}</b>
                <em>{field.value}</em>
              </span>
            ))}
            {section.text ? <p>{section.text}</p> : null}
          </div>
        </section>
      ))}
    </div>
  )
}

function parseFlowMessage(message: string) {
  const parts = message.split(/\s+\|\s+/).map((part) => part.trim()).filter(Boolean)
  return parts.map((part) => {
    const match = part.match(/^\[([^\]]+)]\s*(.*)$/)
    const label = match?.[1]
    const body = match?.[2] ?? part
    return { label, ...parseKeyValueBody(body) }
  })
}

function parseKeyValueBody(body: string) {
  const fields: Array<{ key: string; value: string }> = []
  const textParts: string[] = []
  let cursor = 0
  let textStart = 0

  while (cursor < body.length) {
    const keyStart = findNextKeyStart(body, cursor)
    if (keyStart < 0) break
    const equalsIndex = body.indexOf('=', keyStart)
    if (equalsIndex < 0) break

    const pendingText = body.slice(textStart, keyStart).trim()
    if (pendingText) textParts.push(pendingText)

    const key = body.slice(keyStart, equalsIndex)
    const parsed = parseFieldValue(body, equalsIndex + 1)
    fields.push({ key, value: parsed.value })
    cursor = parsed.next
    textStart = parsed.next
  }

  const tail = body.slice(textStart).trim()
  if (tail) textParts.push(tail)
  return { fields, text: textParts.join(' ').trim() }
}

function findNextKeyStart(value: string, from: number) {
  const keyPattern = /(?:^|\s)([A-Za-z][A-Za-z0-9_]*)=/g
  keyPattern.lastIndex = from
  const match = keyPattern.exec(value)
  if (!match || match.index < 0) return -1
  return match.index + match[0].indexOf(match[1])
}

function parseFieldValue(value: string, from: number) {
  if (value[from] === '"') {
    let cursor = from + 1
    let parsed = ''
    let escaping = false
    while (cursor < value.length) {
      const ch = value[cursor]
      if (escaping) {
        parsed += ch
        escaping = false
      } else if (ch === '\\') {
        escaping = true
      } else if (ch === '"') {
        return { value: parsed, next: cursor + 1 }
      } else {
        parsed += ch
      }
      cursor += 1
    }
    return { value: parsed, next: cursor }
  }

  let cursor = from
  while (cursor < value.length) {
    if (/\s/.test(value[cursor]) && /^[A-Za-z][A-Za-z0-9_]*=/.test(value.slice(cursor + 1))) {
      break
    }
    cursor += 1
  }
  return { value: value.slice(from, cursor).trim(), next: cursor }
}

function RawLogStream({ logs, isLoading, isError, error }: { logs: StrategyLogEntry[]; isLoading: boolean; isError: boolean; error: unknown }) {
  return (
    <PrimeCard pad={false}>
      <div className="ms-log-stream">
        {isLoading ? (
          <div className="loading">运行日志加载中...</div>
        ) : isError ? (
          <div className="empty-state">日志接口请求失败：{error instanceof Error ? error.message : 'unknown error'}</div>
        ) : logs.length === 0 ? (
          <div className="empty-state">当前筛选条件下暂无日志</div>
        ) : logs.map((log, index) => (
          <div key={`${log.timestamp}-${log.level}-${index}`} className="ms-log-row">
            <span>{log.timestamp}</span>
            <strong className={String(log.level).toLowerCase()}>{log.level}</strong>
            <em title={log.logger}>[{log.strategy}]</em>
            <p>{log.message}</p>
          </div>
        ))}
      </div>
    </PrimeCard>
  )
}

function shortAddress(value?: string | null) {
  if (!value) return ''
  return value.length > 14 ? `${value.slice(0, 6)}...${value.slice(-4)}` : value
}

function statusClass(value?: string | null) {
  const normalized = String(value || '').toUpperCase()
  if (normalized.includes('FAIL') || normalized.includes('拒绝') || normalized.includes('BLOCK')) return 'badge danger'
  if (normalized.includes('PENDING') || normalized.includes('WAIT') || normalized.includes('跳过')) return 'badge warn'
  if (normalized.includes('SUCCESS') || normalized.includes('EXECUT') || normalized.includes('成功') || normalized.includes('通过')) return 'badge accent'
  return 'badge'
}
