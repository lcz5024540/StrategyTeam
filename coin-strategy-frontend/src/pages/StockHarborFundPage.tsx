import { useMemo, useState } from 'react'

type VaultStatus = 'TEE ATTESTED' | 'REVIEWING' | 'GUARDIAN WATCH'
type VaultCategory = 'AI Manager' | 'Community Curator' | 'Institutional'
type FilterKey = 'all' | 'tee' | 'low-drawdown' | 'new' | 'watchlist'
type SortKey = 'quality' | 'return' | 'aum' | 'sharpe'

interface StockHarborVault {
  id: string
  rank: number
  name: string
  manager: string
  category: VaultCategory
  status: VaultStatus
  aum: string
  aumValue: number
  return30d: number
  sharpe: number
  drawdown: number
  turnover: number
  managerBond: string
  investors: number
  ageDays: number
  watchlisted: boolean
  holdings: string[]
  teeQuote: string
  attestationAge: string
  oracleFreshness: string
  policyGates: string[]
}

const FILTERS: { key: FilterKey; label: string; description: string }[] = [
  { key: 'all', label: 'All Vaults', description: '公开募集中与观察中策略' },
  { key: 'tee', label: 'TEE Attested', description: '经理运行环境有 quote' },
  { key: 'low-drawdown', label: 'Low Drawdown', description: '最大回撤低于 4%' },
  { key: 'new', label: 'New Managers', description: '30 天内新经理池' },
  { key: 'watchlist', label: 'Watchlist', description: '高风险但值得跟踪' },
]

const METRICS = [
  { label: 'Active Vaults', value: '42', sub: '18 个通过 TEE quote' },
  { label: 'Verified AUM', value: '$18.6M', sub: 'USDC + tokenized equities' },
  { label: 'Median 30D Return', value: '+4.8%', sub: '扣除管理费后' },
  { label: 'Blocked Transfers', value: '317', sub: '策略外路由被拒绝' },
]

const VAULTS: StockHarborVault[] = [
  {
    id: 'hv-apple-shield',
    rank: 1,
    name: 'Harbor Apple Shield',
    manager: '0x7a4C…91bE',
    category: 'AI Manager',
    status: 'TEE ATTESTED',
    aum: '$4.2M',
    aumValue: 4.2,
    return30d: 9.8,
    sharpe: 2.7,
    drawdown: 2.1,
    turnover: 18,
    managerBond: '$120K',
    investors: 684,
    ageDays: 64,
    watchlisted: false,
    holdings: ['AAPLx', 'MSFTx', 'USDC'],
    teeQuote: '0x93f2…c71a',
    attestationAge: '6 min ago',
    oracleFreshness: '12s',
    policyGates: ['Adapter allowlist', 'Daily turnover cap', 'Max 35% single equity', 'Custody locked'],
  },
  {
    id: 'hv-magnificent-7',
    rank: 2,
    name: 'Magnificent 7 TEE Index',
    manager: '0xC02b…dA88',
    category: 'Community Curator',
    status: 'TEE ATTESTED',
    aum: '$6.7M',
    aumValue: 6.7,
    return30d: 7.1,
    sharpe: 2.2,
    drawdown: 3.4,
    turnover: 24,
    managerBond: '$200K',
    investors: 1208,
    ageDays: 112,
    watchlisted: false,
    holdings: ['NVDAx', 'AAPLx', 'METAx', 'AMZNx'],
    teeQuote: '0x44de…92ff',
    attestationAge: '11 min ago',
    oracleFreshness: '9s',
    policyGates: ['Price deviation guard', 'Depth check', 'Fee high-water mark', 'No personal wallet transfer'],
  },
  {
    id: 'hv-adr-neutral',
    rank: 3,
    name: 'Asia ADR Neutral Basket',
    manager: '0x18Fa…40c9',
    category: 'Institutional',
    status: 'TEE ATTESTED',
    aum: '$2.9M',
    aumValue: 2.9,
    return30d: 5.6,
    sharpe: 1.9,
    drawdown: 2.8,
    turnover: 11,
    managerBond: '$80K',
    investors: 351,
    ageDays: 41,
    watchlisted: false,
    holdings: ['BABAx', 'TCOMx', 'BIDUx', 'USDC'],
    teeQuote: '0xa18b…5d01',
    attestationAge: '4 min ago',
    oracleFreshness: '15s',
    policyGates: ['Venue whitelist', 'Oracle freshness', 'Slippage ceiling', 'Guardian pause'],
  },
  {
    id: 'hv-risk-parity',
    rank: 4,
    name: 'On-chain Risk Parity',
    manager: '0x9130…2E7a',
    category: 'AI Manager',
    status: 'REVIEWING',
    aum: '$1.4M',
    aumValue: 1.4,
    return30d: 3.2,
    sharpe: 1.5,
    drawdown: 3.6,
    turnover: 9,
    managerBond: '$55K',
    investors: 192,
    ageDays: 17,
    watchlisted: false,
    holdings: ['SPYx', 'QQQx', 'TLTx', 'USDC'],
    teeQuote: 'pending',
    attestationAge: 'awaiting quote',
    oracleFreshness: '18s',
    policyGates: ['Bond locked', 'Manager review', 'Depth minimum', 'Share transfer lock'],
  },
  {
    id: 'hv-semicap',
    rank: 5,
    name: 'Semicap Momentum Guard',
    manager: '0x55d1…B0Ac',
    category: 'Community Curator',
    status: 'GUARDIAN WATCH',
    aum: '$870K',
    aumValue: 0.87,
    return30d: 12.4,
    sharpe: 1.1,
    drawdown: 7.8,
    turnover: 46,
    managerBond: '$40K',
    investors: 88,
    ageDays: 23,
    watchlisted: true,
    holdings: ['NVDAx', 'AMDx', 'TSMx'],
    teeQuote: '0x0fbc…aa71',
    attestationAge: '21 min ago',
    oracleFreshness: '13s',
    policyGates: ['Turnover warning', 'Concentration alert', 'Trade cooldown', 'Guardian review'],
  },
]

