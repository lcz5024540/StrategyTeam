#!/usr/bin/env python3
"""Generate the immutable P0/P3 research acceptance report from derived labels."""
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

MIN_TRADES, MIN_PF = 30, 1.3
CORE_SYMBOLS = {"BTC-USDT", "ETH-USDT", "SOL-USDT", "BNB-USDT", "XRP-USDT"}
TZ = ZoneInfo("Asia/Shanghai")


def epoch(value: str) -> int:
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=TZ)
    return int(parsed.timestamp())


def sample_split(bar_time: int, start: int, end: int) -> str:
    span = end - start
    return "TRAIN" if bar_time < start + span // 2 else "VALIDATION" if bar_time < start + span * 3 // 4 else "OUT_OF_SAMPLE"


def symbol_bucket(symbol: str) -> str:
    return "CORE" if symbol in CORE_SYMBOLS else "ALT"


def metrics(rows: list[dict]) -> dict:
    pnl = [float(row["netProfit"]) for row in rows]
    wins, losses = [value for value in pnl if value > 0], [-value for value in pnl if value < 0]
    equity = peak = 0.0; drawdown = 0.0
    for value in pnl:
        equity += value; peak = max(peak, equity); drawdown = min(drawdown, equity - peak)
    fees = sum(float(row.get("estimatedEntryFee") or 0) + float(row.get("estimatedExitFee") or 0) for row in rows)
    return {"tradeCount": len(rows), "winCount": len(wins), "lossCount": len(losses), "netProfit": sum(pnl),
            "profitFactor": sum(wins) / sum(losses) if losses else (999.0 if wins else 0.0),
            "expectancy": sum(pnl) / len(pnl) if pnl else 0.0, "maxDrawdown": drawdown, "feeTotal": fees}


def decision(value: dict) -> str:
    if value["tradeCount"] < MIN_TRADES:
        return "INSUFFICIENT_SAMPLE"
    return "PAPER_CANDIDATE" if value["netProfit"] > 0 and value["profitFactor"] >= MIN_PF and value["expectancy"] > 0 else "REJECT"


def build_report(manifest: dict, ledger: dict, quality_gate: dict | None = None) -> dict:
    if manifest.get("inputIntegrity") != "COMPLETE" or ledger.get("inputIntegrity") != "COMPLETE":
        return {"datasetKey": ledger.get("datasetKey"), "decision": "INSUFFICIENT_EVIDENCE", "rows": [],
                "reason": "a research report cannot use partial input"}
    if (quality_gate is None or quality_gate.get("schema") != "htx-trend-research-quality-gate-v1"
            or quality_gate.get("datasetKey") != manifest.get("datasetKey")
            or quality_gate.get("inputIntegrity") != "COMPLETE" or quality_gate.get("decision") != "PASS"):
        return {"datasetKey": ledger.get("datasetKey"), "decision": "INSUFFICIENT_EVIDENCE", "rows": [],
                "reason": "a PASS quality-gate report for the same COMPLETE dataset is required"}
    if manifest.get("datasetKey") != ledger.get("datasetKey"):
        raise ValueError("manifest and derived ledger dataset keys differ")
    start, end = epoch(manifest["inputStartAt"]), epoch(manifest["inputEndAt"])
    grouped: dict[tuple[str, str, str, str], list[dict]] = defaultdict(list)
    for label in ledger.get("labels", []):
        if label.get("dataIntegrity") != "COMPLETE":
            continue
        if sample_split(int(label["entryBarTime"]), start, end) != "OUT_OF_SAMPLE":
            continue
        grouped[(label["modelCode"], label["side"], label.get("marketState", "UNCLASSIFIED"),
                 symbol_bucket(label["contractCode"]))].append(label)
    rows=[]
    for (model, side, state, bucket), values in sorted(grouped.items()):
        value=metrics(values)
        rows.append({"model": model, "side": side, "marketState": state, "symbolBucket": bucket,
                     **value, "decision": decision(value)})
    # A direction/regime/symbol-bucket slice is found after inspecting this
    # dataset.  It may inform a future hypothesis, but it cannot promote a
    # model by itself: doing so would be post-hoc selection on the OOS sample.
    for row in rows:
        if row["decision"] == "PAPER_CANDIDATE":
            row["decision"] = "HYPOTHESIS_REQUIRES_VALIDATION"
    model_evaluations = sorted(ledger.get("evaluations", []),
                               key=lambda row: (row.get("modelCode", ""), row.get("sampleSplit", "")))
    oos_models = [row for row in model_evaluations if row.get("sampleSplit") == "OUT_OF_SAMPLE"]
    candidates = [row for row in oos_models if row.get("decision") == "PAPER_CANDIDATE"]
    return {"schema": "htx-trend-research-acceptance-report-v1", "datasetKey": ledger["datasetKey"],
            "inputIntegrity": "COMPLETE", "sampleSplit": "OUT_OF_SAMPLE", "thresholds": {"minTrades": MIN_TRADES, "minProfitFactor": MIN_PF},
            "decision": "PAPER_CANDIDATE" if candidates else "REJECT", "modelEvaluations": model_evaluations,
            "rows": rows, "evidence": "promotion uses complete out-of-sample model aggregates only; segmented rows are exploratory hypotheses and cannot select a live policy"}


