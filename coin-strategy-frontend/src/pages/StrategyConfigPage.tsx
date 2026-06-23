import { useSearchParams } from 'react-router-dom'
import { CardHeader, PrimeCard, StatusPill, TypeBadge } from '@/components/ui/PrimePrimitives'
import { STRATEGY_CATALOG, normalizeRunnableStrategyCode } from '@/utils/constants'

export function StrategyConfigPage() {
  const [searchParams] = useSearchParams()
  const strategy = normalizeRunnableStrategyCode(searchParams.get('strategy'))
  const meta = STRATEGY_CATALOG.find((item) => item.code === strategy)

  return (
    <div className="ms-page narrow">
      <div className="ms-config-grid">
        <PrimeCard>
          <CardHeader label="Strategy Parameters" right={<TypeBadge strategyCode={strategy} small />} />
          <div className="ms-readonly-config">
            <label>
              <span>Strategy Code</span>
              <input value={strategy} readOnly />
            </label>
            <label>
              <span>Name</span>
              <input value={meta?.name || strategy} readOnly />
            </label>
            <label>
              <span>Status</span>
              <div className="ms-readonly-value"><StatusPill status={meta?.status} /></div>
            </label>
            <label>
              <span>Mode Behavior</span>
              <input value={meta?.modeBehavior || 'ISOLATED'} readOnly />
            </label>
          </div>
          <div className="ms-config-note">
            配置修改页面已临时禁用。当前系统还没有登录和管理员权限模块，为避免外部访问者修改策略参数，运行时配置 API 默认返回 403。
          </div>
        </PrimeCard>

        <PrimeCard>
          <CardHeader label="Risk Management" />
          <div className="ms-readonly-config one">
            <label>
              <span>Supported Modes</span>
              <input value={meta?.supportedModes.join(' / ') || '-'} readOnly />
            </label>
            <label>
              <span>Capabilities</span>
              <textarea value={meta?.capabilities.join(', ') || '-'} readOnly rows={5} />
            </label>
          </div>
        </PrimeCard>
      </div>
    </div>
  )
}