const SECURITY_POSTURE = [
  { label: 'TEE Quote', state: 'Live', detail: 'Remote attestation checked before strategy orders are accepted.' },
  { label: 'Vault Policy', state: 'Locked', detail: 'Managers can route orders only through approved stock adapters and depth lanes.' },
  { label: 'Manager Bond', state: '$495K', detail: 'Bond can be frozen by governance when policy violations are proven.' },
  { label: 'Oracle Freshness', state: '< 20s', detail: 'Stale prices and wide deviations are rejected before execution.' },
]

const RISK_EVENTS = [
  { time: '14:03:12', title: 'Suspicious route blocked', detail: 'Attempted non-whitelisted venue route rejected before allowance grant.' },
  { time: '13:48:39', title: 'Stale oracle rejected', detail: 'AAPLx quote exceeded freshness limit; manager order returned to review queue.' },
  { time: '12:15:04', title: 'Manager key rotated', detail: 'Harbor Apple Shield rotated execution key after TEE quote renewal.' },
  { time: '10:22:18', title: 'Bond locked', detail: 'Semicap Momentum Guard entered guardian watch after turnover threshold warning.' },
]

function formatSignedPercent(value: number) {
  return `${value >= 0 ? '+' : ''}${value.toFixed(1)}%`
}

function getStatusClass(status: VaultStatus) {
  if (status === 'TEE ATTESTED') return 'verified'
  if (status === 'GUARDIAN WATCH') return 'watch'
  return 'reviewing'
}

