import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { profilesApi } from '@/api'
import type { WalletDiagnosisView, WalletOrderAnalysisView, WalletScreenerView } from '@/types'
import {
  compactAddress,
  formatAmount,
  formatMoney,
  formatNumber,
  formatPercent,
  getAmountClass,
  truncateText,
} from '@/utils/format'

type AnalysisWindow = '7d' | '30d' | 'all'
type WatchGroup = 'all' | 'priority' | 'open' | 'observe'

interface WindowStats {
  trades: number
  wins: number
  losses: number
  winRate: number
  netPnlUsdc: number
  averageRoiRate: number
  medianHoldMinutes: number
  earlyExitRate: number
  settlementWinRate: number
}

interface TrendPoint {
  key: string
  value: number
  height: number
  label: string
  exitStyle: string
}

interface CodeMeta {
  code: string
  label: string
  description: string
}

const WATCH_GROUP_OPTIONS: Array<{ value: WatchGroup; label: string; hint: string }> = [
  { value: 'all', label: '全部', hint: '监控池全量地址' },
  { value: 'priority', label: '重点', hint: '优先关注 EXECUTABLE_A' },
  { value: 'open', label: '开放', hint: '当前允许跟单的地址' },
  { value: 'observe', label: '观察', hint: '仅观察或阻断地址' },
]

function getTierBadgeClass(tier: string | null | undefined) {
  if (tier === 'EXECUTABLE_A') return 'badge accent'
  if (tier === 'EXECUTABLE_B') return 'badge'
  if (tier === 'BLOCKLIST') return 'badge danger'
  return 'badge warn'
}

function getSourceBadgeClass(source: string | null | undefined) {
  if (source === 'LATENCY_ALPHA') return 'badge accent'
  if (source === 'SWING_ALPHA') return 'badge'
  if (source === 'SETTLEMENT_ALPHA') return 'badge warn'
  if (source === 'RISK_CONTROL_ALPHA') return 'badge danger'
  return 'badge'
}

function normalizeCode(value: string | null | undefined) {
  const normalized = value?.trim().toUpperCase()
  return normalized && normalized.length > 0 ? normalized : 'UNKNOWN'
}

function getArchetypeMeta(value: string | null | undefined): CodeMeta {
  const code = normalizeCode(value)
  if (code === 'HF') {
    return {
      code,
      label: 'HF 高频快进快出',
      description: '交易节奏快，alpha 窗口短，需要更低延迟的跟随方式。',
    }
  }
  if (code === 'THEMATIC') {
    return {
      code,
      label: 'THEMATIC 主题集中型',
      description: '围绕少量主题市场反复下注，更适合较慢节奏的主题跟随。',
    }
  }
  if (code === 'LOW_FREQ_VALUE') {
    return {
      code,
      label: 'LOW_FREQ_VALUE 低频价值型',
      description: '出手频率低，但命中率和持有耐心通常更好。',
    }
  }
  if (code === 'SWING') {
    return {
      code,
      label: 'SWING 波段型',
      description: '中等频率，偏向 5 分钟到规则级别的稳定 alpha。',
    }
  }
  if (code === 'NOISE') {
    return {
      code,
      label: 'NOISE 噪声型',
      description: '当前执行风格下复制性较弱，不适合直接自动跟单。',
    }
  }
  return {
    code,
    label: 'UNKNOWN 未定型',
    description: '当前样本不足，暂时无法稳定判断地址风格。',
  }
}

function getFollowLaneMeta(value: string | null | undefined): CodeMeta {
  const code = normalizeCode(value)
  if (code === 'HF_FOLLOW') {
    return {
      code,
      label: 'HF_FOLLOW 高频跟随通道',
      description: '适合快节奏、低延迟复制。',
    }
  }
  if (code === 'THEMATIC_FOLLOW') {
    return {
      code,
      label: 'THEMATIC_FOLLOW 主题跟随通道',
      description: '适合围绕主题市场的中慢速跟随。',
    }
  }
  if (code === 'VALUE_FOLLOW') {
    return {
      code,
      label: 'VALUE_FOLLOW 价值跟随通道',
      description: '适合低频、耐心持有的价值型跟随。',
    }
  }
  if (code === 'SWING_FOLLOW') {
    return {
      code,
      label: 'SWING_FOLLOW 波段跟随通道',
      description: '适合中等频率的波段复制。',
    }
  }
  if (code === 'OBSERVE_ONLY') {
    return {
      code,
      label: 'OBSERVE_ONLY 仅观察',
      description: '保留监控，但不进入自动跟单执行。',
    }
  }
  return {
    code,
    label: 'UNKNOWN 未知跟随通道',
    description: '暂未给出明确的跟随执行通道。',
  }
}

function getExitLaneMeta(value: string | null | undefined): CodeMeta {
  const code = normalizeCode(value)
  if (code === 'HOLD_TO_SETTLEMENT') {
    return {
      code,
      label: 'HOLD_TO_SETTLEMENT 持有到结算',
      description: '更适合等市场结算，不急于中途退出。',
    }
  }
  if (code === 'EARLY_TAKE_PROFIT') {
    return {
      code,
      label: 'EARLY_TAKE_PROFIT 提前止盈',
      description: '盈利后优先尽早兑现，避免回撤。',
    }
  }
  if (code === 'FAST_STOP_LOSS') {
    return {
      code,
      label: 'FAST_STOP_LOSS 快速止损',
      description: '一旦走势不利，应更快退出控制亏损。',
    }
  }
  if (code === 'HYBRID_EARLY_EXIT') {
    return {
      code,
      label: 'HYBRID_EARLY_EXIT 混合提前退出',
      description: '部分仓位提前落袋，剩余仓位保留弹性。',
    }
  }
  if (code === 'FAST_EXIT_OR_SKIP') {
    return {
      code,
      label: 'FAST_EXIT_OR_SKIP 快出或跳过',
      description: '信号窗口太短，不适合常规复制，通常只观察或直接跳过。',
    }
  }
  if (code === 'FOLLOW_ARCHETYPE') {
    return {
      code,
      label: 'FOLLOW_ARCHETYPE 跟随地址风格',
      description: '按地址 archetype 的默认节奏执行退出。',
    }
  }
  if (code === 'OBSERVE_ONLY') {
    return {
      code,
      label: 'OBSERVE_ONLY 仅观察',
      description: '仅保留研究价值，不建议进入自动跟单。',
    }
  }
  return {
    code,
    label: 'UNKNOWN 未知退出建议',
    description: '当前没有稳定的退出建议。',
  }
}

