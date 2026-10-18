"""Markdown 报告生成器"""

from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import pandas as pd

TOTAL_SCOPE = "TOTAL"


def summarize_fee_ledger(
    fills: list[dict[str, Any]],
    funding_events: list[dict[str, Any]] | None = None,
    *,
    initial_equity: float | None = None,
    final_equity: float | None = None,
    last_closes: dict[str, float] | None = None,
) -> dict[str, Any]:
    """Entry/exit fees, slippage, signed funding, and equity reconciliation."""
    buckets: dict[str, dict[str, float]] = {}

    def bucket(symbol: str) -> dict[str, float]:
        if symbol not in buckets:
            buckets[symbol] = {
                "entry_fees": 0.0,
                "exit_fees": 0.0,
                "slippage": 0.0,
                "funding_paid": 0.0,
                "funding_received": 0.0,
                "net_funding": 0.0,
            }
        return buckets[symbol]

    pending: dict[str, list[dict[str, Any]]] = {}
    realized = 0.0
    for fill in fills:
        symbol = str(fill.get("symbol") or "")
        row = bucket(symbol)
        fees = float(fill.get("fees") or 0.0)
        slip = float(fill.get("slippage") or 0.0)
        row["slippage"] += slip
        if fill.get("is_entry"):
            row["entry_fees"] += fees
            pending.setdefault(symbol, []).append(fill)
            continue
        row["exit_fees"] += fees
        entries = pending.get(symbol) or []
        if not entries:
            continue
        entry = entries.pop(0)
        size = float(fill.get("size") or 0.0)
        contract = float(fill.get("contract_size") or entry.get("contract_size") or 1.0)
        direction = 1.0 if entry.get("side") == "LONG" else -1.0
        realized += (float(fill["price"]) - float(entry["price"])) * size * contract * direction

    if funding_events:
        for event in funding_events:
            symbol = str(event.get("symbol") or "")
            row = bucket(symbol)
            if event.get("signed_payment") is not None:
                signed = float(event["signed_payment"])
            else:
                payment = float(event.get("payment") or 0.0)
                signed = payment if event.get("side") != "SHORT" else -payment
            if signed >= 0:
                row["funding_paid"] += signed
            else:
                row["funding_received"] += -signed
            row["net_funding"] += signed
    else:
        for fill in fills:
            if fill.get("is_entry"):
                continue
            signed = float(fill.get("funding") or 0.0)
            row = bucket(str(fill.get("symbol") or ""))
            if signed >= 0:
                row["funding_paid"] += signed
            else:
                row["funding_received"] += -signed
            row["net_funding"] += signed

    totals = {
        "entry_fees": 0.0,
        "exit_fees": 0.0,
        "slippage": 0.0,
        "funding_paid": 0.0,
        "funding_received": 0.0,
        "net_funding": 0.0,
    }
    for row in buckets.values():
        for key in totals:
            totals[key] += row[key]

    unrealized = 0.0
    closes = last_closes or {}
    for symbol, entries in pending.items():
        for entry in entries:
            last = float(closes.get(symbol, entry["price"]))
            size = float(entry.get("size") or 0.0)
            contract = float(entry.get("contract_size") or 1.0)
            direction = 1.0 if entry.get("side") == "LONG" else -1.0
            unrealized += (last - float(entry["price"])) * size * contract * direction

    fees = totals["entry_fees"] + totals["exit_fees"]
    expected = None
    diff = None
    if initial_equity is not None:
        expected = (
            float(initial_equity)
            + realized
            - fees
            - totals["slippage"]
            - totals["net_funding"]
            + unrealized
        )
        if final_equity is not None:
            diff = float(final_equity) - expected

    return {
        "by_symbol": buckets,
        "total": totals,
        "reconciliation": {
            "initial_equity": initial_equity,
            "realized_pnl": realized,
            "fees": fees,
            "slippage": totals["slippage"],
            "net_funding": totals["net_funding"],
            "unrealized_pnl": unrealized,
            "expected_final": expected,
            "actual_final": final_equity,
            "diff": diff,
        },
    }


