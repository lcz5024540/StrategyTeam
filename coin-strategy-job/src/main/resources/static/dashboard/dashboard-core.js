const strategyCatalog = [
  {
    code: "POLYMARKET_COPY",
    name: "Polymarket 跟单策略",
    category: "Polymarket",
    status: "ACTIVE",
    description: "基于 smart wallet 的 Polymarket 模拟跟单与风控策略。",
    placeholder: false
  },
  {
    code: "HEDGE",
    name: "对冲策略",
    category: "对冲",
    status: "PLANNED",
    description: "后续用于市场中性、风险对冲和跨市场保护仓位。",
    placeholder: true
  },
  {
    code: "ROBOT",
    name: "机器人策略",
    category: "机器人",
    status: "PLANNED",
    description: "后续用于事件驱动机器人、盘口套利和自动化规则执行。",
    placeholder: true
  },
  {
    code: "INTELLIGENT",
    name: "智能策略",
    category: "智能",
    status: "PLANNED",
    description: "后续用于模型评分、智能分层择时和多因子调度。",
    placeholder: true
  }
];

async function dashboardGetJson(url) {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`Request failed: ${url} status=${response.status}`);
  }
  return response.json();
}

async function loadStrategyDashboardOverview() {
  return dashboardGetJson("/strategy-platform/strategy/overview");
}

async function loadPaperSettlementReviewSummary(limit = 200) {
  const history = await dashboardGetJson(`/smart-wallet/positions/history?closedOnly=false`);
  const now = Date.now();
  const stalePaperPositions = (history || []).filter((row) => {
    if (!row || row.executionAddress !== "paper-book") {
      return false;
    }
    const status = String(row.status || "").toUpperCase();
    if (status !== "OPEN" && status !== "PARTIALLY_CLOSED") {
      return false;
    }
    const marketEndAt = Number(row.marketEndAt || 0);
    return Number.isFinite(marketEndAt) && marketEndAt > 0 && marketEndAt <= now;
  });
  return {
    staleCount: stalePaperPositions.length,
    sample: stalePaperPositions.slice(0, Math.max(limit, 1))
  };
}

function dashboardFormatMoney(value) {
  const num = Number(value || 0);
  return `${num >= 0 ? "+" : "-"}$${Math.abs(num).toFixed(2)}`;
}

function dashboardFormatPercent(value) {
  const num = Number(value || 0) * 100;
  return `${num.toFixed(2)}%`;
}

function dashboardClamp(value, min, max) {
  return Math.min(Math.max(value, min), max);
}

function dashboardAmountClass(value) {
  return Number(value || 0) >= 0 ? "positive" : "negative";
}

function dashboardGeneratedAtText() {
  return `数据生成时间：${new Date().toLocaleString("zh-CN", { hour12: false })}`;
}

function dashboardParseTime(value) {
  if (!value) {
    return 0;
  }
  const ts = Date.parse(value);
  return Number.isFinite(ts) ? ts : 0;
}

function dashboardSortOrders(rows) {
  return [...(rows || [])].sort((left, right) => {
    const rightTs = Math.max(dashboardParseTime(right?.placedAt), dashboardParseTime(right?.filledAt));
    const leftTs = Math.max(dashboardParseTime(left?.placedAt), dashboardParseTime(left?.filledAt));
    if (rightTs !== leftTs) {
      return rightTs - leftTs;
    }
    return Number(right?.id || 0) - Number(left?.id || 0);
  });
}

function dashboardIsAddressLike(value) {
  return /^0x[a-fA-F0-9]{10,}$/.test(String(value || "").trim());
}

function dashboardCompactMiddle(value, head = 6, tail = 4) {
  const text = String(value || "").trim();
  if (!text) {
    return "-";
  }
  if (text.length <= head + tail + 3) {
    return text;
  }
  return `${text.slice(0, head)}...${text.slice(-tail)}`;
}

function dashboardCompactText(value, maxLength = 36) {
  const text = String(value || "").trim();
  if (!text) {
    return "-";
  }
  if (text.length <= maxLength) {
    return text;
  }
  return `${text.slice(0, Math.max(maxLength - 3, 1))}...`;
}

function dashboardCompactLabel(value, maxLength = 36) {
  return dashboardIsAddressLike(value)
    ? dashboardCompactMiddle(value, 8, 6)
    : dashboardCompactText(value, maxLength);
}

