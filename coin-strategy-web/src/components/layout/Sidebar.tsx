import { NavLink, useSearchParams } from 'react-router-dom'
import { useStrategyStore } from '@/store/useStrategyStore'
import { STRATEGY_CATALOG } from '@/utils/constants'

export function Sidebar() {
  const [searchParams] = useSearchParams()
  const { selectedStrategy, setSelectedStrategy } = useStrategyStore()
  const strategyParam = searchParams.get('strategy') || selectedStrategy

  return (
    <aside className="sidebar">
      <div className="sidebar-card brand">
        <div className="brand-mark">CS</div>
        <p className="eyebrow">Strategy Platform</p>
        <h2>Coin Strategy</h2>
        <p>多策略量化交易平台，支持 Polymarket 跟单、套利等多种策略。</p>
      </div>

      <div className="sidebar-card">
        <h3>导航</h3>
        <nav className="sidebar-links">
          <NavLink to={`/dashboard?strategy=${strategyParam}`}>总览</NavLink>
          <NavLink to={`/dashboard/detail?strategy=${strategyParam}`}>详情</NavLink>
          <NavLink to={`/dashboard/charts?strategy=${strategyParam}`}>图表</NavLink>
        </nav>
      </div>

      <div className="sidebar-card">
        <h3>策略列表</h3>
        <div className="sidebar-strategies">
          {STRATEGY_CATALOG.map((strategy) => (
            <div
              key={strategy.code}
              className={`sidebar-strategy ${strategy.code === strategyParam ? 'active' : ''} ${strategy.status !== 'ACTIVE' ? 'disabled' : ''}`}
              onClick={() => strategy.status === 'ACTIVE' && setSelectedStrategy(strategy.code)}
            >
              <span>{strategy.name}</span>
              <small>{strategy.status === 'ACTIVE' ? '运行中' : '计划中'}</small>
            </div>
          ))}
        </div>
      </div>
    </aside>
  )
}
