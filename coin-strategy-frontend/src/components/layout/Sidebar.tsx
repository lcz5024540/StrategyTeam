import { NavLink, useSearchParams, useNavigate, useLocation } from 'react-router-dom'
import { useStrategyStore } from '@/store/useStrategyStore'
import { DEFAULT_STRATEGY_CODE, STRATEGY_CATALOG, normalizeStrategyCode } from '@/utils/constants'

export function Sidebar() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const location = useLocation()
  const { selectedStrategy, setSelectedStrategy } = useStrategyStore()
  const strategyParam = normalizeStrategyCode(searchParams.get('strategy') || selectedStrategy)

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
      path.startsWith('/dashboard/monitoring') ||
      path.startsWith('/dashboard/markets')
    ) {
      return DEFAULT_STRATEGY_CODE
    }
    return strategyParam
  })()

  const handleStrategyClick = (strategy: typeof STRATEGY_CATALOG[number]) => {
    if (strategy.status !== 'ACTIVE') return
    setSelectedStrategy(strategy.code)
    navigate(`${strategy.route}?strategy=${strategy.code}`)
  }

  // 子导航链接：根据当前策略决定是否显示详情/图表
  const showSubNav = activeStrategyCode === 'POLYMARKET_COPY'

  return (
    <aside className="sidebar">
      <div className="sidebar-card brand">
        <div className="brand-mark">CS</div>
        <h2>Coin Strategy</h2>
      </div>

      {showSubNav && (
        <div className="sidebar-card">
          <h3>导航</h3>
          <nav className="sidebar-links">
            <NavLink to={`/dashboard?strategy=${DEFAULT_STRATEGY_CODE}`}>总览</NavLink>
            <NavLink to={`/dashboard/detail?strategy=${DEFAULT_STRATEGY_CODE}`}>详情</NavLink>
            <NavLink to={`/dashboard/charts?strategy=${DEFAULT_STRATEGY_CODE}`}>图表</NavLink>
            <NavLink to={`/dashboard/monitoring?strategy=${DEFAULT_STRATEGY_CODE}`}>监控</NavLink>
            <NavLink to={`/dashboard/markets?strategy=${DEFAULT_STRATEGY_CODE}`}>市场</NavLink>
          </nav>
        </div>
      )}

      <div className="sidebar-card">
        <h3>策略</h3>
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
