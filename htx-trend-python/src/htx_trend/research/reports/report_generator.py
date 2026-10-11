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
    ) -> Path:
        report_path = self.output_dir / "report.md"
        
        with open(report_path, "w") as f:
            f.write(f"# 回测报告 - {self.run_id}\n\n")
            f.write(f"生成时间: {datetime.now().isoformat()}\n\n")
            
            f.write("## 配置\n\n")
            f.write("```yaml\n")
            f.write(self._format_config(config))
            f.write("```\n\n")
            
            f.write("## 交易统计\n\n")
            f.write(self._format_trade_stats(fills))
            f.write("\n\n")
            
            if fills:
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
            
            f.write("## 决策记录摘要\n\n")
            f.write(f"总决策数: {len(decisions)}\n\n")
            if not decisions.empty:
                f.write(f"- 开仓信号: {(decisions.get('action', '') == 'ENTER').sum()}\n")
                f.write(f"- 平仓信号: {(decisions.get('action', '') == 'EXIT').sum()}\n")
                f.write(f"- 无动作: {(decisions.get('action', '') == 'HOLD').sum()}\n")
        
        return report_path
    
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
        
        return f"""
父运行: {parent_id}

变化点: {comparison.get('change', 'N/A')}

指标对比:
- 当前PnL: ${comparison.get('current_pnl', 0):.2f}
- 父运行PnL: ${comparison.get('parent_pnl', 0):.2f}
- 差值: ${comparison.get('pnl_diff', 0):.2f}
"""
