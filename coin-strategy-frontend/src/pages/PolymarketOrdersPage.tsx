import { useSearchParams } from 'react-router-dom'
import { OrdersTable } from '@/components/tables/OrdersTable'
import { TradesTable } from '@/components/tables/TradesTable'
import { useStrategyStore } from '@/store/useStrategyStore'

type OrderPreset = {
  key: string
  label: string
  pnl?: 'ALL' | 'PROFIT' | 'LOSS' | 'ZERO'
  settlement?: 'ALL' | 'SETTLED' | 'UNSETTLED' | 'EARLY_EXIT'
}

const ORDER_PRESETS: OrderPreset[] = [
  { key: 'all', label: '全部', pnl: 'ALL', settlement: 'ALL' },
  { key: 'profit', label: '盈利', pnl: 'PROFIT', settlement: 'ALL' },
  { key: 'loss', label: '亏损', pnl: 'LOSS', settlement: 'ALL' },
  { key: 'settled', label: '已结算', pnl: 'ALL', settlement: 'SETTLED' },
  { key: 'early-exit', label: '提前退出', pnl: 'ALL', settlement: 'EARLY_EXIT' },
]

export function PolymarketOrdersPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { positionMode } = useStrategyStore()
  const mode = (searchParams.get('mode') || positionMode) as 'PAPER' | 'LIVE'
  const view = searchParams.get('view') === 'orders' ? 'orders' : 'trades'
  const activePreset = ORDER_PRESETS.find((preset) => preset.key === searchParams.get('orders')) || ORDER_PRESETS[0]

  const selectPreset = (preset: OrderPreset) => {
    const next = new URLSearchParams(searchParams)
    next.set('strategy', 'POLYMARKET_COPY')
    if (preset.key === 'all') {
      next.delete('orders')
    } else {
      next.set('orders', preset.key)
    }
    setSearchParams(next)
  }

  const selectView = (nextView: 'trades' | 'orders') => {
    const next = new URLSearchParams(searchParams)
    next.set('strategy', 'POLYMARKET_COPY')
    if (nextView === 'trades') {
      next.delete('view')
    } else {
      next.set('view', nextView)
    }
    setSearchParams(next)
  }

  return (
    <section className="panel">
      <div className="section-head section-head-row">
        <div>
          <h2>{view === 'trades' ? '订单记录' : '执行记录'}</h2>
        </div>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', justifyContent: 'flex-end' }}>
          <div className="segmented-control" aria-label="展示视图">
            <button type="button" className={view === 'trades' ? 'active' : ''} onClick={() => selectView('trades')}>
              订单记录
            </button>
            <button type="button" className={view === 'orders' ? 'active' : ''} onClick={() => selectView('orders')}>
              执行记录
            </button>
          </div>
          <div className="segmented-control" aria-label="订单类型筛选">
            {ORDER_PRESETS.map((preset) => (
              <button
                key={preset.key}
                type="button"
                className={preset.key === activePreset.key ? 'active' : ''}
                onClick={() => selectPreset(preset)}
              >
                {preset.label}
              </button>
            ))}
          </div>
        </div>
      </div>
      {view === 'trades' ? (
        <TradesTable mode={mode} pnl={activePreset.pnl} settlement={activePreset.settlement} />
      ) : (
        <OrdersTable mode={mode} pnl={activePreset.pnl} settlement={activePreset.settlement} />
      )}
    </section>
  )
}