function getWinSourceMeta(value: string | null | undefined): CodeMeta {
  const code = normalizeCode(value)
  if (code === 'LATENCY_ALPHA') {
    return {
      code,
      label: 'LATENCY_ALPHA 延迟优势',
      description: '胜率更多来自短时间差抢占，需要更快执行。',
    }
  }
  if (code === 'SWING_ALPHA') {
    return {
      code,
      label: 'SWING_ALPHA 波段优势',
      description: '胜率更多来自中短周期波段，而非超短线。',
    }
  }
  if (code === 'SETTLEMENT_ALPHA') {
    return {
      code,
      label: 'SETTLEMENT_ALPHA 结算优势',
      description: '更适合持有到临近结算或最终结算兑现收益。',
    }
  }
  if (code === 'RISK_CONTROL_ALPHA') {
    return {
      code,
      label: 'RISK_CONTROL_ALPHA 风控优势',
      description: '主要强在止损和风控，而不是高进攻型 alpha。',
    }
  }
  return {
    code,
    label: 'UNKNOWN 未知胜率来源',
    description: '样本不足，暂时无法判断主要胜率来源。',
  }
}

function getWindowRecords(records: WalletOrderAnalysisView[], window: AnalysisWindow) {
  if (window === 'all') {
    return records
  }
  const days = window === '7d' ? 7 : 30
  const cutoff = Date.now() - days * 24 * 3600_000
  return records.filter((record) => {
    const time = record.closedAt ? new Date(record.closedAt).getTime() : 0
    return time >= cutoff
  })
}

function calculateWindowStats(records: WalletOrderAnalysisView[]): WindowStats {
  if (records.length === 0) {
    return {
      trades: 0,
      wins: 0,
      losses: 0,
      winRate: 0,
      netPnlUsdc: 0,
      averageRoiRate: 0,
      medianHoldMinutes: 0,
      earlyExitRate: 0,
      settlementWinRate: 0,
    }
  }

  let wins = 0
  let losses = 0
  let netPnlUsdc = 0
  let totalRoiRate = 0
  let roiSample = 0
  let earlyExitCount = 0
  let settledCount = 0
  let settledWins = 0
  const holdMinutes: number[] = []

  records.forEach((record) => {
    const pnl = Number(record.realizedPnlUsdc || 0)
    const roi = Number(record.roiRate || 0)
    const hold = Number(record.holdMinutes || 0)
    const exitStyle = record.exitStyle || ''
    netPnlUsdc += pnl
    if (pnl > 0) wins += 1
    if (pnl < 0) losses += 1
    if (record.roiRate != null) {
      totalRoiRate += roi
      roiSample += 1
    }
    if (hold > 0) {
      holdMinutes.push(hold)
    }
    if (exitStyle.startsWith('EARLY_')) {
      earlyExitCount += 1
    }
    if (exitStyle.startsWith('SETTLED_')) {
      settledCount += 1
      if (exitStyle === 'SETTLED_WIN') {
        settledWins += 1
      }
    }
  })

  holdMinutes.sort((a, b) => a - b)
  const medianHoldMinutes =
    holdMinutes.length === 0
      ? 0
      : holdMinutes.length % 2 === 1
        ? holdMinutes[(holdMinutes.length - 1) / 2]
        : (holdMinutes[holdMinutes.length / 2 - 1] + holdMinutes[holdMinutes.length / 2]) / 2

  return {
    trades: records.length,
    wins,
    losses,
    winRate: wins / records.length,
    netPnlUsdc,
    averageRoiRate: roiSample === 0 ? 0 : totalRoiRate / roiSample,
    medianHoldMinutes,
    earlyExitRate: earlyExitCount / records.length,
    settlementWinRate: settledCount === 0 ? 0 : settledWins / settledCount,
  }
}

function buildStatusNote(row: WalletScreenerView) {
  if (row.screenerTier === 'EXECUTABLE_A') {
    return '可直接纳入重点跟单队列'
  }
  if (row.screenerTier === 'EXECUTABLE_B') {
    return '可跟单，但建议限额观察'
  }
  if (row.screenerTier === 'OBSERVE') {
    return '仅建议观察，不建议自动跟单'
  }
  return '已列入阻断名单'
}

function formatCompactDateTime(value: string | null | undefined) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function buildRecentTradeTrend(records: WalletOrderAnalysisView[]): TrendPoint[] {
  const recent = records.slice(0, 8).reverse()
  if (recent.length === 0) {
    return []
  }
  const maxAbsRoi = Math.max(...recent.map((record) => Math.abs(Number(record.roiRate || 0))), 0.01)
  return recent.map((record, index) => ({
    key: `${record.marketId}-${record.closedAt || index}`,
    value: Number(record.roiRate || 0),
    height: Math.max(10, (Math.abs(Number(record.roiRate || 0)) / maxAbsRoi) * 88),
    label: record.closedAt ? formatCompactDateTime(record.closedAt) : record.marketId,
    exitStyle: record.exitStyle || 'UNKNOWN',
  }))
}

function matchesWatchGroup(row: WalletScreenerView, watchGroup: WatchGroup) {
  if (watchGroup === 'priority') {
    return row.screenerTier === 'EXECUTABLE_A'
  }
  if (watchGroup === 'open') {
    return row.screenerTier === 'EXECUTABLE_A' || row.screenerTier === 'EXECUTABLE_B'
  }
  if (watchGroup === 'observe') {
    return row.screenerTier === 'OBSERVE' || row.screenerTier === 'BLOCKLIST'
  }
  return true
}

function averageMetric(rows: WalletScreenerView[], picker: (row: WalletScreenerView) => number | null | undefined) {
  const values = rows
    .map((row) => Number(picker(row)))
    .filter((value) => Number.isFinite(value) && value > 0)

  if (values.length === 0) {
    return 0
  }

  return values.reduce((sum, value) => sum + value, 0) / values.length
}

