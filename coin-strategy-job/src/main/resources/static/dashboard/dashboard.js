const state = {
  selected: "POLYMARKET_COPY",
  detail: null,
};

function renderOverview(summary) {
  document.getElementById("sidebarMount").innerHTML = renderStrategySidebar("HOME", state.selected);
  document.getElementById("homeFilters").innerHTML = renderGlobalFilterBar(state.selected);
  attachGlobalFilters("homeFilters", "index.html");
  document.getElementById("homeNav").innerHTML = `
    ${renderDashboardNav("HOME")}
    <div class="pill">Tomcat + Swagger</div>
    <div class="pill">Smart Wallet APIs</div>
    <div class="pill">${dashboardGeneratedAtText()}</div>
  `;
  const health = calculateHealthScore(summary);
  document.getElementById("healthScoreCard").innerHTML = `
    <div class="health-card ${health.level}">
      <span>策略健康度</span>
      <strong>${health.score}</strong>
    </div>
  `;
  renderHealthBreakdown("healthBreakdown", health);
  document.getElementById("homeWindowSwitcher").innerHTML = buildWindowSwitcher(summary.selectedDays || 30, state.selected);
  const cards = document.getElementById("strategyCards");
  cards.innerHTML = "";
  strategyCatalog.forEach((strategy) => {
    const strategySummary = strategy.code === "POLYMARKET_COPY" ? summary : null;
    const card = document.createElement("article");
    card.className = `strategy-card ${strategy.code === state.selected ? "active" : ""}`;
    card.innerHTML = `
      <div class="strategy-meta">
        <span>${strategy.category}</span>
        <span class="strategy-status">${strategy.status}</span>
      </div>
      <h3>${strategy.name}</h3>
      <p>${strategy.description}</p>
      <div class="strategy-meta">
        <span>持仓 ${strategySummary?.openPositions ?? 0}</span>
        <span class="${dashboardAmountClass(strategySummary?.netPnlUsdc)}">${dashboardFormatMoney(strategySummary?.netPnlUsdc)}</span>
      </div>
    `;
    card.addEventListener("click", async () => {
      state.selected = strategy.code;
      const params = new URLSearchParams(window.location.search);
      params.set("strategy", strategy.code);
      params.set("days", String(dashboardSelectedDays()));
      window.history.replaceState({}, "", `${window.location.pathname}?${params.toString()}`);
      renderOverview(summary);
      await loadSelected();
    });
    cards.appendChild(card);
  });

  renderAttributionSummaryCards("homeWalletAttribution", "钱包", summary.walletAttribution || [], "当前窗口没有钱包归因数据。");
  renderAttributionSummaryCards("homeMarketAttribution", "市场", summary.marketAttribution || [], "当前窗口没有市场归因数据。");
  renderRiskSignalCards("riskSignals", summary);
}

function renderMetrics(detail) {
  document.getElementById("strategyTitle").textContent = detail.name;
  document.getElementById("strategyDesc").textContent = detail.description;

  const cards = document.getElementById("metricCards");
  const metrics = [
    ["净收益", dashboardFormatMoney(detail.netPnlUsdc), "综合已实现与未实现盈亏"],
    ["已实现收益", dashboardFormatMoney(detail.realizedPnlUsdc), "真正结算落袋的收益"],
    ["未实现收益", dashboardFormatMoney(detail.unrealizedPnlUsdc), "当前 open positions 的浮盈亏"],
    ["胜率", dashboardFormatPercent(detail.winRate), "基于已关闭仓位统计"],
    ["订单数", String(detail.totalOrders || 0), "累计订单总数"],
    ["Open 仓位", String(detail.openPositions || 0), "当前仍在持有的仓位数"],
    ["回撤", dashboardFormatMoney(detail.maxDrawdownUsdc), "策略历史最大回撤"],
    ["冲突市场", String(detail.openConflictingMarkets || 0), "同市场多 outcome 持仓"],
    ["剩余仓位槽位", String(detail.remainingPositionSlots || 0), "组合还能容纳的新仓位数量"],
    ["剩余资金预算", dashboardFormatMoney(detail.remainingCapitalBudgetUsdc), "还能继续投入的策略预算"]
  ];
  cards.innerHTML = metrics.map(([label, value, hint]) => `
    <article class="metric-card">
      <div class="label">${label}</div>
      <div class="value ${label.includes("收益") || label.includes("回撤") ? dashboardAmountClass(value.replace(/[+$,]/g, "")) : ""}">${value}</div>
      <div class="hint">${hint}</div>
    </article>
  `).join("");
}

function renderInsights(detail) {
  const insights = [];
  if (detail.remainingCapitalBudgetUsdc < 100) {
    insights.push({ level: "warn", title: "资金预算偏紧", message: "剩余策略预算较低，建议优先等待结算或减少新开仓。" });
  }
  if (detail.openConflictingMarkets > 0) {
    insights.push({ level: "warn", title: "存在冲突市场", message: "同一市场持有多个 outcome，会显著拖低胜率与资金效率。" });
  }
  if (detail.maxDrawdownUsdc > 300) {
    insights.push({ level: "warn", title: "回撤偏高", message: "当前回撤较大，建议收紧信号阈值或减少最大开仓数。" });
  }
  if ((detail.nextSettlements || []).length > 0) {
    insights.push({ level: "info", title: "最近待结算市场", message: `${detail.nextSettlements[0].question}，预计北京时间 ${detail.nextSettlements[0].marketEndAtBjt}。` });
  } else {
    insights.push({ level: "info", title: "暂无待结算仓位", message: "当前没有未结算仓位，适合重新观察新策略机会。" });
  }
  if (detail.closedPositions === 0) {
    insights.push({ level: "info", title: "当前胜率尚未成型", message: "暂时没有已结算仓位，胜率需要等待真实结算后再判断。" });
  }

  const container = document.getElementById("insights");
  container.innerHTML = insights.map((item) => `
    <article class="insight ${item.level}">
      <strong>${item.title}</strong>
      <div>${item.message}</div>
    </article>
  `).join("");
}