export function StockHarborFundPage() {
  const [filter, setFilter] = useState<FilterKey>('all')
  const [sort, setSort] = useState<SortKey>('quality')
  const [query, setQuery] = useState('')
  const [notice, setNotice] = useState('')

  const visibleVaults = useMemo(() => {
    const keyword = query.trim().toLowerCase()
    return VAULTS
      .filter((vault) => {
        if (filter === 'tee' && vault.status !== 'TEE ATTESTED') return false
        if (filter === 'low-drawdown' && vault.drawdown >= 4) return false
        if (filter === 'new' && vault.ageDays > 30) return false
        if (filter === 'watchlist' && !vault.watchlisted) return false
        if (!keyword) return true
        return [vault.name, vault.manager, vault.category, vault.holdings.join(' ')]
          .join(' ')
          .toLowerCase()
          .includes(keyword)
      })
      .sort((left, right) => {
        if (sort === 'aum') return right.aumValue - left.aumValue
        if (sort === 'sharpe') return right.sharpe - left.sharpe
        if (sort === 'return') return right.return30d - left.return30d
        const leftScore = left.sharpe * 10 + left.return30d - left.drawdown - (left.status === 'GUARDIAN WATCH' ? 12 : 0)
        const rightScore = right.sharpe * 10 + right.return30d - right.drawdown - (right.status === 'GUARDIAN WATCH' ? 12 : 0)
        return rightScore - leftScore
      })
  }, [filter, query, sort])

  const showNotice = (message: string) => {
    setNotice(message)
    window.setTimeout(() => setNotice(''), 2400)
  }

  return (
    <div className="sh-page">
      <section className="sh-hero">
        <div className="sh-hero-copy">
          <div className="sh-eyebrow">STOCKHARBOR · SECURE SOCIAL ETF</div>
          <h1>On-chain stock funds with TEE-checked managers.</h1>
          <p>
            基金经理不触碰投资人资产，只能在 vault policy 允许的股票订单、adapter 深度、预言机价格和换手率范围内调仓。
            每一次 order、fee、bond 和 attestation 都可追溯。
          </p>
          <div className="sh-hero-actions">
            <button type="button" onClick={() => showNotice('经理审核入口已进入产品草案：需要绑定保证金、KYC 与 TEE quote。')}>Request manager review</button>
            <button type="button" onClick={() => showNotice('Vault policy 详情页待接真实合约 ABI 后开放。')}>Inspect vault policy</button>
          </div>
        </div>
        <div className="sh-tee-console" aria-label="TEE attestation console">
          <div className="sh-console-top">
            <span>TEE ATTESTATION</span>
            <strong>LIVE</strong>
          </div>
          <code>quote.verify(manager.enclave)</code>
          <code>policy.check(adapter, oracle, depth)</code>
          <code>vault.execute(order) → shares.nav()</code>
          <div className="sh-console-grid">
            <span>remote quote</span><b>18 / 18</b>
            <span>blocked route</span><b>317</b>
            <span>custody leak</span><b>0</b>
          </div>
        </div>
      </section>

      <section className="sh-metrics" aria-label="StockHarbor metrics">
        {METRICS.map((metric) => (
          <article key={metric.label} className="sh-metric-card">
            <span>{metric.label}</span>
            <strong>{metric.value}</strong>
            <small>{metric.sub}</small>
          </article>
        ))}
      </section>

      <section className="sh-layout">
        <aside className="sh-filter-rail">
          <div className="sh-panel-title">Vault filters</div>
          <div className="sh-filter-list">
            {FILTERS.map((item) => (
              <button
                key={item.key}
                type="button"
                className={filter === item.key ? 'active' : ''}
                onClick={() => setFilter(item.key)}
              >
                <span>{item.label}</span>
                <small>{item.description}</small>
              </button>
            ))}
          </div>
          <div className="sh-risk-box">
            <span>Capital boundary</span>
            <p>经理只能控制 vault 下单，不能把 USDC 或股票代币转到个人钱包。</p>
          </div>
          <div className="sh-risk-box cyan">
            <span>Execution boundary</span>
            <p>订单必须通过指定 venue adapter、指定股票资产、指定深度和最大滑点。</p>
          </div>
        </aside>

        <main className="sh-main-panel">
          <div className="sh-toolbar">
            <div>
              <span>Strategy marketplace</span>
              <strong>{visibleVaults.length} vaults matched</strong>
            </div>
            <label>
              Search
              <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="manager / vault / ticker" />
            </label>
            <label>
              Sort
              <select value={sort} onChange={(event) => setSort(event.target.value as SortKey)}>
                <option value="quality">Risk Score</option>
                <option value="return">30D Return</option>
                <option value="aum">AUM</option>
                <option value="sharpe">Sharpe</option>
              </select>
            </label>
          </div>

          <div className="sh-vault-list">
            {visibleVaults.map((vault) => (
              <article key={vault.id} className="sh-vault-card">
                <div className="sh-vault-rank">#{vault.rank}</div>
                <div className="sh-vault-core">
                  <div className="sh-vault-title-row">
                    <div>
                      <h2>{vault.name}</h2>
                      <p>{vault.category} · manager {vault.manager}</p>
                    </div>
                    <span className={`sh-status ${getStatusClass(vault.status)}`}>{vault.status}</span>
                  </div>

                  <div className="sh-vault-stats">
                    <span><b>{vault.aum}</b><small>AUM</small></span>
                    <span><b className={vault.return30d >= 0 ? 'positive' : 'negative'}>{formatSignedPercent(vault.return30d)}</b><small>30D return</small></span>
                    <span><b>{vault.drawdown.toFixed(1)}%</b><small>max DD</small></span>
                    <span><b>{vault.sharpe.toFixed(1)}</b><small>Sharpe</small></span>
                    <span><b>{vault.turnover}%</b><small>turnover</small></span>
                    <span><b>{vault.managerBond}</b><small>bond</small></span>
                  </div>

                  <div className="sh-holdings">
                    {vault.holdings.map((holding) => <span key={holding}>{holding}</span>)}
                  </div>

                  <div className="sh-policy-grid">
                    {vault.policyGates.map((gate) => <span key={gate}>✓ {gate}</span>)}
                  </div>
                </div>

                <div className="sh-vault-proof">
                  <span>TEE quote</span>
                  <strong>{vault.teeQuote}</strong>
                  <small>{vault.attestationAge}</small>
                  <span>Oracle</span>
                  <strong>{vault.oracleFreshness}</strong>
                  <small>{vault.investors} investors</small>
                </div>
              </article>
            ))}
          </div>
        </main>
      </section>

      <section className="sh-bottom-grid">
        <div className="sh-security-panel">
          <div className="sh-panel-title">Security posture</div>
          {SECURITY_POSTURE.map((item) => (
            <article key={item.label} className="sh-security-row">
              <span>{item.label}</span>
              <strong>{item.state}</strong>
              <p>{item.detail}</p>
            </article>
          ))}
        </div>
        <div className="sh-events-panel">
          <div className="sh-panel-title">Risk events</div>
          {RISK_EVENTS.map((event) => (
            <article key={`${event.time}-${event.title}`} className="sh-event-row">
              <time>{event.time}</time>
              <div>
                <strong>{event.title}</strong>
                <p>{event.detail}</p>
              </div>
            </article>
          ))}
        </div>
      </section>

      {notice ? <div className="sh-toast">{notice}</div> : null}
    </div>
  )
}