function buildFocusSummary(row?: WalletScreenerView, diagnosis?: WalletDiagnosisView, stats?: WindowStats) {
  if (!row && !diagnosis) {
    return '从监控列表点击一个地址后，这里会输出面向运营的聚焦结论。'
  }

  if (!row && diagnosis?.summary) {
    return diagnosis.summary
  }

  const parts: string[] = []
  const winSourceMeta = getWinSourceMeta(row?.winSourceType || diagnosis?.winSourceType)
  const archetypeMeta = getArchetypeMeta(row?.archetype || diagnosis?.archetype)
  const exitLaneMeta = getExitLaneMeta(diagnosis?.recommendedExitLane || row?.recommendedExitLane)

  if (row?.winSourceType || diagnosis?.winSourceType) {
    parts.push(`核心胜率来源偏向 ${winSourceMeta.label}`)
  }
  if (row?.archetype || diagnosis?.archetype) {
    parts.push(`地址类型归为 ${archetypeMeta.label}`)
  }
  if (row?.highFrequencyTradingAddress || diagnosis?.highFrequencyTradingAddress) {
    parts.push('交易节奏偏高频')
  } else {
    parts.push('交易节奏偏中低频')
  }
  if (stats && stats.trades > 0) {
    parts.push(`当前窗口胜率 ${formatPercent(stats.winRate)}，净收益 ${formatMoney(stats.netPnlUsdc)}`)
  }
  if (diagnosis?.recommendedExitLane || row?.recommendedExitLane) {
    parts.push(`建议退出节奏为 ${exitLaneMeta.label}`)
  }
  return `${parts.join('，')}。`
}

function AddressMetricStrip({
  title,
  value,
  hint,
  className = 'neutral',
}: {
  title: string
  value: string
  hint: string
  className?: 'positive' | 'negative' | 'neutral'
}) {
  return (
    <article className="metric-card address-metric-card">
      <div className="label">{title}</div>
      <div className={`value ${className}`}>{value}</div>
      <div className="hint">{hint}</div>
    </article>
  )
}

function DiagnosisCard({ diagnosis }: { diagnosis?: WalletDiagnosisView }) {
  if (!diagnosis) {
    return <div className="empty-state">选择一个地址后查看胜率来源、风格与风险诊断</div>
  }

  const exitLaneMeta = getExitLaneMeta(diagnosis.recommendedExitLane)

  return (
    <div className="address-diagnosis">
      <div className="address-diagnosis-hero">
        <div>
          <div className="eyebrow">ADDRESS DIAGNOSIS 地址诊断</div>
          <h3>{diagnosis.alias || compactAddress(diagnosis.walletAddress, 10, 6)}</h3>
          <p>{diagnosis.summary || '暂无画像摘要'}</p>
        </div>
        <div className="address-badge-stack">
          <span className={getTierBadgeClass(diagnosis.screenerTier)}>{diagnosis.screenerTier || 'UNKNOWN'}</span>
          <span className={getSourceBadgeClass(diagnosis.winSourceType)}>{diagnosis.winSourceType || 'UNKNOWN'}</span>
        </div>
      </div>

      <div className="metric-grid">
        <AddressMetricStrip
          title="地址质量"
          value={formatPercent(diagnosis.qualityScore)}
          hint="用于判断地址本身是否值得重点跟踪"
          className={getAmountClass((diagnosis.qualityScore || 0) - 0.5)}
        />
        <AddressMetricStrip
          title="可复制性"
          value={formatPercent(diagnosis.replicabilityScore)}
          hint="用于判断是否适合跟单复制"
          className={getAmountClass((diagnosis.replicabilityScore || 0) - 0.5)}
        />
        <AddressMetricStrip
          title="稳定性"
          value={formatPercent(diagnosis.stabilityScore)}
          hint="避免一次性好运地址"
          className={getAmountClass((diagnosis.stabilityScore || 0) - 0.5)}
        />
        <AddressMetricStrip title="建议退出" value={exitLaneMeta.label} hint={exitLaneMeta.description} />
      </div>

      <div className="split-grid">
        <section className="address-note-card">
          <h4>优势判断</h4>
          {diagnosis.strengths.length === 0 ? (
            <p>暂无明确优势结论</p>
          ) : (
            diagnosis.strengths.map((item) => (
              <div key={item} className="address-note-row positive">{item}</div>
            ))
          )}
        </section>
        <section className="address-note-card">
          <h4>风险提醒</h4>
          {diagnosis.risks.length === 0 ? (
            <p>暂无显著风险提醒</p>
          ) : (
            diagnosis.risks.map((item) => (
              <div key={item} className="address-note-row negative">{item}</div>
            ))
          )}
        </section>
      </div>
    </div>
  )
}