function renderTableRows(targetId, rows, mapper, emptyText, colspan) {
  const tbody = document.getElementById(targetId);
  if (!rows || rows.length === 0) {
    tbody.innerHTML = `<tr><td colspan="${colspan}" class="empty">${emptyText}</td></tr>`;
    return;
  }
  tbody.innerHTML = rows.map(mapper).join("");
}

function renderTimeline(detail) {
  const svg = document.getElementById("timelineChart");
  const empty = document.getElementById("chartEmpty");
  const points = detail.timeline || [];
  if (points.length < 2) {
    svg.innerHTML = "";
    empty.style.display = "flex";
    return;
  }
  empty.style.display = "none";

  const width = 700;
  const height = 260;
  const padding = 24;
  const values = points.map(item => Number(item.cumulativeNetPnlUsdc || 0));
  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = Math.max(max - min, 1);
  const coords = points.map((item, index) => {
    const x = padding + (index * (width - padding * 2)) / (points.length - 1);
    const y = height - padding - ((Number(item.cumulativeNetPnlUsdc || 0) - min) * (height - padding * 2)) / range;
    return `${x},${y}`;
  }).join(" ");

  const coordsArr = coords.split(" ");
  svg.innerHTML = `
    <line x1="${padding}" y1="${height - padding}" x2="${width - padding}" y2="${height - padding}" stroke="rgba(23,33,23,0.15)" />
    <line x1="${padding}" y1="${padding}" x2="${padding}" y2="${height - padding}" stroke="rgba(23,33,23,0.15)" />
    <polyline fill="none" stroke="#1f7a4d" stroke-width="3" points="${coords}" />
    ${points.map((item, index) => {
      const [x, y] = coordsArr[index].split(",");
      return `<circle cx="${x}" cy="${y}" r="4" fill="#1f7a4d"><title>${item.bucketAt}: ${item.cumulativeNetPnlUsdc}</title></circle>`;
    }).join("")}
  `;
}

function renderDetail(detail) {
  renderMetrics(detail);
  renderInsights(detail);
  renderTimeline(detail);

  renderTableRows("positionsBody", detail.positions, (row) => `
    <tr>
      <td>${row.question}</td>
      <td><span class="badge">${row.outcome}</span></td>
      <td>${row.status}</td>
      <td>${dashboardFormatMoney(row.costBasisUsdc)}</td>
      <td class="${dashboardAmountClass(row.unrealizedPnlUsdc)}">${dashboardFormatMoney(row.unrealizedPnlUsdc)}</td>
      <td>${row.marketEndAtBjt || "-"}</td>
    </tr>
  `, "当前没有 open positions。", 6);

  renderTableRows("settlementsBody", detail.nextSettlements, (row) => `
    <tr>
      <td>${row.question}</td>
      <td><span class="badge">${row.outcome}</span></td>
      <td>${dashboardFormatMoney(row.costBasisUsdc)}</td>
      <td class="${dashboardAmountClass(row.unrealizedPnlUsdc)}">${dashboardFormatMoney(row.unrealizedPnlUsdc)}</td>
      <td>${row.marketEndAtBjt || "-"}</td>
    </tr>
  `, "当前没有待结算仓位。", 5);

  renderTableRows("ordersBody", detail.recentOrders, (row) => `
    <tr>
      <td>${row.id}</td>
      <td>${row.question}</td>
      <td><span class="badge">${row.outcome}</span></td>
      <td>${row.side}</td>
      <td>${dashboardFormatMoney(row.notionalUsdc)}</td>
      <td>${row.status}</td>
      <td>${row.placedAt || "-"}</td>
    </tr>
  `, "当前没有订单记录。", 7);

  renderTableRows("conflictsBody", detail.conflicts, (row) => `
    <tr>
      <td>${row.question}</td>
      <td>${(row.outcomes || []).join(" / ")}</td>
      <td>${row.openPositionCount}</td>
      <td class="${dashboardAmountClass(row.netPnlUsdc)}">${dashboardFormatMoney(row.netPnlUsdc)}</td>
      <td>${row.marketEndAtBjt || "-"}</td>
    </tr>
  `, "当前没有冲突市场。", 5);
}

async function loadSelected() {
  const days = dashboardSelectedDays();
  state.detail = await loadStrategyDashboardDetail(state.selected, { days });
  renderDetail(state.detail);
}

async function init() {
  try {
    state.selected = parseStrategyCode();
    const days = dashboardSelectedDays();
    const summary = await loadStrategyDashboardDetail(state.selected, { days });
    renderOverview(summary);
    renderDetail(summary);
  } catch (error) {
    document.body.innerHTML = `<div class="page"><section class="panel"><h1>页面加载失败</h1><p>${error.message}</p></section></div>`;
  }
}

init();