class ReportGenerator:
    def __init__(self, run_id: str, output_dir: Path):
        self.run_id = run_id
        self.output_dir = output_dir
        self.output_dir.mkdir(parents=True, exist_ok=True)
    
    def generate_report(
        self,
        config: dict[str, Any],
        fills: list[dict[str, Any]],
        decisions: pd.DataFrame,
        parent_comparison: dict[str, Any] | None = None,
        registration: Any | None = None,
        overlap_deviation: dict[str, Any] | None = None,
        smoke: bool = False,
        funding_missing: list[str] | None = None,
        stitch_points: dict[str, Any] | None = None,
        funding_events: list[dict[str, Any]] | None = None,
        initial_equity: float | None = None,
        final_equity: float | None = None,
    ) -> Path:
        report_path = self.output_dir / "report.md"
        
        with open(report_path, "w") as f:
            title = "SMOKE 回测报告" if smoke or config.get("run_type") == "SMOKE" else "回测报告"
            f.write(f"# {title} - {self.run_id}\n\n")
            if smoke or config.get("run_type") == "SMOKE":
                f.write("**SMOKE**: 不计入 trials，仅 train split，不可用于正式结论。\n\n")
            f.write(f"生成时间: {datetime.now().isoformat()}\n\n")
            
            f.write("## 配置\n\n")
            f.write("```yaml\n")
            f.write(self._format_config(config))
            f.write("```\n\n")

            f.write("## 实验登记\n\n")
            f.write(self._format_registration(config, registration))
            f.write("\n\n")

            f.write("## 结论与决策\n\n")
            f.write(self._format_conclusion(registration))
            f.write("\n\n")
            
            f.write("## 交易统计\n\n")
            f.write(self._format_trade_stats(fills))
            f.write("\n\n")

            f.write("## fees\n\n")
            f.write(
                self._format_fees(
                    fills,
                    funding_events or [],
                    initial_equity=initial_equity
                    if initial_equity is not None
                    else config.get("initial_equity"),
                    final_equity=final_equity,
                    decisions=decisions,
                )
            )
            f.write("\n\n")
            
            if fills and not (smoke or config.get("run_type") == "SMOKE"):
                f.write("## 绩效指标\n\n")
                f.write(self._format_metrics(fills))
                f.write("\n\n")
                
                f.write("## 分品种统计\n\n")
                f.write(self._format_by_symbol(fills))
                f.write("\n\n")

                if stitch_points:
                    f.write("## 绩效分段（stitch point）\n\n")
                    f.write(self._format_stitch_segments(fills, stitch_points))
                    f.write("\n\n")
                
                f.write("## 市场状态切片\n\n")
                f.write(self._format_by_regime(fills, decisions))
                f.write("\n\n")
            
            if parent_comparison:
                f.write("## 与父运行对比\n\n")
                f.write(self._format_comparison(parent_comparison))
                f.write("\n\n")
            
            if overlap_deviation:
                f.write("## 重叠偏差分布\n\n")
                f.write(self._format_overlap_deviation(overlap_deviation))
                f.write("\n\n")

            if funding_missing:
                f.write("## funding_missing\n\n")
                f.write(
                    "Funding history was not attached for: "
                    + ", ".join(funding_missing)
                    + "\n\n"
                )

            f.write("## 决策记录摘要\n\n")
            f.write(f"总决策数: {len(decisions)}\n\n")
            if not decisions.empty:
                f.write(f"- 开仓信号: {(decisions.get('action', '') == 'ENTER').sum()}\n")
                f.write(f"- 平仓信号: {(decisions.get('action', '') == 'EXIT').sum()}\n")
                f.write(f"- 无动作: {(decisions.get('action', '') == 'HOLD').sum()}\n")
        
        return report_path
    
    def _format_registration(self, config: dict[str, Any], registration: Any | None) -> str:
        if registration is None:
            reg = config.get("registration") or {}
            change = reg.get("change")
            hypothesis = reg.get("hypothesis")
            expected_effect = reg.get("expected_effect")
            evidence_ref = reg.get("evidence_ref")
            keep_rule = reg.get("keep_rule")
            drop_rule = reg.get("drop_rule")
            trial_count = reg.get("trial_count")
            uses_holdout = config.get("split") == "test"
        else:
            change = registration.change
            hypothesis = registration.hypothesis
            expected_effect = registration.expected_effect
            evidence_ref = registration.evidence_ref
            keep_rule = registration.keep_rule
            drop_rule = registration.drop_rule
            trial_count = registration.trial_count
            uses_holdout = registration.uses_holdout
        return (
            f"- change: {change}\n"
            f"- hypothesis: {hypothesis}\n"
            f"- expected_effect: {expected_effect}\n"
            f"- evidence_ref: {evidence_ref}\n"
            f"- keep_rule: {keep_rule}\n"
            f"- drop_rule: {drop_rule}\n"
            f"- trial_count: {trial_count}\n"
            f"- uses_holdout: {uses_holdout}\n"
        )

    def _format_conclusion(self, registration: Any | None) -> str:
        conclusion = getattr(registration, "conclusion", None) if registration else None
        decision = getattr(registration, "decision", None) if registration else None
        return (
            f"- conclusion: {conclusion}\n"
            f"- decision: {decision}\n"
            f"- note: Fill via CLI: update-conclusion --run-id {self.run_id}\n"
        )

    def _format_overlap_deviation(self, payload: dict[str, Any]) -> str:
        lines = [
            "| symbol | timeframe | count | median | p95 | max | bars_over_threshold | threshold |",
            "| --- | --- | --- | --- | --- | --- | --- | --- |",
        ]
        for symbol, by_tf in payload.items():
            if not isinstance(by_tf, dict):
                continue
            for timeframe, stats in by_tf.items():
                if not isinstance(stats, dict):
                    continue
                lines.append(
                    "| {symbol} | {timeframe} | {count} | {median} | {p95} | {max} | {over} | {thr} |".format(
                        symbol=symbol,
                        timeframe=timeframe,
                        count=stats.get("count"),
                        median=stats.get("median"),
                        p95=stats.get("p95"),
                        max=stats.get("max"),
                        over=stats.get("bars_over_threshold"),
                        thr=stats.get("threshold"),
                    )
                )
        return "\n".join(lines) + "\n"

    def _format_config(self, config: dict[str, Any]) -> str:
        import yaml
        return yaml.dump(config, default_flow_style=False, allow_unicode=True)
    
    def _format_fees(
        self,
        fills: list[dict[str, Any]],
        funding_events: list[dict[str, Any]],
        *,
        initial_equity: float | None,
        final_equity: float | None,
        decisions: pd.DataFrame,
    ) -> str:
        last_closes: dict[str, float] = {}
        if decisions is not None and not decisions.empty and "close" in decisions.columns:
            grouped = (
                decisions
                if "symbol" not in decisions.columns
                else decisions
            )
            if "symbol" in grouped.columns:
                for symbol, rows in grouped.groupby("symbol"):
                    last_closes[str(symbol)] = float(rows.iloc[-1]["close"])
        summary = summarize_fee_ledger(
            fills,
            funding_events,
            initial_equity=None if initial_equity is None else float(initial_equity),
            final_equity=None if final_equity is None else float(final_equity),
            last_closes=last_closes,
        )
        header = (
            "| scope | entry_fees | exit_fees | slippage | "
            "funding_paid | funding_received | net_funding |"
        )
        lines = [header, "| --- | --- | --- | --- | --- | --- | --- |"]
        for symbol in sorted(summary["by_symbol"]):
            row = summary["by_symbol"][symbol]
            lines.append(_fee_row(symbol, row))
        lines.append(_fee_row(TOTAL_SCOPE, summary["total"]))
        recon = summary["reconciliation"]
        lines.extend(
            [
                "",
                "### equity reconciliation",
                (
                    "initial + realized_pnl - fees - slippage - net_funding "
                    "+ unrealized = expected_final"
                ),
                (
                    f"{_num(recon['initial_equity'])} + {_num(recon['realized_pnl'])} "
                    f"- {_num(recon['fees'])} - {_num(recon['slippage'])} "
                    f"- {_num(recon['net_funding'])} + {_num(recon['unrealized_pnl'])} "
                    f"= {_num(recon['expected_final'])}"
                ),
                (
                    f"actual_final={_num(recon['actual_final'])} "
                    f"diff={_num(recon['diff'])}"
                ),
            ]
        )
        return "\n".join(lines) + "\n"

    def _format_trade_stats(self, fills: list[dict[str, Any]]) -> str:
        if not fills:
            return "无交易记录\n"
        
        entries = [f for f in fills if f.get("is_entry")]
        exits = [f for f in fills if not f.get("is_entry")]
        
        total_fees = sum(f.get("fees", 0) for f in fills)
        
        return f"""
- 总交易笔数: {len(entries)}
- 总成交记录: {len(fills)}
- 总手续费: ${total_fees:.2f}
"""
    
    def _format_metrics(self, fills: list[dict[str, Any]]) -> str:
        exits = [f for f in fills if not f.get("is_entry")]
        
        if not exits:
            return "暂无完整交易\n"
        
        pnls = []
        for exit_fill in exits:
            symbol = exit_fill["symbol"]
            entries = [f for f in fills if f["symbol"] == symbol and f.get("is_entry")]
            if entries:
                entry = entries[-1]
                if exit_fill["side"] == "LONG":
                    pnl = (exit_fill["price"] - entry["price"]) * exit_fill["size"]
                else:
                    pnl = (entry["price"] - exit_fill["price"]) * exit_fill["size"]
                
                pnl -= entry.get("fees", 0) + exit_fill.get("fees", 0)
                pnl -= entry.get("slippage", 0) + exit_fill.get("slippage", 0)
                pnls.append(pnl)
        
        if not pnls:
            return "无完整PnL记录\n"
        
        total_pnl = sum(pnls)
        wins = [p for p in pnls if p > 0]
        losses = [p for p in pnls if p <= 0]
        
        win_rate = len(wins) / len(pnls) if pnls else 0
        avg_win = sum(wins) / len(wins) if wins else 0
        avg_loss = sum(losses) / len(losses) if losses else 0
        profit_factor = abs(sum(wins) / sum(losses)) if losses and sum(losses) != 0 else 0
        
        return f"""
- 总PnL: ${total_pnl:.2f}
- 胜率: {win_rate*100:.1f}%
- 平均盈利: ${avg_win:.2f}
- 平均亏损: ${avg_loss:.2f}
- Profit Factor: {profit_factor:.2f}
"""
    
    def _format_stitch_segments(
        self,
        fills: list[dict[str, Any]],
        stitch_points: dict[str, Any],
    ) -> str:
        before: list[float] = []
        after: list[float] = []
        labels: list[str] = []
        for symbol, exit_time, pnl in self._closed_trades(fills):
            stitch = _stitch_for(symbol, stitch_points)
            if stitch is None:
                continue
            labels.append(stitch.isoformat())
            if exit_time < stitch:
                before.append(pnl)
            else:
                after.append(pnl)
        stitch_label = labels[0] if labels else "unknown"
        return (
            f"Stitch point: {stitch_label}\n\n"
            f"### Before {stitch_label}\n"
            f"{self._format_segment_stats(before)}\n"
            f"### After {stitch_label}\n"
            f"{self._format_segment_stats(after)}\n"
        )

    def _format_segment_stats(self, pnls: list[float]) -> str:
        if not pnls:
            return "- 交易笔数: 0\n- 总PnL: $0.00\n- 胜率: 0.0%\n"
        total = sum(pnls)
        wins = [p for p in pnls if p > 0]
        win_rate = len(wins) / len(pnls)
        return (
            f"- 交易笔数: {len(pnls)}\n"
            f"- 总PnL: ${total:.2f}\n"
            f"- 胜率: {win_rate * 100:.1f}%\n"
        )

    def _closed_trades(
        self, fills: list[dict[str, Any]]
    ) -> list[tuple[str, datetime, float]]:
        pending: dict[str, list[dict[str, Any]]] = {}
        trades: list[tuple[str, datetime, float]] = []
        ordered = sorted(fills, key=lambda item: pd.Timestamp(item["time"]))
        for fill in ordered:
            symbol = fill["symbol"]
            if fill.get("is_entry"):
                pending.setdefault(symbol, []).append(fill)
                continue
            entries = pending.get(symbol)
            if not entries:
                continue
            entry = entries.pop(0)
            size = float(fill["size"])
            if entry["side"] == "LONG":
                pnl = (float(fill["price"]) - float(entry["price"])) * size
            else:
                pnl = (float(entry["price"]) - float(fill["price"])) * size
            pnl -= float(entry.get("fees", 0) or 0) + float(fill.get("fees", 0) or 0)
            pnl -= float(entry.get("slippage", 0) or 0) + float(fill.get("slippage", 0) or 0)
            pnl -= float(fill.get("funding", 0) or 0)
            trades.append((symbol, pd.Timestamp(fill["time"]).to_pydatetime(), float(pnl)))
        return trades

    def _format_by_symbol(self, fills: list[dict[str, Any]]) -> str:
        symbols = set(f["symbol"] for f in fills)
        
        lines = []
        for symbol in sorted(symbols):
            symbol_fills = [f for f in fills if f["symbol"] == symbol]
            entries = [f for f in symbol_fills if f.get("is_entry")]
            lines.append(f"- {symbol}: {len(entries)} 笔")
        
        return "\n".join(lines)
    
    def _format_by_regime(
        self, fills: list[dict[str, Any]], decisions: pd.DataFrame
    ) -> str:
        if decisions.empty or "regime" not in decisions.columns:
            return "无市场状态数据\n"
        
        regimes = decisions["regime"].value_counts()
        
        lines = []
        for regime, count in regimes.items():
            lines.append(f"- {regime}: {count} 个bar")
        
        return "\n".join(lines)
    
    def _format_comparison(self, comparison: dict[str, Any]) -> str:
        parent_id = comparison.get("parent_run_id", "unknown")
        
        pct = comparison.get("pct_change")
        pct_line = f"- 百分比变化: {pct:.2f}%\n" if pct is not None else "- 百分比变化: N/A\n"
        parent_pnl = comparison.get("parent_pnl")
        pnl_diff = comparison.get("pnl_diff")
        current_pnl = comparison.get("current_pnl")
        parent_line = (
            f"- 父运行PnL: ${parent_pnl:.2f}"
            if parent_pnl is not None
            else "- 父运行PnL: N/A"
        )
        diff_line = (
            f"- 差值: ${pnl_diff:.2f}"
            if pnl_diff is not None
            else "- 差值: N/A"
        )
        current_line = (
            f"- 当前PnL: ${current_pnl:.2f}"
            if current_pnl is not None
            else "- 当前PnL: N/A"
        )
        return f"""
父运行: {parent_id}

变化点: {comparison.get('changed_key') or comparison.get('change', 'N/A')}

指标对比:
{current_line}
{parent_line}
{diff_line}
{pct_line}
"""


def _num(value: float | None) -> str:
    if value is None:
        return "n/a"
    return f"{float(value):.6f}".rstrip("0").rstrip(".")


def _fee_row(scope: str, row: dict[str, float]) -> str:
    return (
        f"| {scope} | {_num(row['entry_fees'])} | {_num(row['exit_fees'])} | "
        f"{_num(row['slippage'])} | {_num(row['funding_paid'])} | "
        f"{_num(row['funding_received'])} | {_num(row['net_funding'])} |"
    )


def _stitch_for(symbol: str, stitch_points: dict[str, Any]) -> datetime | None:
    raw = stitch_points.get(symbol)
    if raw is None and stitch_points:
        raw = next(iter(stitch_points.values()))
    if raw is None:
        return None
    if isinstance(raw, datetime):
        parsed = raw
    else:
        parsed = datetime.fromisoformat(str(raw).replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=UTC)
    return parsed.astimezone(UTC)
