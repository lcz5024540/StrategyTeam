const state = {
  selected: "POLYMARKET_COPY",
  detail: null,
  baselineModelConfig: null,
  baselineModelStatus: "",
  baselineModelStatusLevel: "neutral",
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
  renderBaselineModelPanel();
}

function renderMetrics(detail) {
  document.getElementById("strategyTitle").textContent = detail.name;
  document.getElementById("strategyDesc").textContent = detail.description;

  const cards = document.getElementById("metricCards");
  const metrics = [
    ["综合收益", dashboardFormatMoney(detail.compositePnlUsdc), "已实现净收益 + 净待结算收益"],
    ["已结算净收益", dashboardFormatMoney(detail.netPnlUsdc), "已实现收益 - 手续费，不含待结算部分"],
    ["已实现收益", dashboardFormatMoney(detail.realizedPnlUsdc), "真正结算落袋的收益"],
    ["净待结算收益", dashboardFormatMoney(detail.pendingSettlementPnlUsdc), "待结算仓位按净收益口径展示，不再把回款总额当收益"],
    ["胜率", dashboardFormatPercent(detail.winRate), "基于已关闭仓位统计"],
    ["订单数", String(detail.totalOrders || 0), "累计订单总数"],
    ["Open 仓位", String(detail.openPositions || 0), "当前仍在持有的仓位数"],
    ["回撤", dashboardFormatMoney(detail.maxDrawdownUsdc), "策略历史最大回撤"],
    ["冲突市场", String(detail.openConflictingMarkets || 0), "同市场多 outcome 持仓"],
    ["剩余仓位槽位", String(detail.remainingPositionSlots || 0), "组合还能容纳的新仓位数量"],
    ["可用资金", dashboardFormatMoney(detail.availableCapitalUsdc), "当前还能继续投入的新资金"],
    ["可回款资金", dashboardFormatMoney(detail.lockedClaimProceedsUsdc), "已结算但尚未 claim 的回款总额"],
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

  renderTableRows("positionsBody", detail.positions, (row) => {
    const cost = Number(row.costBasisUsdc || 0);
    const pnl = Number(row.pendingSettlementPnlUsdc ?? row.unrealizedPnlUsdc ?? 0);
    const marketValue = cost + pnl;
    return `
    <tr>
      <td>${dashboardTitleCell(row.question, 40)}</td>
      <td><span class="badge">${row.outcome}</span></td>
      <td>${dashboardPositionStatusText(row.status)}</td>
      <td>${dashboardFormatAmount(row.costBasisUsdc)}</td>
      <td>${dashboardFormatAmount(marketValue)}</td>
      <td class="${dashboardAmountClass(pnl)}">${dashboardFormatMoney(pnl)}</td>
      <td>${row.marketEndAtBjt || "-"}</td>
    </tr>
  `;}, "当前没有 open positions。", 7);

  renderTableRows("settlementsBody", detail.nextSettlements, (row) => `
    <tr>
      <td>${dashboardTitleCell(row.question, 40)}</td>
      <td><span class="badge">${row.outcome}</span></td>
      <td>${dashboardFormatAmount(row.originalCostBasisUsdc ?? row.costBasisUsdc)}</td>
      <td class="${dashboardAmountClass(row.pendingSettlementNetPnlUsdc ?? row.pendingSettlementPnlUsdc ?? row.unrealizedPnlUsdc)}">${dashboardFormatMoney(row.pendingSettlementNetPnlUsdc ?? row.pendingSettlementPnlUsdc ?? row.unrealizedPnlUsdc)}</td>
      <td>${dashboardFormatAmount(row.claimableProceedsUsdc || 0)}</td>
      <td>${row.marketEndAtBjt || "-"}</td>
    </tr>
  `, "当前没有待结算仓位。", 6);

  renderTableRows("ordersBody", detail.recentOrders, (row) => `
    <tr>
      <td>${dashboardAddressCell(row.id, 6, 4)}</td>
      <td>${dashboardTitleCell(row.question, 40)}</td>
      <td><span class="badge">${row.outcome}</span></td>
      <td>${row.side}</td>
      <td>${dashboardFormatAmount(row.notionalUsdc)}</td>
      <td class="${dashboardAmountClass(row.expectedNetEvUsdc)}">${row.expectedNetEvUsdc == null ? "-" : dashboardFormatMoney(row.expectedNetEvUsdc)}</td>
      <td>${dashboardOrderModelCell(row)}</td>
      <td>${row.status}</td>
      <td>${dashboardOrderSettlementText(row)}</td>
      <td>${row.placedAt || "-"}</td>
    </tr>
  `, "当前没有订单记录。", 10);

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
      <td>${dashboardTaskModelCell(row)}</td>
      <td>${dashboardTaskSampleCell(row)}</td>
      <td>${dashboardTaskStatusBadge(row)}</td>
    </tr>
  `, "当前没有通过风控的可执行任务。", 7);

  renderTableRows("opportunityTasksBody", opportunityTasks, (row) => `
    <tr>
      <td>${dashboardTitleCell(row.question, 40)}</td>
      <td><span class="badge">${row.outcome}</span></td>
      <td>${dashboardFormatMoney(row.recommendedStakeUsdc)}</td>
      <td>${dashboardTaskEvCell(row)}</td>
      <td>${dashboardTaskModelCell(row)}</td>
      <td>${row.blockerCount ?? 0}</td>
      <td><span class="cell-truncate" title="${dashboardEscapeHtmlAttr((row.riskReasons || []).join(" | "))}">${dashboardTaskBlockerText(row)}</span></td>
    </tr>
  `, "当前没有接近可执行的机会任务。", 7);
}

function baselineField(label, id, value) {
  return `
    <div class="model-config-field">
      <label for="${id}">${label}</label>
      <input id="${id}" type="number" step="0.0001" value="${value ?? ""}">
    </div>
  `;
}

function renderBaselineModelPanel() {
  const mount = document.getElementById("baselineModelPanel");
  if (!mount) {
    return;
  }
  const config = state.baselineModelConfig;
  if (!config) {
    mount.innerHTML = `<div class="empty-state">当前没有可用的 baseline 模型配置。</div>`;
    return;
  }
  const statusClass = state.baselineModelStatusLevel === "error"
    ? "model-config-status error"
    : state.baselineModelStatusLevel === "success"
      ? "model-config-status success"
      : "model-config-status";
  mount.innerHTML = `
    <div class="model-config-grid">
      <article class="model-config-card">
        <h3>Follow Probability</h3>
        <p>调节跟单概率基线和主要正负因子权重。</p>
        <div class="model-config-fields">
          ${baselineField("Intercept", "baselineFollowIntercept", config.followProbability?.intercept)}
          ${baselineField("Wallet Score", "baselineFollowWalletScoreWeight", config.followProbability?.walletScoreWeight)}
          ${baselineField("Consensus Score", "baselineFollowConsensusScoreWeight", config.followProbability?.consensusScoreWeight)}
          ${baselineField("Positive Rate", "baselineFollowPositiveRateWeight", config.followProbability?.positiveRateWeight)}
          ${baselineField("Trigger Size", "baselineFollowTriggerSizeWeight", config.followProbability?.triggerSizeWeight)}
          ${baselineField("Distinct Wallet", "baselineFollowDistinctWalletWeight", config.followProbability?.distinctWalletWeight)}
          ${baselineField("Imbalance", "baselineFollowImbalanceWeight", config.followProbability?.imbalanceWeight)}
          ${baselineField("Spread Penalty", "baselineFollowSpreadPenaltyWeight", config.followProbability?.spreadPenaltyWeight)}
          ${baselineField("Vol Penalty", "baselineFollowVolatilityPenaltyWeight", config.followProbability?.volatilityPenaltyWeight)}
          ${baselineField("Trigger Size Cap", "baselineFollowTriggerSizeCapUsdc", config.followProbability?.triggerSizeCapUsdc)}
          ${baselineField("Distinct Wallet Cap", "baselineFollowDistinctWalletCap", config.followProbability?.distinctWalletCap)}
          ${baselineField("Spread Scale (bps)", "baselineFollowSpreadPenaltyScaleBps", config.followProbability?.spreadPenaltyScaleBps)}
          ${baselineField("Vol Scale", "baselineFollowVolatilityPenaltyScale", config.followProbability?.volatilityPenaltyScale)}
        </div>
      </article>
      <article class="model-config-card">
        <h3>Slippage</h3>
        <p>控制价差、冲击和波动对滑点成本的贡献。</p>
        <div class="model-config-fields">
          ${baselineField("Gross Edge Prior", "baselineGrossEdgePriorWeight", config.grossEdgePriorWeight)}
          ${baselineField("Spread Cost", "baselineSlippageSpreadCostWeight", config.slippage?.spreadCostWeight)}
          ${baselineField("Impact Cost", "baselineSlippageImpactCostWeight", config.slippage?.impactCostWeight)}
          ${baselineField("Vol Cost", "baselineSlippageVolatilityCostWeight", config.slippage?.volatilityCostWeight)}
        </div>
      </article>
      <article class="model-config-card">
        <h3>Slippage Spike</h3>
        <p>控制滑点尖峰风险的触发阈值和特征权重。</p>
        <div class="model-config-fields">
          ${baselineField("Intercept", "baselineSpikeIntercept", config.slippageSpike?.intercept)}
          ${baselineField("Spread Risk", "baselineSpikeSpreadRiskWeight", config.slippageSpike?.spreadRiskWeight)}
          ${baselineField("Vol Risk", "baselineSpikeVolatilityRiskWeight", config.slippageSpike?.volatilityRiskWeight)}
          ${baselineField("Size/Depth Risk", "baselineSpikeSizeDepthRiskWeight", config.slippageSpike?.sizeDepthRiskWeight)}
          ${baselineField("Hist Spike", "baselineSpikeHistoricalSpikeWeight", config.slippageSpike?.historicalSpikeWeight)}
          ${baselineField("Spread Scale (bps)", "baselineSpikeSpreadRiskScaleBps", config.slippageSpike?.spreadRiskScaleBps)}
          ${baselineField("Vol Scale", "baselineSpikeVolatilityRiskScale", config.slippageSpike?.volatilityRiskScale)}
          ${baselineField("Spike Threshold (bps)", "baselineSpikeThresholdBps", config.slippageSpike?.spikeThresholdBps)}
        </div>
      </article>
      <article class="model-config-card">
        <h3>Calibration</h3>
        <p>对 baseline 输出做 Platt 风格校准。</p>
        <div class="model-config-fields">
          ${baselineField("Follow Scale", "baselineCalibrationFollowScale", config.calibration?.followProbabilityScale)}
          ${baselineField("Follow Bias", "baselineCalibrationFollowBias", config.calibration?.followProbabilityBias)}
          ${baselineField("Spike Scale", "baselineCalibrationSpikeScale", config.calibration?.slippageSpikeScale)}
          ${baselineField("Spike Bias", "baselineCalibrationSpikeBias", config.calibration?.slippageSpikeBias)}
        </div>
      </article>
    </div>
    <div class="model-config-actions">
      <button class="action-button primary" id="baselineModelSaveButton">保存运行时配置</button>
      <button class="action-button secondary" id="baselineModelResetButton">重载当前配置</button>
      <span class="${statusClass}" id="baselineModelStatus">${state.baselineModelStatus || "修改后仅影响当前进程，刷新数据即可观察结果变化。"}</span>
    </div>
  `;
  document.getElementById("baselineModelSaveButton")?.addEventListener("click", saveBaselineModelConfig);
  document.getElementById("baselineModelResetButton")?.addEventListener("click", reloadBaselineModelConfig);
}

function baselineNumber(id) {
  const raw = document.getElementById(id)?.value;
  if (raw === undefined || raw === null || String(raw).trim() === "") {
    return null;
  }
  return Number(raw);
}

function collectBaselineModelRequest() {
  return {
    grossEdgePriorWeight: baselineNumber("baselineGrossEdgePriorWeight"),
    followProbability: {
      intercept: baselineNumber("baselineFollowIntercept"),
      walletScoreWeight: baselineNumber("baselineFollowWalletScoreWeight"),
      consensusScoreWeight: baselineNumber("baselineFollowConsensusScoreWeight"),
      positiveRateWeight: baselineNumber("baselineFollowPositiveRateWeight"),
      triggerSizeWeight: baselineNumber("baselineFollowTriggerSizeWeight"),
      distinctWalletWeight: baselineNumber("baselineFollowDistinctWalletWeight"),
      imbalanceWeight: baselineNumber("baselineFollowImbalanceWeight"),
      spreadPenaltyWeight: baselineNumber("baselineFollowSpreadPenaltyWeight"),
      volatilityPenaltyWeight: baselineNumber("baselineFollowVolatilityPenaltyWeight"),
      triggerSizeCapUsdc: baselineNumber("baselineFollowTriggerSizeCapUsdc"),
      distinctWalletCap: baselineNumber("baselineFollowDistinctWalletCap"),
      spreadPenaltyScaleBps: baselineNumber("baselineFollowSpreadPenaltyScaleBps"),
      volatilityPenaltyScale: baselineNumber("baselineFollowVolatilityPenaltyScale")
    },
    slippage: {
      spreadCostWeight: baselineNumber("baselineSlippageSpreadCostWeight"),
      impactCostWeight: baselineNumber("baselineSlippageImpactCostWeight"),
      volatilityCostWeight: baselineNumber("baselineSlippageVolatilityCostWeight")
    },
    slippageSpike: {
      intercept: baselineNumber("baselineSpikeIntercept"),
      spreadRiskWeight: baselineNumber("baselineSpikeSpreadRiskWeight"),
      volatilityRiskWeight: baselineNumber("baselineSpikeVolatilityRiskWeight"),
      sizeDepthRiskWeight: baselineNumber("baselineSpikeSizeDepthRiskWeight"),
      historicalSpikeWeight: baselineNumber("baselineSpikeHistoricalSpikeWeight"),
      spreadRiskScaleBps: baselineNumber("baselineSpikeSpreadRiskScaleBps"),
      volatilityRiskScale: baselineNumber("baselineSpikeVolatilityRiskScale"),
      spikeThresholdBps: baselineNumber("baselineSpikeThresholdBps")
    },
    calibration: {
      followProbabilityScale: baselineNumber("baselineCalibrationFollowScale"),
      followProbabilityBias: baselineNumber("baselineCalibrationFollowBias"),
      slippageSpikeScale: baselineNumber("baselineCalibrationSpikeScale"),
      slippageSpikeBias: baselineNumber("baselineCalibrationSpikeBias")
    }
  };
}

async function reloadBaselineModelConfig() {
  try {
    state.baselineModelConfig = await dashboardGetJson("/smart-wallet/config/baseline-model");
    state.baselineModelStatus = "已重载当前运行时配置。";
    state.baselineModelStatusLevel = "success";
    renderBaselineModelPanel();
  } catch (error) {
    state.baselineModelStatus = `重载失败：${error.message}`;
    state.baselineModelStatusLevel = "error";
    renderBaselineModelPanel();
  }
}

async function saveBaselineModelConfig() {
  try {
    const request = collectBaselineModelRequest();
    state.baselineModelConfig = await dashboardPostJson("/smart-wallet/config/baseline-model", request);
    state.baselineModelStatus = "运行时配置已更新，正在刷新策略数据。";
    state.baselineModelStatusLevel = "success";
    renderBaselineModelPanel();
    await loadSelected();
  } catch (error) {
    state.baselineModelStatus = `保存失败：${error.message}`;
    state.baselineModelStatusLevel = "error";
    renderBaselineModelPanel();
  }
}

async function loadSelected() {
  const days = dashboardSelectedDays();
  state.positionMode = dashboardSelectedMode();
  state.detail = await loadStrategyDashboardDetail(state.selected, { days, mode: state.positionMode });
  renderOverview(state.detail);
  renderDetail(state.detail);
}

async function init() {
  try {
    state.selected = parseStrategyCode();
    state.positionMode = dashboardSelectedMode();
    const days = dashboardSelectedDays();
    const [overview, summary, baselineConfig] = await Promise.all([
      loadStrategyDashboardOverviewWithMode(state.positionMode),
      loadStrategyDashboardDetail(state.selected, { days, mode: state.positionMode }),
      dashboardGetJson("/smart-wallet/config/baseline-model")
    ]);
    summary.strategyList = overview?.strategies || [];
    state.baselineModelConfig = baselineConfig;
    state.detail = summary;
    renderOverview(summary);
    renderDetail(summary);
  } catch (error) {
    document.body.innerHTML = `<div class="page"><section class="panel"><h1>页面加载失败</h1><p>${error.message}</p></section></div>`;
  }
}

init();
