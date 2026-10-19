import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'

const staticScript = fs.readFileSync(new URL('../../coin-strategy-web/src/main/resources/static/dashboard/dashboard.js', import.meta.url), 'utf8')
test('static HTX panel requests the selected scope and displays terminal simulated facts', async () => {
  const panel = {}
  const requests = []
  const context = vm.createContext({
    document: { createElement: () => panel, querySelector: () => ({ prepend() {} }) },
    fetch: async (url) => { requests.push(url); return { ok: true, json: async () => ({
      source: 'HTX_TREND_PAPER_SIMULATED_READONLY', summary: { closedPositions: 3 },
      recentPositions: [{ accountLabel: 'paper', status: 'DONE', finalPnlSource: 'PAPER_SIMULATED', finalNetProfit: 18 }],
      recentOrders: Array(6).fill({ clientOrderId: 'paper-1' }), recentLedgerEvents: Array(6).fill({ eventType: 'OPEN_FILL' }),
    }) } },
  })
  vm.runInContext(staticScript.replace(/init\(\);\s*$/, ''), context)
  await vm.runInContext('loadHtxTrend()', context)
  assert.match(requests[0], /htx-trend\?mode=PAPER$/)
  assert.match(panel.innerHTML, /PAPER 模拟/)
  assert.match(panel.innerHTML, /PAPER_SIMULATED/)
  assert.match(panel.innerHTML, /DONE 3 · 订单 6 · 成交事件 6/)
  await vm.runInContext('state.positionMode="LIVE"; loadHtxTrend()', context)
  assert.match(requests[1], /mode=LIVE$/)
})

test('React query cache and API carry execution mode without any trading request', () => {
  const api = fs.readFileSync(new URL('../src/api/arbitrage-dashboard.api.ts', import.meta.url), 'utf8')
  const page = fs.readFileSync(new URL('../src/pages/ArbitrageScannerPage.tsx', import.meta.url), 'utf8')
  assert.match(api, /params: \{ mode \}/)
  assert.doesNotMatch(api, /apiClient\.(post|put|delete|patch)/)
  assert.match(page, /queryKey: \['htxTrendDashboard', executionMode\]/)
  assert.match(page, /getHtxTrendDashboard\(executionMode\)/)
  assert.match(page, /PAPER 模拟 · 只读/)
  assert.match(page, /position.finalNetProfit \?\?/)
})
