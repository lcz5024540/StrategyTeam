"""Markdown 报告生成器"""

from __future__ import annotations

from datetime import datetime
from pathlib import Path
from typing import Any

import pandas as pd


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
            
            if fills and not (smoke or config.get("run_type") == "SMOKE"):
                f.write("## 绩效指标\n\n")
                f.write(self._format_metrics(fills))
                f.write("\n\n")
                
                f.write("## 分品种统计\n\n")
                f.write(self._format_by_symbol(fills))
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
