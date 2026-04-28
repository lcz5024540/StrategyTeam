import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { strategyApi } from '@/api'
import { PrimeCard } from '@/components/ui/PrimePrimitives'
import { type PositionMode } from '@/utils/constants'
import { isEnterableStrategy, toRegistryItems } from '@/utils/strategyViewModels'

const LEVELS = ['ALL', 'info', 'warn'] as const

export function StrategyLogsPage() {
  const [searchParams] = useSearchParams()
  const mode = (searchParams.get('mode') || 'PAPER') as PositionMode
  const [strategyFilter, setStrategyFilter] = useState('ALL')
  const [levelFilter, setLevelFilter] = useState<typeof LEVELS[number]>('ALL')
  const [search, setSearch] = useState('')

  const { data: overview } = useQuery({
    queryKey: ['strategyOverview', mode],
    queryFn: () => strategyApi.getOverview(mode),
  })

  const logs = useMemo(() => {
    const strategies = toRegistryItems(overview?.strategies).filter(isEnterableStrategy)
    return strategies.flatMap((strategy, index) => [
      {
        time: new Date(Date.now() - index * 180000).toTimeString().slice(0, 8),
        strategy: strategy.name,
        level: strategy.status === 'ACTIVE' ? 'info' : 'warn',
        message: `${strategy.code} overview refreshed in ${mode} mode`,
      },
      {
        time: new Date(Date.now() - index * 240000 - 60000).toTimeString().slice(0, 8),
        strategy: strategy.name,
        level: strategy.modeBehavior === 'UNSUPPORTED' ? 'warn' : 'info',
        message: `capabilities: ${strategy.capabilities?.join(', ') || '-'}`,
      },
    ])
  }, [mode, overview?.strategies])

  const filtered = logs.filter((log) =>
    (strategyFilter === 'ALL' || log.strategy === strategyFilter) &&
    (levelFilter === 'ALL' || log.level === levelFilter) &&
    (search === '' || `${log.strategy} ${log.message}`.toLowerCase().includes(search.toLowerCase()))
  )

  const strategies = toRegistryItems(overview?.strategies).filter(isEnterableStrategy)

  return (
    <div className="ms-page">
      <div className="ms-log-toolbar">
        <strong>SYSTEM EVENTS PREVIEW</strong>
        <select value={strategyFilter} onChange={(event) => setStrategyFilter(event.target.value)}>
          <option value="ALL">All Strategies</option>
          {strategies.map((strategy) => <option key={strategy.code} value={strategy.name}>{strategy.name}</option>)}
        </select>
        <select value={levelFilter} onChange={(event) => setLevelFilter(event.target.value as typeof LEVELS[number])}>
          {LEVELS.map((level) => <option key={level} value={level}>{level.toUpperCase()}</option>)}
        </select>
        <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search..." />
        <span>{filtered.length} entries</span>
      </div>

      <div className="ms-preview-note">
        当前日志页为前端预览数据；后端审计日志接口接入后会替换为真实日志流。
      </div>

      <PrimeCard pad={false}>
        <div className="ms-log-stream">
          {filtered.map((log, index) => (
            <div key={`${log.time}-${index}`} className="ms-log-row">
              <span>{log.time}</span>
              <strong className={log.level}>{log.level.toUpperCase()}</strong>
              <em>[{log.strategy.split(' ')[0]}]</em>
              <p>{log.message}</p>
            </div>
          ))}
        </div>
      </PrimeCard>
    </div>
  )
}
