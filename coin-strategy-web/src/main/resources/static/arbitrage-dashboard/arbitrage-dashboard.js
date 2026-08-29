const REFRESH_SECONDS = 10;
const API_BASE = "/strategy/api/strategy-platform/arbitrage-dashboard";

const demoSnapshot = {
  asOf: "2026-08-30 12:40:06 UTC",
  source: "DEMO · 等待读模型 API",
  partial: true,
  missingExchanges: ["BINANCE"],
  coverage: { total: 42, displayed: 18, qualified: 6, missingL2: 8, stale: 4, unavailable: 12 },
  health: [
    { exchange: "HTX", healthy: true, success: 99, latency: 760, note: "L2 完整" },
    { exchange: "OKX", healthy: true, success: 99, latency: 477, note: "L2 完整" },
    { exchange: "BITGET", healthy: true, success: 99, latency: 351, note: "L2 完整" },
    { exchange: "BINANCE", healthy: false, success: 0, latency: 0, note: "HTTP 451 · 地域访问受限" },
  ],
  opportunities: [
    { id: "eth-okx-htx", kind: "CROSS_FUNDING", symbol: "ETHUSDT", a: { exchange: "HTX", contract: "ETH-USDT", funding: "-0.0072%", interval: "8h", countdown: "02:18:44", vwap: "3,418.82", volume: "$1.26B", oi: "$488M" }, b: { exchange: "OKX", contract: "ETH-USDT-SWAP", funding: "+0.0248%", interval: "8h", countdown: "02:18:37", vwap: "3,425.11", volume: "$2.13B", oi: "$901M" }, gross: "+0.0320%", cost: "0.0200%", net: 0.012, netText: "+0.0120%", annualized: "+13.1%", capacity: 12800, capacityText: "$12.8K", basis: "+0.184%", historical: ["+0.018%", "+0.104%", "+0.390%"], status: "QUALIFIED", reason: "", stable: "4m 22s / 3m", aDepth: [["3,418.82", "$4.8K"], ["3,419.74", "$5.1K"], ["3,420.60", "$2.9K"]], bDepth: [["3,425.11", "$5.0K"], ["3,424.28", "$4.6K"], ["3,423.65", "$3.2K"]] },
    { id: "sol-bitget-okx", kind: "CROSS_FUNDING", symbol: "SOLUSDT", a: { exchange: "BITGET", contract: "SOLUSDT", funding: "-0.0050%", interval: "8h", countdown: "02:18:41", vwap: "147.24", volume: "$611M", oi: "$162M" }, b: { exchange: "OKX", contract: "SOL-USDT-SWAP", funding: "+0.0180%", interval: "8h", countdown: "02:18:38", vwap: "147.61", volume: "$844M", oi: "$310M" }, gross: "+0.0230%", cost: "0.0200%", net: 0.003, netText: "+0.0030%", annualized: "+3.3%", capacity: 6200, capacityText: "$6.2K", basis: "+0.251%", historical: ["+0.013%", "+0.054%", "+0.188%"], status: "OBSERVING", reason: "成本覆盖倍数 1.15，低于 1.25 阈值", stable: "6m 09s / 3m", aDepth: [["147.24", "$2.4K"], ["147.28", "$2.1K"], ["147.31", "$1.7K"]], bDepth: [["147.61", "$2.7K"], ["147.57", "$2.0K"], ["147.52", "$1.5K"]] },
    { id: "btc-htx-okx", kind: "CROSS_FUNDING", symbol: "BTCUSDT", a: { exchange: "HTX", contract: "BTC-USDT", funding: "+0.0020%", interval: "8h", countdown: "02:18:44", vwap: "112,084.2", volume: "$1.82B", oi: "$714M" }, b: { exchange: "OKX", contract: "BTC-USDT-SWAP", funding: "+0.0220%", interval: "8h", countdown: "02:26:32", vwap: "112,396.8", volume: "$3.05B", oi: "$1.32B" }, gross: "+0.0200%", cost: "0.0200%", net: 0, netText: "+0.0000%", annualized: "—", capacity: 0, capacityText: "—", basis: "+0.279%", historical: ["+0.005%", "+0.041%", "+0.111%"], status: "REJECTED", reason: "SETTLEMENT_TIME_SKEW：两边下一结算相差 468 秒", stable: "—", aDepth: [], bDepth: [] },
    { id: "eth-htx-bitget", kind: "CROSS_FUNDING", symbol: "ETHUSDT", a: { exchange: "HTX", contract: "ETH-USDT", funding: "-0.0072%", interval: "8h", countdown: "02:18:44", vwap: "3,418.82", volume: "$1.26B", oi: "$488M" }, b: { exchange: "BITGET", contract: "ETHUSDT", funding: "+0.0110%", interval: "8h", countdown: "02:18:42", vwap: "3,424.90", volume: "$938M", oi: "$355M" }, gross: "+0.0182%", cost: "0.0200%", net: -0.0018, netText: "-0.0018%", annualized: "—", capacity: 9400, capacityText: "$9.4K", basis: "+0.178%", historical: ["+0.009%", "+0.032%", "+0.127%"], status: "REJECTED", reason: "COST_COVERAGE_BELOW_THRESHOLD：毛收益未覆盖开平成本", stable: "5m 31s / 3m", aDepth: [], bDepth: [] },
    { id: "eth-price-okx-htx", kind: "CROSS_PRICE", symbol: "ETHUSDT", a: { exchange: "OKX", contract: "ETH-USDT-SWAP", funding: "+0.0248%", interval: "8h", countdown: "02:18:37", vwap: "3,429.44", volume: "$2.13B", oi: "$901M" }, b: { exchange: "HTX", contract: "ETH-USDT", funding: "-0.0072%", interval: "8h", countdown: "02:18:44", vwap: "3,418.82", volume: "$1.26B", oi: "$488M" }, gross: "+0.311%", cost: "0.140%", net: 0.171, netText: "+0.171%", annualized: "研究指标", capacity: 8700, capacityText: "$8.7K", basis: "+0.311%", historical: ["+0.069%", "+0.220%", "+0.542%"], status: "OBSERVING", reason: "OBSERVE_ONLY：跨所价格扫描尚未接入 Paper 执行", stable: "—", aDepth: [["3,429.44", "$4.2K"], ["3,428.91", "$4.5K"]], bDepth: [["3,418.82", "$3.4K"], ["3,419.15", "$2.6K"]] },
    { id: "sol-price-bitget-okx", kind: "CROSS_PRICE", symbol: "SOLUSDT", a: { exchange: "OKX", contract: "SOL-USDT-SWAP", funding: "+0.0180%", interval: "8h", countdown: "02:18:38", vwap: "147.74", volume: "$844M", oi: "$310M" }, b: { exchange: "BITGET", contract: "SOLUSDT", funding: "-0.0050%", interval: "8h", countdown: "02:18:41", vwap: "147.21", volume: "$611M", oi: "$162M" }, gross: "+0.360%", cost: "0.140%", net: 0.22, netText: "+0.220%", annualized: "研究指标", capacity: 5400, capacityText: "$5.4K", basis: "+0.360%", historical: ["+0.042%", "+0.161%", "+0.313%"], status: "OBSERVING", reason: "OBSERVE_ONLY：等待价差持续性与执行延迟统计", stable: "—", aDepth: [], bDepth: [] },
    { id: "btc-single", kind: "SINGLE_EXCHANGE", symbol: "BTCUSDT", a: { exchange: "HTX", contract: "BTC/USDT · 现货", funding: "买入现货", interval: "", countdown: "—", vwap: "112,110.0", volume: "$1.82B", oi: "—" }, b: { exchange: "HTX", contract: "BTC-USDT · 永续空", funding: "+0.0140%", interval: "8h", countdown: "02:18:44", vwap: "112,286.1", volume: "$1.82B", oi: "$714M" }, gross: "+0.0140%", cost: "0.100%", net: 0.006, netText: "+0.0060%", annualized: "+6.6%", capacity: 1000, capacityText: "$1.0K", basis: "+0.157%", historical: ["+0.011%", "+0.078%", "+0.281%"], status: "QUALIFIED", reason: "保证金缓冲、现货与合约数量均满足限制", stable: "资金费有效", aDepth: [["112,110.0", "$750"], ["112,116.0", "$250"]], bDepth: [["112,286.1", "$620"], ["112,279.5", "$380"]] },
    { id: "eth-single", kind: "SINGLE_EXCHANGE", symbol: "ETHUSDT", a: { exchange: "HTX", contract: "ETH/USDT · 现货", funding: "买入现货", interval: "", countdown: "—", vwap: "3,420.14", volume: "$1.26B", oi: "—" }, b: { exchange: "HTX", contract: "ETH-USDT · 永续空", funding: "-0.0072%", interval: "8h", countdown: "02:18:44", vwap: "3,418.82", volume: "$1.26B", oi: "$488M" }, gross: "-0.0072%", cost: "0.100%", net: -0.1072, netText: "-0.1072%", annualized: "—", capacity: 0, capacityText: "—", basis: "-0.039%", historical: ["-0.004%", "+0.013%", "+0.044%"], status: "REJECTED", reason: "资金费方向不利：现货多 + 永续空会支付资金费", stable: "—", aDepth: [], bDepth: [] },
  ],
};