def markdown(report: dict) -> str:
    lines=[f"# HTX 趋势研究样本外报告\n\n- 数据集：`{report.get('datasetKey')}`\n- 结论：`{report['decision']}`\n"]
    if report.get("reason"):
        return "\n".join(lines + [f"- 原因：{report['reason']}\n"])
    lines.extend(["## 模型整体评估\n", "| 模型 | 切分 | 样本 | 净收益 | PF | 期望 | 最大回撤 | 费用 | 结论 |",
                  "|---|---|---:|---:|---:|---:|---:|---:|---|"])
    for row in report.get("modelEvaluations", []):
        lines.append(f"| {row['modelCode']} | {row['sampleSplit']} | {row['tradeCount']} | {row['netProfit']:.4f} | {row['profitFactor']:.2f} | {row['expectancy']:.4f} | {row['maxDrawdown']:.4f} | {row['feeTotal']:.4f} | {row['decision']} |")
    lines.extend(["\n## 样本外分层观察\n", "以下分层在本数据集内发现，只能形成下一轮独立验证假设，不能直接提升为实盘政策。\n",
                  "| 模型 | 方向 | 状态 | 币种簇 | 样本 | 净收益 | PF | 期望 | 最大回撤 | 费用 | 结论 |",
                  "|---|---|---|---|---:|---:|---:|---:|---:|---:|---|"])
    for row in report["rows"]:
        lines.append(f"| {row['model']} | {row['side']} | {row['marketState']} | {row['symbolBucket']} | {row['tradeCount']} | {row['netProfit']:.4f} | {row['profitFactor']:.2f} | {row['expectancy']:.4f} | {row['maxDrawdown']:.4f} | {row['feeTotal']:.4f} | {row['decision']} |")
    return "\n".join(lines) + "\n"


def main() -> None:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True); parser.add_argument("--ledger", type=Path, required=True)
    parser.add_argument("--json-out", type=Path, required=True); parser.add_argument("--markdown-out", type=Path, required=True)
    parser.add_argument("--quality-report", type=Path, required=True)
    args=parser.parse_args()
    report=build_report(json.loads(args.manifest.read_text()), json.loads(args.ledger.read_text()),
                        json.loads(args.quality_report.read_text()))
    args.json_out.write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n")
    args.markdown_out.write_text(markdown(report))
    print(json.dumps({"decision":report["decision"],"rows":len(report["rows"])},ensure_ascii=False))

if __name__ == "__main__": main()
