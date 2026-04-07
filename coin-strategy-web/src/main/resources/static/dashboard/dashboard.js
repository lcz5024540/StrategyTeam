const state = {
  selected: "POLYMARKET_COPY",
  overview: null,
  detail: null,
};

async function getJson(url) {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`Request failed: ${url} status=${response.status}`);
  }
  return response.json();
}

function formatMoney(value) {
  const num = Number(value || 0);
  return `${num >= 0 ? "+" : "-"}$${Math.abs(num).toFixed(2)}`;
}

function formatPercent(value) {
  const num = Number(value || 0) * 100;
  return `${num.toFixed(2)}%`;
}

function amountClass(value) {
  return Number(value || 0) >= 0 ? "positive" : "negative";
}

function renderOverview() {
  const generatedAt = document.getElementById("generatedAt");
  generatedAt.textContent = `数据生成时间：${state.overview.generatedAtBjt}`;

  const cards = document.getElementById("strategyCards");
  cards.innerHTML = "";
  state.overview.strategies.forEach((strategy) => {
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
        <span>持仓 ${strategy.openPositions ?? 0}</span>
        <span class="${amountClass(strategy.netPnlUsdc)}">${formatMoney(strategy.netPnlUsdc)}</span>
      </div>
    `;
    card.addEventListener("click", async () => {
      state.selected = strategy.code;
      renderOverview();
      await loadDetail(strategy.code);
    });
    cards.appendChild(card);
  });
}

function renderMetrics(detail) {
  document.getElementById("strategyTitle").textContent = detail.summary.name;
  document.getElementById("strategyDesc").textContent = detail.summary.description;

  const cards = document.getElementById("metricCards");
  const metrics = [
    ["净收益", formatMoney(detail.metrics.netPnlUsdc), "综合已实现与未实现盈亏"],
    ["已实现收益", formatMoney(detail.metrics.realizedPnlUsdc), "真正结算落袋的收益"],
    ["未实现收益", formatMoney(detail.metrics.unrealizedPnlUsdc), "当前 open positions 的浮盈亏"],
    ["胜率", formatPercent(detail.metrics.winRate), "基于已关闭仓位统计"],
    ["订单数", String(detail.metrics.totalOrders || 0), "累计订单总数"],
    ["Open 仓位", String(detail.metrics.openPositions || 0), "当前仍在持有的仓位数"],
    ["回撤", formatMoney(detail.metrics.maxDrawdownUsdc), "策略历史最大回撤"],
    ["冲突市场", String(detail.metrics.openConflictingMarkets || 0), "同市场多 outcome 持仓"],
    ["剩余仓位槽位", String(detail.metrics.remainingPositionSlots || 0), "组合还能容纳的新仓位数量"],
    ["剩余资金预算", formatMoney(detail.metrics.remainingCapitalBudgetUsdc), "还能继续投入的策略预算"],
  ];
  cards.innerHTML = metrics.map(([label, value, hint]) => `
    <article class="metric-card">
      <div class="label">${label}</div>
      <div class="value ${label.includes("收益") || label.includes("回撤") ? amountClass(value.replace(/[+$,]/g, "")) : ""}">${value}</div>
      <div class="hint">${hint}</div>
    </article>
  `).join("");
}

function renderInsights(detail) {
  const container = document.getElementById("insights");
  container.innerHTML = detail.insights.map((item) => `
    <article class="insight ${String(item.level || "").toLowerCase()}">
      <strong>${item.title}</strong>
      <div>${item.message}</div>
    </article>
  `).join("");
}

function renderTableRows(targetId, rows, mapper, emptyText) {
  const tbody = document.getElementById(targetId);
  if (!rows || rows.length === 0) {
    tbody.innerHTML = `<tr><td colspan="8" class="empty">${emptyText}</td></tr>`;
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
    empty.style.display = "block";
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

  svg.innerHTML = `
    <line x1="${padding}" y1="${height - padding}" x2="${width - padding}" y2="${height - padding}" stroke="rgba(23,33,23,0.15)" />
    <line x1="${padding}" y1="${padding}" x2="${padding}" y2="${height - padding}" stroke="rgba(23,33,23,0.15)" />
    <polyline fill="none" stroke="#1f7a4d" stroke-width="3" points="${coords}" />
    ${points.map((item, index) => {
      const [x, y] = coords.split(" ")[index].split(",");
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
      <td>${formatMoney(row.costBasisUsdc)}</td>
      <td class="${amountClass(row.unrealizedPnlUsdc)}">${formatMoney(row.unrealizedPnlUsdc)}</td>
      <td>${row.marketEndAtBjt || "-"}</td>
    </tr>
  `, "当前没有 open positions。");

  renderTableRows("settlementsBody", detail.nextSettlements, (row) => `
    <tr>
      <td>${row.question}</td>
      <td><span class="badge">${row.outcome}</span></td>
      <td>${formatMoney(row.costBasisUsdc)}</td>
      <td class="${amountClass(row.unrealizedPnlUsdc)}">${formatMoney(row.unrealizedPnlUsdc)}</td>
      <td>${row.marketEndAtBjt || "-"}</td>
    </tr>
  `, "当前没有待结算仓位。");

  renderTableRows("ordersBody", detail.recentOrders, (row) => `
    <tr>
      <td>${row.id}</td>
      <td>${row.question}</td>
      <td><span class="badge">${row.outcome}</span></td>
      <td>${row.side}</td>
      <td>${formatMoney(row.notionalUsdc)}</td>
      <td>${row.status}</td>
      <td>${row.placedAt || "-"}</td>
    </tr>
  `, "当前没有订单记录。");

  renderTableRows("conflictsBody", detail.conflicts, (row) => `
    <tr>
      <td>${row.question}</td>
      <td>${(row.outcomes || []).join(" / ")}</td>
      <td>${row.openPositionCount}</td>
      <td class="${amountClass(row.netPnlUsdc)}">${formatMoney(row.netPnlUsdc)}</td>
      <td>${row.marketEndAtBjt || "-"}</td>
    </tr>
  `, "当前没有冲突市场。");
}

async function loadOverview() {
  state.overview = await getJson("/dashboard/api/overview");
  renderOverview();
}

async function loadDetail(code) {
  state.detail = await getJson(`/dashboard/api/strategies/${code}`);
  renderDetail(state.detail);
}

async function init() {
  try {
    await loadOverview();
    await loadDetail(state.selected);
  } catch (error) {
    document.body.innerHTML = `<div class="page"><section class="panel"><h1>页面加载失败</h1><p>${error.message}</p></section></div>`;
  }
}

init();