const state = { mode: "CROSS_PRICE", query: "", exchange: "ALL", minCapacity: 0, minYield: 0, qualifiedOnly: false, snapshot: demoSnapshot, selected: null, seconds: REFRESH_SECONDS, apiConnected: false, refreshing: false };
const $ = (id) => document.getElementById(id);
const escape = (value) => String(value ?? "").replace(/[&<>'"]/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" }[char]));
const fmtCapacity = (value) => value ? `$${(value / 1000).toFixed(value >= 10000 ? 1 : 2)}K` : "—";
const signedClass = (value) => value > 0 ? "positive" : value < 0 ? "negative" : "neutral";

function initFromUrl() {
  const params = new URLSearchParams(location.search);
  state.mode = params.get("mode") || state.mode;
  state.query = params.get("symbol") || "";
  state.exchange = params.get("exchange") || "ALL";
  state.minCapacity = Number(params.get("minCapacity") || 0);
  state.minYield = Number(params.get("minYield") || 0);
  state.qualifiedOnly = params.get("qualified") === "1";
}

function updateUrl() {
  const params = new URLSearchParams();
  params.set("mode", state.mode);
  if (state.query) params.set("symbol", state.query);
  if (state.exchange !== "ALL") params.set("exchange", state.exchange);
  if (state.minCapacity) params.set("minCapacity", state.minCapacity);
  if (state.minYield) params.set("minYield", state.minYield);
  if (state.qualifiedOnly) params.set("qualified", "1");
  history.replaceState({}, "", `${location.pathname}?${params}`);
}

function currentRows() {
  return state.snapshot.opportunities.filter((row) => {
    const queryMatch = !state.query || row.symbol.toUpperCase().includes(state.query.toUpperCase());
    const exchangeMatch = state.exchange === "ALL" || row.a.exchange === state.exchange || row.b.exchange === state.exchange;
    const capacityMatch = row.capacity >= state.minCapacity;
    const yieldMatch = state.mode === "CROSS_PRICE" || row.net >= state.minYield;
    const statusMatch = !state.qualifiedOnly || row.status === "QUALIFIED";
    return row.kind === state.mode && queryMatch && exchangeMatch && capacityMatch && yieldMatch && statusMatch;
  }).sort((a, b) => b.net * b.capacity - a.net * a.capacity);
}

function renderHeader() {
  $("asOf").textContent = `asOf ${state.snapshot.asOf}`;
  $("sourceName").textContent = state.apiConnected ? "看板读模型 · 在线" : "演示数据 · API 待读模型接入";
  const partial = state.snapshot.partial ? ` · 部分数据：${(state.snapshot.missingExchanges || []).join("、") || "已标识"}` : "";
  $("sourceDetail").textContent = `${state.snapshot.source || "PAPER_PUBLIC_ESTIMATE"}${partial}`;
}

function renderMetrics(rows) {
  const qualified = rows.filter((row) => row.status === "QUALIFIED");
  const best = qualified[0] || rows[0];
  const largest = rows.reduce((current, row) => row.capacity > (current?.capacity || 0) ? row : current, null);
  const c = state.snapshot.coverage;
  $("qualifiedCount").textContent = String(qualified.length);
  $("bestYield").textContent = best ? best.netText : "—";
  $("bestCapacity").textContent = largest?.capacityText || "—";
  $("coverageRate").textContent = `${Math.round((c.displayed / c.total) * 100)}%`;
  $("coverageHint").textContent = `${c.displayed}/${c.total} 组合可展示 · 缺 L2 ${c.missingL2}`;
}

function renderTabs() {
  document.querySelectorAll(".mode-tab").forEach((button) => button.classList.toggle("active", button.dataset.mode === state.mode));
  $("minYield").closest("label").classList.toggle("hidden", state.mode === "CROSS_PRICE");
}

function populateExchanges() {
  const select = $("exchangeFilter");
  const known = [...new Set(state.snapshot.opportunities.flatMap((row) => [row.a.exchange, row.b.exchange]))].sort();
  select.innerHTML = `<option value="ALL">全部交易所</option>${known.map((exchange) => `<option value="${escape(exchange)}">${escape(exchange)}</option>`).join("")}`;
  if (!known.includes(state.exchange) && state.exchange !== "ALL") state.exchange = "ALL";
  select.value = state.exchange;
}

function tableHeaders() {
  if (state.mode === "CROSS_PRICE") return ["币种", "高价腿 · 卖出", "低价腿 · 买入", "报价 / VWAP", "开仓价差", "费用后价差", "容量", "24h · 7d · 30d", "状态", ""];
  if (state.mode === "SINGLE_EXCHANGE") return ["币种", "现货腿 · 买入", "永续腿 · 卖出", "资金费 / 下次结算", "当前基差", "单次净收益", "可用额度", "24h · 7d · 30d", "状态", ""];
  return ["币种", "低资金费腿 · 多", "高资金费腿 · 空", "资金费 / 下次结算", "毛收益 / 成本", "单次净收益", "L2 容量", "24h · 7d · 30d", "状态", ""];
}

function legHtml(leg) {
  return `<div class="leg-cell"><span class="exchange">${escape(leg.exchange)}</span><span class="contract">${escape(leg.contract)}</span><div class="subline">Vol ${escape(leg.volume)} · OI ${escape(leg.oi)}</div></div>`;
}

function priceHtml(row) { return `<div>${escape(row.a.vwap)} <span class="pair-divider">/</span> ${escape(row.b.vwap)}</div><div class="subline">VWAP · 同一基础币数量</div>`; }
function fundingHtml(row) { return `<div>${escape(row.a.funding)} <span class="pair-divider">/</span> ${escape(row.b.funding)}</div><div class="subline">${escape(row.a.interval)} · ${escape(row.a.countdown)} <span class="pair-divider">|</span> ${escape(row.b.interval)} · ${escape(row.b.countdown)}</div>`; }
function historyHtml(row) { return `<div class="${signedClass(Number.parseFloat(row.historical[0]))}">${escape(row.historical[0])}</div><div class="subline">${escape(row.historical[1])} <span class="pair-divider">/</span> ${escape(row.historical[2])}</div>`; }
function statusHtml(row) { const type = row.status === "QUALIFIED" ? "qualified" : row.status === "REJECTED" ? "rejected" : "observing"; const label = row.status === "QUALIFIED" ? "可执行" : row.status === "REJECTED" ? "已拒绝" : "观察中"; return `<span class="status ${type}"><i></i>${label}</span>`; }

function renderTable(rows) {
  $("tableHead").innerHTML = `<tr>${tableHeaders().map((header) => `<th>${header}</th>`).join("")}</tr>`;
  if (!rows.length) { $("opportunityRows").innerHTML = `<tr><td class="empty-row" colspan="10">没有满足当前筛选条件的组合</td></tr>`; return; }
  $("opportunityRows").innerHTML = rows.map((row) => {
    const mid = state.mode === "CROSS_PRICE" ? `<td>${priceHtml(row)}</td><td class="positive yield">${escape(row.gross)}</td><td class="${signedClass(row.net)} yield">${escape(row.netText)}</td>`
      : state.mode === "SINGLE_EXCHANGE" ? `<td>${fundingHtml(row)}</td><td class="${signedClass(row.net)}">${escape(row.basis)}</td><td class="${signedClass(row.net)} yield">${escape(row.netText)}</td>`
        : `<td>${fundingHtml(row)}</td><td><div class="positive">${escape(row.gross)}</div><div class="subline">成本 ${escape(row.cost)}</div></td><td class="${signedClass(row.net)} yield">${escape(row.netText)}</td>`;
    return `<tr class="data-row" data-id="${escape(row.id)}"><td><div class="asset">${escape(row.symbol)}</div><div class="subline">${escape(row.annualized)}</div></td>${legHtml(row.a)}${legHtml(row.b)}${mid}<td><div>${escape(row.capacityText)}</div><div class="subline">basis ${escape(row.basis)}</div></td><td>${historyHtml(row)}</td><td>${statusHtml(row)}</td><td>›</td></tr>`;
  }).join("");
  document.querySelectorAll(".data-row").forEach((row) => row.addEventListener("click", () => openDrawer(row.dataset.id)));
}

function renderScanMeta(rows) {
  const c = state.snapshot.coverage;
  const qualified = rows.filter((row) => row.status === "QUALIFIED").length;
  const names = { CROSS_PRICE: "跨所价格扫描", CROSS_FUNDING: "跨所资金费扫描", SINGLE_EXCHANGE: "同所期现 / 永续" };
  $("scanSummary").textContent = `${names[state.mode]} · 当前显示 ${rows.length} 个组合，其中可执行 ${qualified} 个`;
  $("hiddenSummary").textContent = `总组合 ${c.total} · 缺 L2 ${c.missingL2} · 过期 ${c.stale} · 交易所不可用 ${c.unavailable}`;
  $("pageSummary").textContent = "筛选作用于全部组合 · 点击行查看 L2 与收益拆解";
}

function renderHealth() {
  $("healthRows").innerHTML = state.snapshot.health.map((item) => `<div class="health-row"><div class="health-state ${item.healthy ? "good" : "bad"}"><i></i><strong>${escape(item.exchange)}</strong></div><div class="health-bar"><span style="width:${item.success}%"></span></div><span>${item.latency ? `${item.latency}ms` : "—"}</span><span class="health-error muted">${escape(item.note)}</span></div>`).join("");
}

function drawerStat(label, value, className = "") { return `<div class="detail-stat"><span>${label}</span><strong class="${className}">${value}</strong></div>`; }
function depthBook(title, levels, positive) { return `<div class="depth-book"><h4>${title}</h4>${levels.length ? levels.map(([price, size]) => `<div class="depth-level"><span class="${positive ? "positive" : "negative"}">${escape(price)}</span><span>${escape(size)}</span></div>`).join("") : `<div class="muted">本组合未保留完整 L2</div>`}</div>`; }
function depthTitle(row, side) {
  const leg = row[side];
  const suffix = row.kind === "CROSS_PRICE" ? (side === "a" ? "高价腿 · 卖出" : "低价腿 · 买入") : (side === "a" ? "买入 / 多头腿" : "卖出 / 空头腿");
  return `${leg.exchange} · ${leg.contract} · ${suffix}`;
}

function openDrawer(id) {
  const row = state.snapshot.opportunities.find((item) => item.id === id); if (!row) return;
  state.selected = row;
  $("drawerMode").textContent = row.kind.replaceAll("_", " · "); $("drawerTitle").textContent = `${row.symbol} · ${row.a.exchange} ↔ ${row.b.exchange}`;
  $("drawerContent").innerHTML = `
    <section class="drawer-section"><div class="detail-grid">${drawerStat("单次净收益", row.netText, signedClass(row.net))}${drawerStat("可成交容量", row.capacityText)}${drawerStat("可成交 basis", row.basis)}${drawerStat("稳定窗口", row.stable)}</div></section>
    <section class="drawer-section"><h3>收益拆解</h3><div class="waterfall"><div class="waterfall-row"><span>毛资金费 / 价格差</span><b class="positive">${row.gross}</b></div><div class="waterfall-row"><span>开平手续费、滑点与 buffer</span><b class="negative">-${row.cost}</b></div><div class="waterfall-row total"><span>下一次同步结算净收益</span><b>${row.netText}</b></div></div></section>
    <section class="drawer-section"><h3>相同基础币数量下的 L2 成交</h3><div class="depth-columns">${depthBook(depthTitle(row, "a"), row.aDepth || [], row.kind !== "CROSS_PRICE")}${depthBook(depthTitle(row, "b"), row.bDepth || [], row.kind === "CROSS_PRICE")}</div></section>
    <section class="drawer-section"><h3>历史实际资金费</h3><div class="detail-grid">${drawerStat("24 小时", row.historical[0], "positive")}${drawerStat("7 天", row.historical[1], "positive")}${drawerStat("30 天", row.historical[2], "positive")}${drawerStat("年化等效", row.annualized)}</div></section>
    ${row.reason ? `<section class="drawer-section"><h3>当前判断</h3><div class="reason-box">${escape(row.reason)}</div></section>` : ""}`;
  $("detailDrawer").classList.add("open"); $("drawerScrim").classList.add("open"); $("detailDrawer").setAttribute("aria-hidden", "false");
}

function closeDrawer() { $("detailDrawer").classList.remove("open"); $("drawerScrim").classList.remove("open"); $("detailDrawer").setAttribute("aria-hidden", "true"); }
function render() { const rows = currentRows(); renderHeader(); renderTabs(); renderMetrics(rows); renderTable(rows); renderScanMeta(rows); renderHealth(); updateUrl(); }

async function loadLiveSnapshot() {
  try {
    const response = await fetch(`${API_BASE}/overview`, { cache: "no-store", headers: { Accept: "application/json" } });
    if (!response.ok) throw new Error("dashboard API unavailable");
    const data = await response.json();
    if (!Array.isArray(data.opportunities) || !Array.isArray(data.health)) throw new Error("unexpected dashboard API response");
    state.snapshot = data; state.apiConnected = true;
  } catch (_) { state.snapshot = demoSnapshot; state.apiConnected = false; }
}

function showToast(message) { const toast = $("toast"); toast.textContent = message; toast.classList.add("show"); setTimeout(() => toast.classList.remove("show"), 2200); }
async function copyView() { await navigator.clipboard.writeText(location.href); showToast("已复制当前筛选视图链接"); }

function bind() {
  document.querySelectorAll(".mode-tab").forEach((button) => button.addEventListener("click", () => { state.mode = button.dataset.mode; render(); }));
  $("symbolFilter").addEventListener("input", (event) => { state.query = event.target.value.trim(); render(); });
  $("exchangeFilter").addEventListener("change", (event) => { state.exchange = event.target.value; render(); });
  $("minCapacity").addEventListener("input", (event) => { state.minCapacity = Number(event.target.value) || 0; render(); });
  $("minYield").addEventListener("input", (event) => { state.minYield = Number(event.target.value) || 0; render(); });
  $("qualifiedOnly").addEventListener("change", (event) => { state.qualifiedOnly = event.target.checked; render(); });
  $("resetFilters").addEventListener("click", () => { state.query = ""; state.exchange = "ALL"; state.minCapacity = 0; state.minYield = 0; state.qualifiedOnly = false; syncInputs(); render(); });
  $("copyView").addEventListener("click", () => copyView().catch(() => showToast("浏览器未允许复制，请手动复制地址栏")));
  $("closeDrawer").addEventListener("click", closeDrawer); $("drawerScrim").addEventListener("click", closeDrawer);
}
function syncInputs() { $("symbolFilter").value = state.query; $("exchangeFilter").value = state.exchange; $("minCapacity").value = state.minCapacity || ""; $("minYield").value = state.minYield || ""; $("qualifiedOnly").checked = state.qualifiedOnly; }
async function refreshSnapshot() {
  if (state.refreshing) return;
  state.refreshing = true;
  await loadLiveSnapshot();
  populateExchanges();
  syncInputs();
  render();
  state.refreshing = false;
}
function startClock() { setInterval(async () => { state.seconds -= 1; if (state.seconds <= 0) { state.seconds = REFRESH_SECONDS; await refreshSnapshot(); } $("countdown").textContent = `下次刷新 ${state.seconds}s`; }, 1000); }

async function init() { initFromUrl(); await loadLiveSnapshot(); populateExchanges(); syncInputs(); bind(); render(); $("countdown").textContent = `下次刷新 ${state.seconds}s`; startClock(); }
init();