export function FollowAddressPanel() {
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const [analysisWindow, setAnalysisWindow] = useState<AnalysisWindow>('30d')
  const [watchGroup, setWatchGroup] = useState<WatchGroup>('all')
  const [keyword, setKeyword] = useState('')
  const [tierFilter, setTierFilter] = useState('ALL')
  const [sourceFilter, setSourceFilter] = useState('ALL')
  const [archetypeFilter, setArchetypeFilter] = useState('ALL')
  const [managementMessage, setManagementMessage] = useState<string>('')

  const selectedWalletAddress = searchParams.get('wallet')?.trim() || ''
  const detailMode = searchParams.get('view') === 'detail' && Boolean(selectedWalletAddress)

  const openWalletDetail = (walletAddress: string) => {
    setSearchParams((current) => {
      const next = new URLSearchParams(current)
      next.set('view', 'detail')
      next.set('wallet', walletAddress)
      return next
    })
  }

  const closeWalletDetail = () => {
    setSearchParams((current) => {
      const next = new URLSearchParams(current)
      next.delete('view')
      next.delete('wallet')
      return next
    })
  }

  const { data: monitoredPage, isLoading: monitoredLoading } = useQuery({
    queryKey: ['copyAddressScreener', 'candidates'],
    queryFn: () => profilesApi.getScreener({ scope: 'candidates', limit: 60 }),
  })

  const { data: executablePage, isLoading: executableLoading } = useQuery({
    queryKey: ['copyAddressScreener', 'executable'],
    queryFn: () => profilesApi.getScreener({ scope: 'executable', limit: 24 }),
  })

  const monitoredAddresses = monitoredPage?.items || []
  const executableAddresses = executablePage?.items || []

  const { data: diagnosis, isLoading: diagnosisLoading } = useQuery({
    queryKey: ['copyAddressDiagnosis', selectedWalletAddress],
    queryFn: () => profilesApi.getDiagnosis(selectedWalletAddress, false),
    enabled: detailMode,
  })

  const { data: orderAnalysisPage, isLoading: orderAnalysisLoading } = useQuery({
    queryKey: ['copyAddressOrderAnalysis', selectedWalletAddress],
    queryFn: () => profilesApi.getOrderAnalysis(selectedWalletAddress, { limit: 200 }),
    enabled: detailMode,
  })

  const observeOnlyMutation = useMutation({
    mutationFn: (walletAddress: string) =>
      profilesApi.updateProfileOverride(walletAddress, {
        manualWatchEnabled: false,
        note: 'Set to observe-only from Polymarket copy-trade dashboard.',
      }),
    onSuccess: () => {
      setManagementMessage('地址已切换为只观察，已从自动跟单开放队列中移除。')
      queryClient.invalidateQueries({ queryKey: ['copyAddressScreener'] })
      queryClient.invalidateQueries({ queryKey: ['copyAddressDiagnosis'] })
    },
    onError: (error) => {
      setManagementMessage(error instanceof Error ? error.message : '设置只观察失败')
    },
  })

  const selectedOrders = orderAnalysisPage?.items || []
  const filteredOrders = getWindowRecords(selectedOrders, analysisWindow)
  const windowStats = calculateWindowStats(filteredOrders)
  const recentTradeTrend = buildRecentTradeTrend(filteredOrders)
  const normalizedKeyword = keyword.trim().toLowerCase()

  const filteredMonitoredAddresses = monitoredAddresses.filter((row) => {
    const keywordMatched =
      normalizedKeyword.length === 0 ||
      row.walletAddress.toLowerCase().includes(normalizedKeyword) ||
      (row.alias || '').toLowerCase().includes(normalizedKeyword) ||
      (row.summary || '').toLowerCase().includes(normalizedKeyword)
    const tierMatched = tierFilter === 'ALL' || row.screenerTier === tierFilter
    const sourceMatched = sourceFilter === 'ALL' || row.winSourceType === sourceFilter
    const archetypeMatched = archetypeFilter === 'ALL' || row.archetype === archetypeFilter
    return (
      keywordMatched &&
      tierMatched &&
      sourceMatched &&
      archetypeMatched &&
      matchesWatchGroup(row, watchGroup)
    )
  })

  const groupedMonitoredAddresses = [
    {
      key: 'EXECUTABLE_A',
      title: '重点开放地址',
      description: '优先执行跟单，适合主队列配置。',
      rows: filteredMonitoredAddresses.filter((row) => row.screenerTier === 'EXECUTABLE_A'),
    },
    {
      key: 'EXECUTABLE_B',
      title: '可跟单观察地址',
      description: '可以开放，但建议控制额度或仓位。',
      rows: filteredMonitoredAddresses.filter((row) => row.screenerTier === 'EXECUTABLE_B'),
    },
    {
      key: 'OBSERVE',
      title: '只观察地址',
      description: '保留监控信号，不自动跟单。',
      rows: filteredMonitoredAddresses.filter((row) => row.screenerTier === 'OBSERVE'),
    },
    {
      key: 'BLOCKLIST',
      title: '阻断地址',
      description: '持续跟踪风险，不进入开放名单。',
      rows: filteredMonitoredAddresses.filter((row) => row.screenerTier === 'BLOCKLIST'),
    },
  ].filter((group) => group.rows.length > 0)

  const activeCopyCount = executableAddresses.length
  const observeCount = monitoredAddresses.filter((item) => item.screenerTier === 'OBSERVE').length

  const selectedRow =
    monitoredAddresses.find((item) => item.walletAddress === selectedWalletAddress) ||
    executableAddresses.find((item) => item.walletAddress === selectedWalletAddress)

  const overviewCards = [
    {
      key: 'ALL',
      title: '监控总池',
      subtitle: '全量候选地址',
      count: monitoredAddresses.length,
      avgQuality: averageMetric(monitoredAddresses, (row) => row.qualityScore),
      avgWinRate: averageMetric(monitoredAddresses, (row) => row.recentWinRate30d),
    },
    {
      key: 'EXECUTABLE_A',
      title: '重点开放',
      subtitle: '优先跟单地址',
      count: monitoredAddresses.filter((row) => row.screenerTier === 'EXECUTABLE_A').length,
      avgQuality: averageMetric(monitoredAddresses.filter((row) => row.screenerTier === 'EXECUTABLE_A'), (row) => row.qualityScore),
      avgWinRate: averageMetric(monitoredAddresses.filter((row) => row.screenerTier === 'EXECUTABLE_A'), (row) => row.recentWinRate30d),
    },
    {
      key: 'EXECUTABLE_B',
      title: '跟单观察',
      subtitle: '开放但限额',
      count: monitoredAddresses.filter((row) => row.screenerTier === 'EXECUTABLE_B').length,
      avgQuality: averageMetric(monitoredAddresses.filter((row) => row.screenerTier === 'EXECUTABLE_B'), (row) => row.qualityScore),
      avgWinRate: averageMetric(monitoredAddresses.filter((row) => row.screenerTier === 'EXECUTABLE_B'), (row) => row.recentWinRate30d),
    },
    {
      key: 'OBSERVE',
      title: '只观察',
      subtitle: '保留研究信号',
      count: monitoredAddresses.filter((row) => row.screenerTier === 'OBSERVE').length,
      avgQuality: averageMetric(monitoredAddresses.filter((row) => row.screenerTier === 'OBSERVE'), (row) => row.qualityScore),
      avgWinRate: averageMetric(monitoredAddresses.filter((row) => row.screenerTier === 'OBSERVE'), (row) => row.recentWinRate30d),
    },
  ]

  const focusSummary = buildFocusSummary(selectedRow, diagnosis, windowStats)
  const trendPositiveCount = recentTradeTrend.filter((item) => item.value > 0).length
  const trendNegativeCount = recentTradeTrend.filter((item) => item.value < 0).length

  const selectedName =
    selectedRow?.alias ||
    diagnosis?.alias ||
    (selectedWalletAddress ? compactAddress(selectedWalletAddress, 10, 6) : '未选择')
  const selectedArchetypeMeta = getArchetypeMeta(selectedRow?.archetype || diagnosis?.archetype)
  const selectedFollowLaneMeta = getFollowLaneMeta(selectedRow?.followLane || diagnosis?.followLane)
  const selectedExitLaneMeta = getExitLaneMeta(diagnosis?.recommendedExitLane || selectedRow?.recommendedExitLane)
  const selectedWinSourceMeta = getWinSourceMeta(selectedRow?.winSourceType || diagnosis?.winSourceType)
  const selectedTier = selectedRow?.screenerTier || diagnosis?.screenerTier || 'UNKNOWN'
  const selectedQualityScore = diagnosis?.qualityScore ?? selectedRow?.qualityScore
  const selectedReplicabilityScore = diagnosis?.replicabilityScore ?? selectedRow?.replicabilityScore
  const selectedStabilityScore = diagnosis?.stabilityScore ?? selectedRow?.stabilityScore
  const selectedRecentWinRate30d = selectedRow?.recentWinRate30d
  const selectedRecentWinRate7d = selectedRow?.recentWinRate7d
  const selectedFollowabilityRate = selectedRow?.followabilityRate
  const selectedFreshnessScore = selectedRow?.freshnessScore
  const selectedSettlementWinRate = diagnosis?.settlementWinRate ?? selectedRow?.settlementWinRate
  const currentAddressLabel = selectedWalletAddress
    ? (selectedRow?.alias || compactAddress(selectedWalletAddress, 8, 6))
    : '未选择'

  if (detailMode) {
    return (
      <>
        <section className="panel address-focus-panel">
          <div className="address-window-toolbar" style={{ marginBottom: 18 }}>
            <div className="section-head compact" style={{ marginBottom: 0 }}>
              <h2>地址详情</h2>
            </div>
            <button type="button" className="btn btn-secondary" onClick={closeWalletDetail}>
              返回地址列表
            </button>
          </div>

          <div className="address-focus-stage">
            <div className="address-focus-hero">
              <div className="address-focus-copy">
                <p className="eyebrow">ADDRESS ANALYSIS</p>
                <h3>{selectedName}</h3>
                <p>{focusSummary}</p>
                <div className="address-badge-stack">
                  <span className="badge" title={selectedArchetypeMeta.description}>{selectedArchetypeMeta.code}</span>
                  <span className={getTierBadgeClass(selectedTier)}>{selectedTier}</span>
                  <span className={getSourceBadgeClass(selectedWinSourceMeta.code)}>{selectedWinSourceMeta.code}</span>
                </div>
                <div className="address-focus-meta">
                  <span>{selectedWalletAddress || '-'}</span>
                  <span>{`最近活跃 ${selectedRow?.lastTradeAt ? new Date(selectedRow.lastTradeAt).toLocaleDateString('zh-CN') : '-'}`}</span>
                </div>
              </div>

              <div className="address-focus-metrics">
                <AddressMetricStrip
                  title="地址质量"
                  value={formatPercent(selectedQualityScore)}
                  hint="强不强"
                  className={getAmountClass((selectedQualityScore || 0) - 0.5)}
                />
                <AddressMetricStrip
                  title="可复制性"
                  value={formatPercent(selectedReplicabilityScore)}
                  hint="能不能跟"
                  className={getAmountClass((selectedReplicabilityScore || 0) - 0.5)}
                />
                <AddressMetricStrip
                  title="7D 胜率"
                  value={formatPercent(selectedRecentWinRate7d)}
                  hint="最近 7 天是否仍然有效"
                  className={getAmountClass((selectedRecentWinRate7d || 0) - 0.5)}
                />
                <AddressMetricStrip
                  title="30D 胜率"
                  value={formatPercent(selectedRecentWinRate30d)}
                  hint="近 30 天胜率表现"
                  className={getAmountClass((selectedRecentWinRate30d || 0) - 0.5)}
                />
              </div>
            </div>

            <div className="address-focus-signal-rail">
              <div className="address-focus-signal" title={selectedArchetypeMeta.description}>
                <span>地址类型</span>
                <strong>{selectedArchetypeMeta.label}</strong>
              </div>
              <div className="address-focus-signal" title={selectedFollowLaneMeta.description}>
                <span>跟随通道</span>
                <strong>{selectedFollowLaneMeta.label}</strong>
              </div>
              <div className="address-focus-signal" title={selectedExitLaneMeta.description}>
                <span>建议退出</span>
                <strong>{selectedExitLaneMeta.label}</strong>
              </div>
              <div className="address-focus-signal" title={selectedWinSourceMeta.description}>
                <span>胜率来源</span>
                <strong>{selectedWinSourceMeta.label}</strong>
              </div>
            </div>

            <div className="metric-grid address-analysis-grid">
              <AddressMetricStrip
                title="可复制率"
                value={formatPercent(selectedFollowabilityRate)}
                hint="滑点复制可行性"
                className={getAmountClass((selectedFollowabilityRate || 0) - 0.5)}
              />
              <AddressMetricStrip
                title="等待结算胜率"
                value={formatPercent(selectedSettlementWinRate)}
                hint="只看 SETTLED_* 类订单"
                className={getAmountClass((selectedSettlementWinRate || 0) - 0.5)}
              />
              <AddressMetricStrip
                title="稳定性"
                value={formatPercent(selectedStabilityScore)}
                hint="避免一次性好运地址"
                className={getAmountClass((selectedStabilityScore || 0) - 0.5)}
              />
              <AddressMetricStrip
                title="新鲜度"
                value={formatPercent(selectedFreshnessScore)}
                hint="距离最近有效交易越近越高"
                className={getAmountClass((selectedFreshnessScore || 0) - 0.5)}
              />
            </div>

            <section className="address-trend-panel">
              <div className="section-head compact">
                <h2>近期交易节奏带</h2>
              </div>

              <div className="address-trend-summary-grid">
                <div className="address-trend-summary-card">
                  <span>正收益笔数</span>
                  <strong>{formatNumber(trendPositiveCount, 0)}</strong>
                  <small>最近趋势里赢的交易</small>
                </div>
                <div className="address-trend-summary-card">
                  <span>负收益笔数</span>
                  <strong>{formatNumber(trendNegativeCount, 0)}</strong>
                  <small>最近趋势里亏的交易</small>
                </div>
                <div className="address-trend-summary-card">
                  <span>提前退出率</span>
                  <strong>{formatPercent(windowStats.earlyExitRate)}</strong>
                  <small>当前分析窗口内提前退出占比（EARLY_*）</small>
                </div>
                <div className="address-trend-summary-card">
                  <span>中位持有时长</span>
                  <strong>{`${formatNumber(windowStats.medianHoldMinutes, 1)}m`}</strong>
                  <small>当前分析窗口的持仓节奏</small>
                </div>
              </div>

              {recentTradeTrend.length === 0 ? (
                <div className="empty-state">当前窗口暂无可展示的趋势样本</div>
              ) : (
                <div className="address-trend-strip">
                  {recentTradeTrend.map((item) => (
                    <div key={item.key} className="address-trend-item">
                      <div className="address-trend-bar-wrap">
                        <div
                          className={`address-trend-bar ${item.value >= 0 ? 'positive' : 'negative'}`}
                          style={{ height: `${item.height}px` }}
                          title={`${formatPercent(item.value)} / ${item.exitStyle}`}
                        />
                      </div>
                      <div className="address-trend-value">{formatPercent(item.value)}</div>
                      <div className="address-trend-label">{item.label}</div>
                    </div>
                  ))}
                </div>
              )}
            </section>

            <div className="address-focus-stack">
              <section className="address-stack-block">
                <div className="section-head compact">
                  <h2>地址诊断</h2>
                </div>
                {diagnosisLoading ? <div className="loading">地址诊断加载中...</div> : <DiagnosisCard diagnosis={diagnosis} />}
              </section>

              <section className="address-stack-block">
                <div className="section-head compact">
                  <h2>交易分析</h2>
                </div>

                <div className="address-window-toolbar">
                  <div className="segmented-control address-window-tabs">
                    {(['7d', '30d', 'all'] as AnalysisWindow[]).map((window) => (
                      <button
                        key={window}
                        type="button"
                        className={analysisWindow === window ? 'active' : ''}
                        onClick={() => setAnalysisWindow(window)}
                      >
                        {window.toUpperCase()}
                      </button>
                    ))}
                  </div>
                  <div className="table-toolbar-meta">
                    {selectedOrders.length === 0 ? '暂无单笔样本' : `当前窗口 ${formatNumber(filteredOrders.length, 0)} 笔 / 全量 ${formatNumber(selectedOrders.length, 0)} 笔`}
                  </div>
                </div>

                {orderAnalysisLoading ? (
                  <div className="loading">交易分析加载中...</div>
                ) : (
                  <div className="metric-grid address-analysis-grid">
                    <AddressMetricStrip title="交易数" value={formatNumber(windowStats.trades, 0)} hint="当前窗口内已关闭交易样本数" />
                    <AddressMetricStrip title="胜率" value={formatPercent(windowStats.winRate)} hint={`${windowStats.wins} 胜 / ${windowStats.losses} 负`} className={getAmountClass(windowStats.winRate - 0.5)} />
                    <AddressMetricStrip title="净收益" value={formatMoney(windowStats.netPnlUsdc)} hint="当前窗口内累计已实现收益" className={getAmountClass(windowStats.netPnlUsdc)} />
                    <AddressMetricStrip title="平均 ROI" value={formatPercent(windowStats.averageRoiRate)} hint="逐单收益率平均值" className={getAmountClass(windowStats.averageRoiRate)} />
                    <AddressMetricStrip title="中位持有时长" value={`${formatNumber(windowStats.medianHoldMinutes, 1)}m`} hint="单笔持有分钟数中位数" />
                    <AddressMetricStrip title="等待结算胜率" value={formatPercent(windowStats.settlementWinRate)} hint="只统计等待结算类退出（SETTLED_*）" className={getAmountClass(windowStats.settlementWinRate - 0.5)} />
                  </div>
                )}
              </section>
            </div>
          </div>
        </section>

        <section className="panel">
          <div className="section-head">
            <h2>逐单分析</h2>
          </div>

          {orderAnalysisLoading ? (
            <div className="loading">逐单分析加载中...</div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>市场 / 方向</th>
                    <th>开仓 / 平仓</th>
                    <th>投入</th>
                    <th>收益</th>
                    <th>ROI</th>
                    <th>持有时长</th>
                    <th>退出风格</th>
                    <th>提前分钟</th>
                    <th>可信度</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredOrders.length === 0 ? (
                    <tr>
                      <td colSpan={9} className="empty-state">当前窗口暂无已关闭交易分析记录</td>
                    </tr>
                  ) : (
                    filteredOrders.map((row, index) => (
                      <tr key={`${row.walletAddress}-${row.marketId}-${row.closedAt || index}`}>
                        <td>
                          <div className="address-primary-cell">
                            <strong>{row.outcome || '-'}</strong>
                            <span title={row.question || ''}>{truncateText(row.question, 52) || row.marketId}</span>
                          </div>
                        </td>
                        <td>
                          <div>{row.openedAt ? new Date(row.openedAt).toLocaleString('zh-CN') : '-'}</div>
                          <div className="table-subtext">{row.closedAt ? new Date(row.closedAt).toLocaleString('zh-CN') : '-'}</div>
                        </td>
                        <td>{formatAmount(row.investedUsdc)}</td>
                        <td className={getAmountClass(row.realizedPnlUsdc)}>{formatMoney(row.realizedPnlUsdc)}</td>
                        <td className={getAmountClass(row.roiRate)}>{formatPercent(row.roiRate)}</td>
                        <td>{formatNumber(row.holdMinutes, 1)}m</td>
                        <td><span className="badge">{row.exitStyle || 'UNKNOWN'}</span></td>
                        <td>{row.exitLeadMinutes == null ? '-' : `${formatNumber(row.exitLeadMinutes, 1)}m`}</td>
                        <td>{formatPercent(row.sourceConfidence)}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          )}
        </section>
      </>
    )
  }

  return (
    <>
      <section className="panel address-hero-panel">
        <div className="address-hero">
          <div className="address-hero-copy">
            <p className="eyebrow">ADDRESS MONITOR</p>
            <h2>地址监控</h2>
          </div>
          <div className="address-hero-stats">
            <div className="address-hero-stat">
              <span>监控池</span>
              <strong>{formatNumber(monitoredAddresses.length, 0)}</strong>
            </div>
            <div className="address-hero-stat">
              <span>开放跟单</span>
              <strong>{formatNumber(activeCopyCount, 0)}</strong>
            </div>
            <div className="address-hero-stat">
              <span>只观察</span>
              <strong>{formatNumber(observeCount, 0)}</strong>
            </div>
            <div className="address-hero-stat">
              <span>当前地址</span>
              <strong>{currentAddressLabel}</strong>
            </div>
          </div>
        </div>

        {managementMessage ? <div className="export-status">{managementMessage}</div> : null}
      </section>

      <section className="panel address-control-panel">
        <div className="address-control-deck sticky-toolbar">
          <div className="address-control-copy">
            <div>
              <p className="eyebrow">COMMAND DECK</p>
              <h2>地址筛选</h2>
            </div>
          </div>

          <div className="address-kpi-ribbon">
            {overviewCards.map((card) => {
              const active = tierFilter === card.key || (card.key === 'ALL' && tierFilter === 'ALL')
              return (
                <button
                  key={card.key}
                  type="button"
                  className={`address-kpi-chip ${active ? 'active' : ''}`}
                  onClick={() => setTierFilter(card.key === 'ALL' ? 'ALL' : card.key)}
                >
                  <span className="address-kpi-label">{card.title}</span>
                  <strong>{formatNumber(card.count, 0)}</strong>
                  <span>{card.subtitle}</span>
                  <small>均值质量 {formatPercent(card.avgQuality)} / 30D 胜率 {formatPercent(card.avgWinRate)}</small>
                </button>
              )
            })}
          </div>

          <div className="table-toolbar address-toolbar">
            <div className="filter-bar">
              <input
                className="table-input"
                value={keyword}
                placeholder="搜索地址 / 别名 / 摘要"
                onChange={(event) => setKeyword(event.target.value)}
              />
              <select value={tierFilter} onChange={(event) => setTierFilter(event.target.value)}>
                <option value="ALL">全部分层</option>
                <option value="EXECUTABLE_A">EXECUTABLE_A 重点开放</option>
                <option value="EXECUTABLE_B">EXECUTABLE_B 可跟单观察</option>
                <option value="OBSERVE">OBSERVE 只观察</option>
                <option value="BLOCKLIST">BLOCKLIST 阻断</option>
              </select>
              <select value={sourceFilter} onChange={(event) => setSourceFilter(event.target.value)}>
                <option value="ALL">全部胜率来源</option>
                <option value="LATENCY_ALPHA">{getWinSourceMeta('LATENCY_ALPHA').label}</option>
                <option value="SWING_ALPHA">{getWinSourceMeta('SWING_ALPHA').label}</option>
                <option value="SETTLEMENT_ALPHA">{getWinSourceMeta('SETTLEMENT_ALPHA').label}</option>
                <option value="RISK_CONTROL_ALPHA">{getWinSourceMeta('RISK_CONTROL_ALPHA').label}</option>
                <option value="UNKNOWN">UNKNOWN 未知</option>
              </select>
              <select value={archetypeFilter} onChange={(event) => setArchetypeFilter(event.target.value)}>
                <option value="ALL">全部地址类型</option>
                <option value="HF">{getArchetypeMeta('HF').label}</option>
                <option value="SWING">{getArchetypeMeta('SWING').label}</option>
                <option value="THEMATIC">{getArchetypeMeta('THEMATIC').label}</option>
                <option value="LOW_FREQ_VALUE">{getArchetypeMeta('LOW_FREQ_VALUE').label}</option>
                <option value="NOISE">{getArchetypeMeta('NOISE').label}</option>
                <option value="UNKNOWN">UNKNOWN 未定型</option>
              </select>
            </div>

            <div className="address-toolbar-actions">
              <div className="segmented-control">
                {WATCH_GROUP_OPTIONS.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    className={watchGroup === option.value ? 'active' : ''}
                    onClick={() => setWatchGroup(option.value)}
                    title={option.hint}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
              <button
                type="button"
                className="btn btn-secondary table-reset-btn"
                onClick={() => {
                  setKeyword('')
                  setTierFilter('ALL')
                  setSourceFilter('ALL')
                  setArchetypeFilter('ALL')
                  setWatchGroup('all')
                }}
              >
                重置
              </button>
            </div>
          </div>
        </div>
      </section>

      <section className="panel address-full-panel">
        <div className="section-head">
          <h2>监控地址</h2>
        </div>

        <div className="address-group-rail">
          {groupedMonitoredAddresses.map((group) => (
            <button
              key={group.key}
              type="button"
              className={`address-group-card ${tierFilter === group.key ? 'active' : ''}`}
              onClick={() => setTierFilter(group.key)}
            >
              <div className="address-group-card-head">
                <div>
                  <h3>{group.title}</h3>
                </div>
                <span className={getTierBadgeClass(group.key)}>{group.key}</span>
              </div>
              <div className="address-group-card-metrics">
                <span>地址数 {formatNumber(group.rows.length, 0)}</span>
                <span>均值质量 {formatPercent(averageMetric(group.rows, (row) => row.qualityScore))}</span>
                <span>30D 胜率 {formatPercent(averageMetric(group.rows, (row) => row.recentWinRate30d))}</span>
              </div>
            </button>
          ))}
        </div>

        {monitoredLoading ? (
          <div className="loading">监控地址加载中...</div>
        ) : filteredMonitoredAddresses.length === 0 ? (
          <div className="empty-state">当前筛选条件下没有可展示的监控地址</div>
        ) : (
          groupedMonitoredAddresses.map((group) => (
            <section key={group.key} className="address-group-section">
              <div className="address-group-head">
                <div>
                  <h3>{group.title}</h3>
                </div>
                <div className="address-group-head-meta">
                  <span>{group.rows.length} 个地址</span>
                  <span>均值质量 {formatPercent(averageMetric(group.rows, (row) => row.qualityScore))}</span>
                  <span>30D 胜率 {formatPercent(averageMetric(group.rows, (row) => row.recentWinRate30d))}</span>
                </div>
              </div>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>地址</th>
                      <th>类型</th>
                      <th>分层</th>
                      <th>胜率来源</th>
                      <th>7D胜率</th>
                      <th>30D胜率</th>
                      <th>订单胜率</th>
                      <th>质量 / 复制 / 稳定</th>
                      <th>建议退出</th>
                      <th>补充判断</th>
                      <th>最近活跃</th>
                      <th>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {group.rows.map((row) => {
                      const actionDisabled =
                        observeOnlyMutation.isPending ||
                        row.screenerTier === 'OBSERVE' ||
                        row.screenerTier === 'BLOCKLIST'
                      const actionLabel =
                        row.screenerTier === 'OBSERVE' || row.screenerTier === 'BLOCKLIST'
                          ? '不开放'
                          : '只观察'
                      const archetypeMeta = getArchetypeMeta(row.archetype)
                      const followLaneMeta = getFollowLaneMeta(row.followLane)
                      const winSourceMeta = getWinSourceMeta(row.winSourceType)
                      const exitLaneMeta = getExitLaneMeta(row.recommendedExitLane)

                      return (
                        <tr
                          key={row.walletAddress}
                          onClick={() => openWalletDetail(row.walletAddress)}
                          style={{ cursor: 'pointer' }}
                        >
                          <td>
                            <div className="address-primary-cell">
                              <strong>{row.alias || compactAddress(row.walletAddress, 8, 6)}</strong>
                              <span>{compactAddress(row.walletAddress, 10, 6)}</span>
                            </div>
                          </td>
                          <td>
                            <div className="address-badge-stack">
                              <span className="badge" title={archetypeMeta.description}>{archetypeMeta.code}</span>
                              {row.highFrequencyTradingAddress && row.archetype !== 'HF' ? <span className="badge warn">HF</span> : null}
                            </div>
                            <div className="table-subtext">{archetypeMeta.label}</div>
                            <div className="table-subtext">{followLaneMeta.label}</div>
                          </td>
                          <td>
                            <span className={getTierBadgeClass(row.screenerTier)}>{row.screenerTier}</span>
                            <div className="table-subtext">{buildStatusNote(row)}</div>
                          </td>
                          <td>
                            <span className={getSourceBadgeClass(row.winSourceType)}>{winSourceMeta.code}</span>
                            <div className="table-subtext">{winSourceMeta.label}</div>
                          </td>
                          <td>{formatPercent(row.recentWinRate7d)}</td>
                          <td>{formatPercent(row.recentWinRate30d)}</td>
                          <td>{formatPercent(row.orderWinRate)}</td>
                          <td>
                            <div>{formatPercent(row.qualityScore)} / {formatPercent(row.replicabilityScore)} / {formatPercent(row.stabilityScore)}</div>
                            <div className="table-subtext">可复制率 {formatPercent(row.followabilityRate)}</div>
                          </td>
                          <td>
                            <div>{exitLaneMeta.label}</div>
                            <div className="table-subtext">{exitLaneMeta.description}</div>
                          </td>
                          <td>
                            <div>{`新鲜度 ${formatPercent(row.freshnessScore)}`}</div>
                            <div className="table-subtext">{`市场集中 ${formatPercent(row.concentrationMarket30d)}`}</div>
                          </td>
                          <td>{row.lastTradeAt ? new Date(row.lastTradeAt).toLocaleDateString('zh-CN') : '-'}</td>
                          <td>
                            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                              <button
                                type="button"
                                className="btn btn-primary address-action-btn"
                                onClick={(event) => {
                                  event.stopPropagation()
                                  openWalletDetail(row.walletAddress)
                                }}
                              >
                                查看详情
                              </button>
                              <button
                                type="button"
                                className="btn btn-secondary address-action-btn"
                                disabled={actionDisabled}
                                onClick={(event) => {
                                  event.stopPropagation()
                                  if (!actionDisabled) {
                                    observeOnlyMutation.mutate(row.walletAddress)
                                  }
                                }}
                              >
                                {observeOnlyMutation.isPending && observeOnlyMutation.variables === row.walletAddress
                                  ? '处理中'
                                  : actionLabel}
                              </button>
                            </div>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            </section>
          ))
        )}
      </section>

      <section className="panel address-full-panel">
        <div className="section-head">
          <h2>跟单地址</h2>
        </div>

        {executableLoading ? (
          <div className="loading">正在跟单地址加载中...</div>
        ) : executableAddresses.length === 0 ? (
          <div className="empty-state">当前没有开放中的跟单地址</div>
        ) : (
          <div className="address-card-strip">
            {executableAddresses.map((row) => {
              const archetypeMeta = getArchetypeMeta(row.archetype)
              const followLaneMeta = getFollowLaneMeta(row.followLane)
              const winSourceMeta = getWinSourceMeta(row.winSourceType)
              return (
                <button
                  key={row.walletAddress}
                  type="button"
                  className="address-card"
                  onClick={() => openWalletDetail(row.walletAddress)}
                >
                  <div className="address-card-top">
                    <strong>{row.alias || compactAddress(row.walletAddress, 8, 6)}</strong>
                    <span className={getTierBadgeClass(row.screenerTier)}>{row.screenerTier}</span>
                  </div>
                  <div className="address-card-meta">
                    <span>{archetypeMeta.label}</span>
                    <span>{followLaneMeta.label}</span>
                  </div>
                  <div className="address-card-metrics">
                    <span>质量 {formatPercent(row.qualityScore)}</span>
                    <span>复制 {formatPercent(row.replicabilityScore)}</span>
                  </div>
                  <div className="address-card-metrics">
                    <span>7D {formatPercent(row.recentWinRate7d)}</span>
                    <span>30D {formatPercent(row.recentWinRate30d)}</span>
                  </div>
                  <div className="address-card-metrics">
                    <span>{winSourceMeta.code}</span>
                    <span>结算 {formatPercent(row.settlementWinRate)}</span>
                  </div>
                  <div className="address-card-summary">{truncateText(row.summary, 120) || '暂无摘要'}</div>
                </button>
              )
            })}
          </div>
        )}
      </section>
    </>
  )
}
