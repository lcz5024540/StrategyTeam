export function FundingArbPage() {
  return (
    <section className="panel">
      <div className="section-head">
        <h2>资金费套利策略</h2>
        <p>利用永续合约资金费率差异进行套利</p>
      </div>
      <div className="empty-state" style={{ padding: '3rem 1rem' }}>
        <p style={{ fontSize: '1.1rem', marginBottom: '0.5rem' }}>该策略正在开发中</p>
        <p style={{ color: 'var(--text-muted)' }}>
          资金费套利策略将自动监控各交易所永续合约的资金费率，当费率差异达到阈值时自动建立对冲头寸以赚取资金费差价。
        </p>
      </div>
    </section>
  )
}