function dashboardEscapeHtmlAttr(value) {
  return String(value || "")
    .replaceAll("&", "&amp;")
    .replaceAll("\"", "&quot;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

function dashboardTitleCell(value, maxLength = 44) {
  const full = String(value || "").trim();
  return `<span class="cell-truncate cell-question" title="${dashboardEscapeHtmlAttr(full || "-")}">${dashboardCompactText(full, maxLength)}</span>`;
}

function dashboardAddressCell(value, head = 8, tail = 6) {
  const full = String(value || "").trim();
  return `<span class="cell-truncate cell-mono" title="${dashboardEscapeHtmlAttr(full || "-")}">${dashboardCompactMiddle(full, head, tail)}</span>`;
}

function dashboardOrderSettlementText(row) {
  if (!row) {
    return "-";
  }
  const status = String(row.status || "").toUpperCase();
  if (["CANCELLED", "REJECTED", "FAILED", "EXPIRED"].includes(status)) {
    return "-";
  }
  return row.marketEndAtBjt || "-";
}

function dashboardPositionStatusText(status) {
  const normalized = String(status || "").toUpperCase();
  if (normalized === "OPEN") {
    return "持有中";
  }
  if (normalized === "PARTIALLY_CLOSED") {
    return "部分止盈";
  }
  if (normalized === "CLOSED") {
    return "已平仓";
  }
  if (normalized === "RESOLVED_UNCLAIMED") {
    return "已可领取未领取";
  }
  if (normalized === "CLAIMED") {
    return "已领取";
  }
  return status || "-";
}

function dashboardPlaceholderDetail(strategy) {
  return {
    code: strategy.code,
    name: strategy.name,
    description: strategy.description,
    netPnlUsdc: 0,
    compositePnlUsdc: 0,
    realizedPnlUsdc: 0,
    unrealizedPnlUsdc: 0,
    pendingSettlementPnlUsdc: 0,
    winRate: 0,
    totalOrders: 0,
    openPositions: 0,
    closedPositions: 0,
    maxDrawdownUsdc: 0,
    openConflictingMarkets: 0,
    remainingPositionSlots: 0,
    remainingCapitalBudgetUsdc: 0,
    availableCapitalUsdc: 0,
    lockedClaimProceedsUsdc: 0,
    positions: [],
    nextSettlements: [],
    recentOrders: [],
    conflicts: [],
    timeline: [],
    walletAttribution: [],
    marketAttribution: [],
    dailyReports: [],
    blockerStats: []
  };
}

function parsePositiveIntParam(name, fallbackValue) {
  const params = new URLSearchParams(window.location.search);
  const raw = Number(params.get(name));
  if (!Number.isFinite(raw) || raw <= 0) {
    return fallbackValue;
  }
  return Math.floor(raw);
}

function buildWindowSwitcher(activeDays, strategyCode) {
  const options = [7, 14, 30, 60];
  return `
    <div class="window-switcher">
      ${options.map((days) => `
        <a href="${window.location.pathname}?strategy=${strategyCode || "POLYMARKET_COPY"}&days=${days}"
           class="${days === activeDays ? "active" : ""}">
          ${days}天
        </a>
      `).join("")}
    </div>
  `;
}

async function loadPolymarketDashboardDetail(options = {}) {
  const days = options.days || 30;
  const attributionLimit = options.attributionLimit || 10;
  const baseDetail = await dashboardGetJson("/strategy-platform/strategy/POLYMARKET_COPY");

  const [walletAttribution, marketAttribution, dailyReports, blockerStats, stalePaperSettlementReview] = await Promise.all([
    dashboardGetJson(`/smart-wallet/reports/wallets?limit=${attributionLimit}`),
    dashboardGetJson(`/smart-wallet/reports/markets?limit=${attributionLimit}`),
    dashboardGetJson(`/smart-wallet/reports/daily?limit=${Math.max(days, 7)}`),
    dashboardGetJson("/smart-wallet/follow-tasks/blocker-stats?taskLimit=200"),
    loadPaperSettlementReviewSummary()
  ]);

  return {
    code: baseDetail?.summary?.code || "POLYMARKET_COPY",
    name: baseDetail?.summary?.name || "Polymarket 跟单策略",
    description: baseDetail?.summary?.description || "基于 smart wallet 的 Polymarket 模拟跟单与风控策略。",
    totalOrders: Number(baseDetail?.metrics?.totalOrders || 0),
    openPositions: Number(baseDetail?.metrics?.openPositions || 0),
    closedPositions: Number(baseDetail?.metrics?.closedPositions || 0),
    netPnlUsdc: Number(baseDetail?.metrics?.netPnlUsdc || 0),
    compositePnlUsdc: Number((baseDetail?.metrics?.compositePnlUsdc ?? baseDetail?.metrics?.netPnlUsdc) || 0),
    realizedPnlUsdc: Number(baseDetail?.metrics?.realizedPnlUsdc || 0),
    unrealizedPnlUsdc: Number(baseDetail?.metrics?.unrealizedPnlUsdc || 0),
    pendingSettlementPnlUsdc: Number((baseDetail?.metrics?.pendingSettlementPnlUsdc ?? baseDetail?.metrics?.unrealizedPnlUsdc) || 0),
    winRate: Number(baseDetail?.metrics?.winRate || 0),
    maxDrawdownUsdc: Number(baseDetail?.metrics?.maxDrawdownUsdc || 0),
    openConflictingMarkets: Number(baseDetail?.metrics?.openConflictingMarkets || 0),
    remainingPositionSlots: Number(baseDetail?.metrics?.remainingPositionSlots || 0),
    remainingCapitalBudgetUsdc: Number(baseDetail?.metrics?.remainingCapitalBudgetUsdc || 0),
    availableCapitalUsdc: Number(baseDetail?.metrics?.availableCapitalUsdc || 0),
    lockedClaimProceedsUsdc: Number(baseDetail?.metrics?.lockedClaimProceedsUsdc || 0),
    tradedVolumeUsdc: Number(baseDetail?.metrics?.tradedVolumeUsdc || 0),
    positions: baseDetail?.positions || [],
    nextSettlements: baseDetail?.nextSettlements || [],
    recentOrders: dashboardSortOrders(baseDetail?.recentOrders || []),
    conflicts: baseDetail?.conflicts || [],
    timeline: baseDetail?.timeline || [],
    insights: baseDetail?.insights || [],
    walletAttribution,
    marketAttribution,
    dailyReports,
    blockerStats,
    stalePaperSettlementReview,
    selectedDays: days
  };
}

async function loadStrategyDashboardDetail(code, options = {}) {
  if (code === "POLYMARKET_COPY") {
    return loadPolymarketDashboardDetail(options);
  }
  const detail = await dashboardGetJson(`/strategy-platform/strategy/${code}`);
  return {
    code: detail?.summary?.code || code,
    name: detail?.summary?.name || code,
    description: detail?.summary?.description || "",
    totalOrders: Number(detail?.metrics?.totalOrders || 0),
    openPositions: Number(detail?.metrics?.openPositions || 0),
    closedPositions: Number(detail?.metrics?.closedPositions || 0),
    netPnlUsdc: Number(detail?.metrics?.netPnlUsdc || 0),
    compositePnlUsdc: Number((detail?.metrics?.compositePnlUsdc ?? detail?.metrics?.netPnlUsdc) || 0),
    realizedPnlUsdc: Number(detail?.metrics?.realizedPnlUsdc || 0),
    unrealizedPnlUsdc: Number(detail?.metrics?.unrealizedPnlUsdc || 0),
    pendingSettlementPnlUsdc: Number((detail?.metrics?.pendingSettlementPnlUsdc ?? detail?.metrics?.unrealizedPnlUsdc) || 0),
    winRate: Number(detail?.metrics?.winRate || 0),
    maxDrawdownUsdc: Number(detail?.metrics?.maxDrawdownUsdc || 0),
    openConflictingMarkets: Number(detail?.metrics?.openConflictingMarkets || 0),
    remainingPositionSlots: Number(detail?.metrics?.remainingPositionSlots || 0),
    remainingCapitalBudgetUsdc: Number(detail?.metrics?.remainingCapitalBudgetUsdc || 0),
    availableCapitalUsdc: Number(detail?.metrics?.availableCapitalUsdc || 0),
    lockedClaimProceedsUsdc: Number(detail?.metrics?.lockedClaimProceedsUsdc || 0),
    positions: detail?.positions || [],
    nextSettlements: detail?.nextSettlements || [],
    recentOrders: dashboardSortOrders(detail?.recentOrders || []),
    conflicts: detail?.conflicts || [],
    timeline: detail?.timeline || [],
    insights: detail?.insights || [],
    walletAttribution: [],
    marketAttribution: [],
    dailyReports: [],
    blockerStats: [],
    selectedDays: options.days || 30
  };
}

function renderDashboardNav(selectedCode) {
  return `
    <nav class="dashboard-nav">
      <a href="${dashboardPageHref("index.html", selectedCode || "POLYMARKET_COPY")}" class="${selectedCode === "HOME" ? "active" : ""}">总览</a>
      <a href="${dashboardPageHref("detail.html", selectedCode || "POLYMARKET_COPY")}" class="${selectedCode === "DETAIL" ? "active" : ""}">策略详情</a>
      <a href="${dashboardPageHref("charts.html", selectedCode || "POLYMARKET_COPY")}" class="${selectedCode === "CHARTS" ? "active" : ""}">图表分析</a>
      <a href="/swagger-ui.html">Swagger</a>
    </nav>
  `;
}

function renderGlobalFilterBar(activeStrategyCode) {
  const selectedDays = dashboardSelectedDays();
  return `
    <section class="panel filter-panel">
      <div class="filter-grid">
        <div class="filter-item">
          <label for="globalStrategySelect">当前策略</label>
          <select id="globalStrategySelect">
            ${strategyCatalog.map((strategy) => `
              <option value="${strategy.code}" ${strategy.code === activeStrategyCode ? "selected" : ""}>
                ${strategy.name}
              </option>
            `).join("")}
          </select>
        </div>
        <div class="filter-item">
          <label for="globalDaysSelect">观察窗口</label>
          <select id="globalDaysSelect">
            ${[7, 14, 30, 60].map((days) => `
              <option value="${days}" ${days === selectedDays ? "selected" : ""}>${days}天</option>
            `).join("")}
          </select>
        </div>
        <div class="filter-item filter-action">
          <span>当前说明</span>
          <strong>页面切换会保留策略与时间窗口参数</strong>
        </div>
      </div>
    </section>
  `;
}

function renderStrategySidebar(activePage, activeStrategyCode) {
  const strategyLinks = strategyCatalog.map((strategy) => `
    <a href="${strategy.placeholder ? "#" : dashboardPageHref("detail.html", strategy.code)}"
       class="sidebar-strategy ${strategy.code === activeStrategyCode ? "active" : ""} ${strategy.placeholder ? "disabled" : ""}">
      <span>${strategy.name}</span>
      <small>${strategy.status}</small>
    </a>
  `).join("");

  const pageLinks = `
    <a href="${dashboardPageHref("index.html", activeStrategyCode || "POLYMARKET_COPY")}" class="${activePage === "HOME" ? "active" : ""}">总览首页</a>
    <a href="${dashboardPageHref("detail.html", activeStrategyCode || "POLYMARKET_COPY")}" class="${activePage === "DETAIL" ? "active" : ""}">策略详情</a>
    <a href="${dashboardPageHref("charts.html", activeStrategyCode || "POLYMARKET_COPY")}" class="${activePage === "CHARTS" ? "active" : ""}">图表分析</a>
    <a href="/swagger-ui.html">Swagger 接口</a>
  `;

  return `
    <aside class="sidebar">
      <div class="sidebar-card brand">
        <div class="brand-mark">CS</div>
        <p class="eyebrow">Strategy Console</p>
        <h2>策略驾驶舱</h2>
        <p>统一查看策略持仓、收益、归因和风险容量，后续新策略可以直接沿用这套导航结构。</p>
      </div>
      <div class="sidebar-card">
        <h3>页面导航</h3>
        <nav class="sidebar-links">
          ${pageLinks}
        </nav>
      </div>
      <div class="sidebar-card">
        <h3>策略列表</h3>
        <div class="sidebar-strategies">
          ${strategyLinks}
        </div>
      </div>
      <div class="sidebar-card small">
        <h3>当前说明</h3>
        <p>当前只有 Polymarket 跟单策略接入实时数据，其余策略先保留结构，后续可逐步接入对冲、机器人和智能策略。</p>
      </div>
    </aside>
  `;
}

function renderAttributionBars(targetId, rows, emptyText) {
  const target = document.getElementById(targetId);
  if (!rows || rows.length === 0) {
    target.innerHTML = `<div class="empty-state">${emptyText}</div>`;
    return;
  }
  const maxAbs = Math.max(...rows.map(item => Math.abs(Number(item.netPnlUsdc || 0))), 1);
  target.innerHTML = rows.map((item) => {
    const net = Number(item.netPnlUsdc || 0);
    const width = Math.max((Math.abs(net) / maxAbs) * 100, 3);
    return `
      <div class="bar-row">
        <div class="bar-header">
          <span class="bar-label" title="${dashboardEscapeHtmlAttr(item.label || item.key || "-")}">${dashboardCompactLabel(item.label || item.key || "-", 28)}</span>
          <span class="${dashboardAmountClass(net)}">${dashboardFormatMoney(net)}</span>
        </div>
        <div class="bar-track">
          <div class="bar-fill ${dashboardAmountClass(net)}" style="width:${width}%"></div>
        </div>
        <div class="bar-meta">
          <span>订单 ${item.orderCount ?? 0}</span>
          <span>成交量 ${dashboardFormatMoney(item.tradedVolumeUsdc || 0)}</span>
        </div>
      </div>
    `;
  }).join("");
}

function parseStrategyCode() {
  const params = new URLSearchParams(window.location.search);
  return params.get("strategy") || "POLYMARKET_COPY";
}

function dashboardSelectedDays() {
  return parsePositiveIntParam("days", 30);
}

function dashboardPageHref(page, strategyCode) {
  const params = new URLSearchParams();
  params.set("strategy", strategyCode || parseStrategyCode());
  params.set("days", String(dashboardSelectedDays()));
  return `/dashboard/${page}?${params.toString()}`;
}

function attachGlobalFilters(containerId, currentPage) {
  const container = document.getElementById(containerId);
  if (!container) {
    return;
  }
  const strategySelect = container.querySelector("#globalStrategySelect");
  const daysSelect = container.querySelector("#globalDaysSelect");
  if (strategySelect) {
    strategySelect.addEventListener("change", () => {
      const strategy = strategySelect.value || parseStrategyCode();
      const days = daysSelect ? daysSelect.value : dashboardSelectedDays();
      window.location.href = `/dashboard/${currentPage}?strategy=${strategy}&days=${days}`;
    });
  }
  if (daysSelect) {
    daysSelect.addEventListener("change", () => {
      const strategy = strategySelect ? strategySelect.value : parseStrategyCode();
      const days = daysSelect.value || dashboardSelectedDays();
      window.location.href = `/dashboard/${currentPage}?strategy=${strategy}&days=${days}`;
    });
  }
}

function calculateHealthScore(detail) {
  const winRateScore = dashboardClamp(Number(detail.winRate || 0) * 100, 0, 100);
  const pnlScore = detail.realizedPnlUsdc > 0 ? 20 : detail.realizedPnlUsdc < 0 ? -20 : 0;
  const drawdownPenalty = dashboardClamp(Number(detail.maxDrawdownUsdc || 0) / 10, 0, 35);
  const conflictPenalty = Math.min(Number(detail.openConflictingMarkets || 0) * 6, 25);
  const capacityPenalty = Number(detail.remainingCapitalBudgetUsdc || 0) < 100 ? 10 : 0;
  const blockerPenalty = (detail.blockerStats || []).slice(0, 1).some(item => (item.blockedCount || 0) > 100) ? 8 : 0;
  const score = dashboardClamp(Math.round(55 + winRateScore * 0.35 + pnlScore - drawdownPenalty - conflictPenalty - capacityPenalty - blockerPenalty), 0, 100);
  return {
    score,
    level: score >= 75 ? "healthy" : score >= 50 ? "watch" : "risk",
    factors: [
      {
        label: "胜率贡献",
        value: Math.round(winRateScore * 0.35),
        kind: "positive",
        detail: `按已结算仓位胜率折算，当前为 ${dashboardFormatPercent(detail.winRate || 0)}。`
      },
      {
        label: "已结算收益贡献",
        value: pnlScore,
        kind: pnlScore >= 0 ? "positive" : "negative",
        detail: `当前已实现收益为 ${dashboardFormatMoney(detail.realizedPnlUsdc || 0)}，待结算收益为 ${dashboardFormatMoney(detail.pendingSettlementPnlUsdc || 0)}。`
      },
      {
        label: "回撤扣分",
        value: -Math.round(drawdownPenalty),
        kind: "negative",
        detail: `最大回撤为 ${dashboardFormatMoney(detail.maxDrawdownUsdc || 0)}。`
      },
      {
        label: "冲突市场扣分",
        value: -Math.round(conflictPenalty),
        kind: "negative",
        detail: `当前存在 ${detail.openConflictingMarkets || 0} 个 open 冲突市场。`
      },
      {
        label: "容量扣分",
        value: -Math.round(capacityPenalty),
        kind: "negative",
        detail: `剩余资金预算为 ${dashboardFormatMoney(detail.remainingCapitalBudgetUsdc || 0)}。`
      },
      {
        label: "阻塞扣分",
        value: -Math.round(blockerPenalty),
        kind: "negative",
        detail: "最近 blocker 统计中的高频阻塞项会继续压低健康度。"
      }
    ]
  };
}

function renderHealthBreakdown(targetId, health) {
  const target = document.getElementById(targetId);
  if (!target) {
    return;
  }
  target.innerHTML = `
    <div class="health-breakdown">
      ${(health.factors || []).map((factor) => `
        <article class="health-factor ${factor.kind}">
          <div class="health-factor-head">
            <strong>${factor.label}</strong>
            <span>${factor.value >= 0 ? "+" : ""}${factor.value}</span>
          </div>
          <p>${factor.detail}</p>
        </article>
      `).join("")}
    </div>
  `;
}

function renderAttributionSummaryCards(targetId, titlePrefix, rows, emptyText) {
  const target = document.getElementById(targetId);
  if (!target) {
    return;
  }
  if (!rows || rows.length === 0) {
    target.innerHTML = `<div class="empty-state">${emptyText}</div>`;
    return;
  }
  target.innerHTML = rows.slice(0, 3).map((item, index) => `
    <article class="attribution-card">
      <div class="attribution-rank">TOP ${index + 1}</div>
      <h3 title="${dashboardEscapeHtmlAttr(item.label || item.key || "-")}">${dashboardCompactLabel(item.label || item.key || "-", 30)}</h3>
      <div class="attribution-value ${dashboardAmountClass(item.netPnlUsdc)}">${dashboardFormatMoney(item.netPnlUsdc)}</div>
      <div class="attribution-meta">
        <span>${titlePrefix}订单 ${item.orderCount ?? 0}</span>
        <span>成交量 ${dashboardFormatMoney(item.tradedVolumeUsdc || 0)}</span>
      </div>
    </article>
  `).join("");
}

function renderRiskSignalCards(targetId, detail) {
  const target = document.getElementById(targetId);
  if (!target) {
    return;
  }
  const blockerStats = detail.blockerStats || [];
  const topBlockers = blockerStats.slice(0, 3);
  const cards = [];

  if ((detail.openConflictingMarkets || 0) > 0) {
    cards.push({
      level: "warn",
      title: "冲突市场未清空",
      message: `当前还有 ${detail.openConflictingMarkets} 个 open 冲突市场，说明同一市场多 outcome 的情况仍在影响策略收益。`
    });
  }
  if ((detail.remainingPositionSlots || 0) <= 2) {
    cards.push({
      level: "warn",
      title: "仓位容量接近上限",
      message: `当前剩余仓位槽位仅 ${detail.remainingPositionSlots} 个，建议关注结算释放节奏。`
    });
  }
  if ((detail.remainingCapitalBudgetUsdc || 0) < 100) {
    cards.push({
      level: "warn",
      title: "资金预算偏低",
      message: `当前剩余资金预算仅 ${dashboardFormatMoney(detail.remainingCapitalBudgetUsdc)}，容易导致高分信号继续被 CAPITAL_LIMIT 拦截。`
    });
  }
  topBlockers.forEach((item) => {
    cards.push({
      level: "info",
      title: `主要阻塞：${item.blockerCategory || "UNKNOWN"}`,
      message: `最近任务中有 ${item.blockedCount || 0} 条被该规则拦住，可用于判断下一步调参方向。`
    });
  });

  if (cards.length === 0) {
    target.innerHTML = `<div class="empty-state">当前没有明显的高优先级风险信号。</div>`;
    return;
  }

  target.innerHTML = cards.slice(0, 4).map((item) => `
    <article class="insight ${item.level}">
      <strong>${item.title}</strong>
      <div>${item.message}</div>
    </article>
  `).join("");
}
