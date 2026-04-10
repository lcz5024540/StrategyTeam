const state = {
  selected: "POLYMARKET_COPY",
  detail: null,
  positionMode: "PAPER",
  taskFilters: {
    positiveEvOnly: false,
    maxBlocker: null,
    minSampleSize: 0
  }
};

function renderOverview(summary) {
  document.getElementById("sidebarMount").innerHTML = renderStrategySidebar("HOME", state.selected);
  document.getElementById("homeFilters").innerHTML = renderGlobalFilterBar(state.selected);
  attachGlobalFilters("homeFilters", "index.html");
  document.getElementById("homeNav").innerHTML = `
    ${renderDashboardNav("HOME")}
    <div class="pill">Tomcat + Swagger</div>
    <div class="pill">Smart Wallet APIs</div>
    <div class="pill">${dashboardModeLabel(state.positionMode)}视图</div>
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
  const overviewStrategies = summary.strategyList || strategyCatalog.map((strategy) => ({
    code: strategy.code,
    name: strategy.name,
    category: strategy.category,
    status: strategy.status,
    description: strategy.description,
    openPositions: strategy.code === "POLYMARKET_COPY" ? summary.openPositions : 0,
    netPnlUsdc: strategy.code === "POLYMARKET_COPY" ? summary.netPnlUsdc : 0,
    compositePnlUsdc: strategy.code === "POLYMARKET_COPY" ? summary.compositePnlUsdc : 0,
    realizedPnlUsdc: strategy.code === "POLYMARKET_COPY" ? summary.realizedPnlUsdc : 0
  }));
  overviewStrategies.forEach((strategySummary) => {
    const card = document.createElement("article");
    card.className = `strategy-card ${strategySummary.code === state.selected ? "active" : ""}`;
    card.innerHTML = `
      <div class="strategy-meta">
        <span>${strategySummary.category}</span>
        <span class="strategy-status">${strategySummary.status}</span>
      </div>
      <h3>${strategySummary.name}</h3>
      <p>${strategySummary.description}</p>
      <div class="strategy-meta">
        <span>持仓 ${strategySummary?.openPositions ?? 0}</span>
        <span class="${dashboardAmountClass(strategySummary?.realizedPnlUsdc)}">${dashboardFormatMoney(strategySummary?.realizedPnlUsdc)}</span>
      </div>
    `;
    card.addEventListener("click", async () => {
      state.selected = strategySummary.code;
      const params = new URLSearchParams(window.location.search);
      params.set("strategy", strategySummary.code);
      params.set("days", String(dashboardSelectedDays()));
      params.set("mode", state.positionMode);
      window.history.replaceState({}, "", `${window.location.pathname}?${params.toString()}`);
      renderOverview(summary);
      await loadSelected();
    });
    cards.appendChild(card);
  });

  renderAttributionSummaryCards("homeWalletAttribution", "钱包", summary.walletAttribution || [], "当前窗口没有钱包归因数据。");
  renderAttributionSummaryCards("homeMarketAttribution", "市场", summary.marketAttribution || [], "当前窗口没有市场归因数据。");
  renderRiskSignalCards("riskSignals", summary);
  document.getElementById("homeTaskFilters").innerHTML = dashboardBuildTaskFilterBar("homeTask", state.taskFilters);
  dashboardAttachTaskFilters("homeTask", () => {
    state.taskFilters = dashboardReadTaskFilters("homeTask");
    renderDetail(state.detail);
  });
}

function renderMetrics(detail) {
  document.getElementById("strategyTitle").textContent = detail.name;
  document.getElementById("strategyDesc").textContent = detail.description;

  const cards = document.getElementById("metricCards");
  const metrics = [
    ["综合收益", dashboardFormatMoney(detail.compositePnlUsdc), "已实现净收益 + 待结算收益"],
    ["已结算净收益", dashboardFormatMoney(detail.netPnlUsdc), "已实现收益 - 手续费，不含待结算部分"],
    ["已实现收益", dashboardFormatMoney(detail.realizedPnlUsdc), "真正结算落袋的收益"],
    ["待结算收益", dashboardFormatMoney(detail.pendingSettlementPnlUsdc), "市场已持仓但尚未个人结算的中间态收益"],
    ["胜率", dashboardFormatPercent(detail.winRate), "基于已关闭仓位统计"],
    ["订单数", String(detail.totalOrders || 0), "累计订单总数"],
    ["Open 仓位", String(detail.openPositions || 0), "当前仍在持有的仓位数"],
    ["回撤", dashboardFormatMoney(detail.maxDrawdownUsdc), "策略历史最大回撤"],
    ["冲突市场", String(detail.openConflictingMarkets || 0), "同市场多 outcome 持仓"],
    ["剩余仓位槽位", String(detail.remainingPositionSlots || 0), "组合还能容纳的新仓位数量"],
    ["可用资金", dashboardFormatMoney(detail.availableCapitalUsdc), "当前还能继续投入的新资金"],
    ["待领取锁定", dashboardFormatMoney(detail.lockedClaimProceedsUsdc), "真实单已结算但未 claim 的锁定金额"],
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
  if ((detail.stalePaperSettlementReview?.staleCount || 0) > 0) {
    insights.push({
      level: "warn",
      title: "历史模拟仓位待复核",
      message: `检测到 ${detail.stalePaperSettlementReview.staleCount} 笔 paper-book 仓位已过结算时间但状态未更新，建议执行 /smart-wallet/settlement/review-paper-history。`
    });
  }
  if ((detail.nextSettlements || []).length > 0) {
    insights.push({ level: "info", title: "待结算处理", message: `${detail.nextSettlements[0].question}，预计北京时间 ${detail.nextSettlements[0].marketEndAtBjt}。` });
  } else {
    insights.push({ level: "info", title: "暂无待结算处理项", message: "当前没有已到结算窗口但仍需处理的仓位。" });
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
      <td>${dashboardTitleCell(row.question, 40)}</td>
      <td><span class="badge">${row.outcome}</span></td>
      <td>${dashboardPositionStatusText(row.status)}</td>
      <td>${dashboardFormatMoney(row.costBasisUsdc)}</td>
      <td class="${dashboardAmountClass(row.pendingSettlementPnlUsdc ?? row.unrealizedPnlUsdc)}">${dashboardFormatMoney(row.pendingSettlementPnlUsdc ?? row.unrealizedPnlUsdc)}</td>
      <td>${row.marketEndAtBjt || "-"}</td>
    </tr>
  `, "当前没有 open positions。", 6);

  renderTableRows("settlementsBody", detail.nextSettlements, (row) => `
    <tr>
      <td>${dashboardTitleCell(row.question, 40)}</td>
      <td><span class="badge">${row.outcome}</span></td>
      <td>${dashboardFormatMoney(row.costBasisUsdc)}</td>
      <td class="${dashboardAmountClass(row.pendingSettlementPnlUsdc ?? row.unrealizedPnlUsdc)}">${dashboardFormatMoney(row.pendingSettlementPnlUsdc ?? row.unrealizedPnlUsdc)}</td>
      <td>${row.marketEndAtBjt || "-"}</td>
    </tr>
  `, "当前没有待结算仓位。", 5);

  renderTableRows("ordersBody", detail.recentOrders, (row) => `
    <tr>
      <td>${dashboardAddressCell(row.id, 6, 4)}</td>
      <td>${dashboardTitleCell(row.question, 40)}</td>
      <td><span class="badge">${row.outcome}</span></td>
      <td>${row.side}</td>
      <td>${dashboardFormatMoney(row.notionalUsdc)}</td>
      <td class="${dashboardAmountClass(row.expectedNetEvUsdc)}">${row.expectedNetEvUsdc == null ? "-" : dashboardFormatMoney(row.expectedNetEvUsdc)}</td>
      <td>${row.status}</td>
      <td>${dashboardOrderSettlementText(row)}</td>
      <td>${row.placedAt || "-"}</td>
    </tr>
  `, "当前没有订单记录。", 9);

  renderTableRows("conflictsBody", detail.conflicts, (row) => `
    <tr>
      <td>${dashboardTitleCell(row.question, 40)}</td>
      <td>${(row.outcomes || []).join(" / ")}</td>
      <td>${row.openPositionCount}</td>
      <td class="${dashboardAmountClass(row.netPnlUsdc)}">${dashboardFormatMoney(row.netPnlUsdc)}</td>
      <td>${row.marketEndAtBjt || "-"}</td>
    </tr>
  `, "当前没有冲突市场。", 5);

  const executableTasks = dashboardFilterTasks(detail.executableTasks, state.taskFilters);
  const opportunityTasks = dashboardFilterTasks(detail.opportunityTasks, state.taskFilters);

  renderTableRows("executableTasksBody", executableTasks, (row) => `
    <tr>
      <td>${dashboardTitleCell(row.question, 40)}</td>
      <td><span class="badge">${row.outcome}</span></td>
      <td>${dashboardFormatMoney(row.recommendedStakeUsdc)}</td>
      <td>${dashboardTaskEvCell(row)}</td>
      <td>${dashboardTaskSampleCell(row)}</td>
      <td>${dashboardTaskStatusBadge(row)}</td>
    </tr>
  `, "当前没有通过风控的可执行任务。", 6);

  renderTableRows("opportunityTasksBody", opportunityTasks, (row) => `
    <tr>
      <td>${dashboardTitleCell(row.question, 40)}</td>
      <td><span class="badge">${row.outcome}</span></td>
      <td>${dashboardFormatMoney(row.recommendedStakeUsdc)}</td>
      <td>${dashboardTaskEvCell(row)}</td>
      <td>${row.blockerCount ?? 0}</td>
      <td><span class="cell-truncate" title="${dashboardEscapeHtmlAttr((row.riskReasons || []).join(" | "))}">${dashboardTaskBlockerText(row)}</span></td>
    </tr>
  `, "当前没有接近可执行的机会任务。", 6);
}

async function loadSelected() {
  const days = dashboardSelectedDays();
  state.positionMode = dashboardSelectedMode();
  state.detail = await loadStrategyDashboardDetail(state.selected, { days, mode: state.positionMode });
  renderDetail(state.detail);
}

async function init() {
  try {
    state.selected = parseStrategyCode();
    state.positionMode = dashboardSelectedMode();
    const days = dashboardSelectedDays();
    const [overview, summary] = await Promise.all([
      loadStrategyDashboardOverviewWithMode(state.positionMode),
      loadStrategyDashboardDetail(state.selected, { days, mode: state.positionMode })
    ]);
    summary.strategyList = overview?.strategies || [];
    state.detail = summary;
    renderOverview(summary);
    renderDetail(summary);
  } catch (error) {
    document.body.innerHTML = `<div class="page"><section class="panel"><h1>页面加载失败</h1><p>${error.message}</p></section></div>`;
  }
}

init();
