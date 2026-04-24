import { NavLink, useSearchParams, useNavigate, useLocation } from 'react-router-dom'
import { useStrategyStore } from '@/store/useStrategyStore'
import { STRATEGY_CATALOG } from '@/utils/constants'

export function Sidebar() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const location = useLocation()
  const { selectedStrategy, setSelectedStrategy } = useStrategyStore()
  const strategyParam = searchParams.get('strategy') || selectedStrategy

  // 判断当前激活的策略：根据路由路径确定
  const activeStrategyCode = (() => {
    const path = location.pathname
    const matched = STRATEGY_CATALOG.find((s) => s.route !== '/dashboard' && path.startsWith(s.route))
    if (matched) return matched.code
    // 默认路由 /dashboard 对应 POLYMARKET_COPY
    if (
      path === '/dashboard' ||
      path.startsWith('/dashboard/detail') ||
      path.startsWith('/dashboard/charts') ||
      path.startsWith('/dashboard/monitoring')
    ) {
      return 'POLYMARKET_COPY'
    }
    return strategyParam
  })()

  const handleStrategyClick = (strategy: typeof STRATEGY_CATALOG[number]) => {
    if (strategy.status !== 'ACTIVE') return
    setSelectedStrategy(strategy.code)
    navigate(strategy.route)
  }

  // 子导航链接：根据当前策略决定是否显示详情/图表
  const showSubNav = activeStrategyCode === 'POLYMARKET_COPY'

  return (
    <aside className="sidebar">
      <div className="sidebar-card brand">
        <div className="brand-mark">CS</div>
        <p className="eyebrow">Strategy Platform</p>
        <h2>Coin Strategy</h2>
        <p>多策略量化交易平台，支持 Polymarket 跟单、对冲套利等多种策略。</p>
      </div>

      {showSubNav && (
        <div className="sidebar-card">
          <h3>导航</h3>
          <nav className="sidebar-links">
            <NavLink to={`/dashboard?strategy=${strategyParam}`}>总览</NavLink>
            <NavLink to={`/dashboard/detail?strategy=${strategyParam}`}>详情</NavLink>
            <NavLink to={`/dashboard/charts?strategy=${strategyParam}`}>图表</NavLink>
            <NavLink to={`/dashboard/monitoring?strategy=${strategyParam}`}>监控</NavLink>
          </nav>
        </div>
      )}

      <div className="sidebar-card">
        <h3>策略列表</h3>
        <div className="sidebar-strategies">
          {STRATEGY_CATALOG.map((strategy) => (
            <div
              key={strategy.code}
              className={`sidebar-strategy ${strategy.code === activeStrategyCode ? 'active' : ''} ${strategy.status !== 'ACTIVE' ? 'disabled' : ''}`}
              onClick={() => handleStrategyClick(strategy)}
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
