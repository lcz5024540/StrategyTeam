#!/usr/bin/env python3
"""Turn immutable model research evidence into a fail-closed policy report.

The report is deliberately an offline research artifact.  It never writes a
Dashboard policy row and it never authorizes a live HTX order.  A model can at
most become a paper candidate here; a separate same-source execution replay is
required before any live-policy discussion.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


SCHEMA = "htx-trend-model-policy-v1"
QUALITY_SCHEMA = "htx-trend-research-quality-gate-v1"
ACCEPTANCE_SCHEMA = "htx-trend-research-acceptance-report-v1"
MIN_TRADES = 30
MIN_PF = 1.30


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _complete_manifest(manifest: dict) -> bool:
    return (manifest.get("schema") == "htx-trend-research-dataset-manifest-v1"
            and manifest.get("inputIntegrity") == "COMPLETE"
            and bool(manifest.get("datasetKey")))


def _valid_inputs(manifest: dict, quality: dict, acceptance: dict) -> tuple[bool, str]:
    dataset_key = manifest.get("datasetKey")
    if not _complete_manifest(manifest):
        return False, "dataset manifest is not a COMPLETE frozen research dataset"
    if (quality.get("schema") != QUALITY_SCHEMA or quality.get("datasetKey") != dataset_key
            or quality.get("inputIntegrity") != "COMPLETE" or quality.get("decision") != "PASS"):
        return False, "a PASS quality gate for the same COMPLETE dataset is required"
    if (acceptance.get("schema") != ACCEPTANCE_SCHEMA or acceptance.get("datasetKey") != dataset_key
            or acceptance.get("inputIntegrity") != "COMPLETE"):
        return False, "a COMPLETE acceptance report for the same dataset is required"
    return True, ""


def _policy_for_evaluation(evaluation: dict) -> tuple[str, list[str]]:
    reasons: list[str] = []
    trades = int(evaluation.get("tradeCount") or 0)
    net = float(evaluation.get("netProfit") or 0)
    pf = float(evaluation.get("profitFactor") or 0)
    expectancy = float(evaluation.get("expectancy") or 0)
    if trades < MIN_TRADES:
        reasons.append(f"样本量 {trades} 小于最低 {MIN_TRADES}")
    if net <= 0:
        reasons.append(f"扣费后净收益 {net:.6f}U 非正")
    if pf < MIN_PF:
        reasons.append(f"PF {pf:.4f} 低于最低 {MIN_PF:.2f}")
    if expectancy <= 0:
        reasons.append(f"单笔期望 {expectancy:.6f}U 非正")
    return ("PAPER_CANDIDATE" if not reasons else "REJECT"), reasons


def build_policy(manifest: dict, quality: dict, acceptance: dict, *, source_digests: dict[str, str] | None = None) -> dict:
    valid, reason = _valid_inputs(manifest, quality, acceptance)
    dataset_key = manifest.get("datasetKey")
    base = {
        "schema": SCHEMA,
        "datasetKey": dataset_key,
        "inputIntegrity": "COMPLETE" if valid else "INSUFFICIENT_EVIDENCE",
        "liveExecution": "FORBIDDEN",
        "databasePolicyMutation": "FORBIDDEN",
        "promotionBoundary": "PAPER_CANDIDATE is not a live authorization; same-source execution replay and an explicit approval are still required",
        "sourceDigests": source_digests or {},
        "thresholds": {"minTrades": MIN_TRADES, "minProfitFactor": MIN_PF,
                       "requiresPositiveNetProfit": True, "requiresPositiveExpectancy": True},
    }
    if not valid:
        return {**base, "decision": "INSUFFICIENT_EVIDENCE", "reason": reason, "models": []}

    oos = [row for row in acceptance.get("modelEvaluations", [])
           if row.get("sampleSplit") == "OUT_OF_SAMPLE"]
    by_model = {row.get("modelCode"): row for row in oos if row.get("modelCode")}
    models = []
    for model in sorted(by_model):
        evaluation = by_model[model]
        decision, reasons = _policy_for_evaluation(evaluation)
        models.append({
            "modelCode": model,
            "sourceEvaluationKey": evaluation.get("evaluationKey"),
            "sampleSplit": "OUT_OF_SAMPLE",
            "decision": decision,
            "liveEnabled": False,
            "tradeCount": evaluation.get("tradeCount"),
            "netProfit": evaluation.get("netProfit"),
            "profitFactor": evaluation.get("profitFactor"),
            "expectancy": evaluation.get("expectancy"),
            "maxDrawdown": evaluation.get("maxDrawdown"),
            "feeTotal": evaluation.get("feeTotal"),
            "reasons": reasons,
            "nextRequirement": ("same-source execution replay, then explicit policy review"
                                if decision == "PAPER_CANDIDATE"
                                else "new independent model specification and a fresh frozen out-of-sample evaluation"),
        })
    missing = sorted({"DONCHIAN_BREAKOUT", "FIB_PULLBACK_CONTINUATION", "EMA_VWAP_MOMENTUM"} - set(by_model))
    for model in missing:
        models.append({"modelCode": model, "decision": "OBSERVE_ONLY", "liveEnabled": False,
                       "reasons": ["缺少同一冻结数据集的样本外评估"],
                       "nextRequirement": "complete frozen out-of-sample evaluation"})
    overall = "PAPER_CANDIDATE" if any(row["decision"] == "PAPER_CANDIDATE" for row in models) else "REJECT"
    return {**base, "decision": overall, "models": sorted(models, key=lambda row: row["modelCode"])}


def markdown(policy: dict) -> str:
    lines = ["# HTX 趋势模型政策报告", "",
             f"- 数据集：`{policy.get('datasetKey')}`",
             f"- 准入结论：`{policy.get('decision')}`",
             f"- 实盘授权：`{policy.get('liveExecution')}`", "",
             "此报告只固定研究政策，不能写入交易数据库或开启 HTX 实盘交易。", "",
             "| 模型 | 研究结论 | 样本 | 净收益 | PF | 单笔期望 | 实盘 | 原因 |",
             "|---|---|---:|---:|---:|---:|---|---|"]
    for model in policy.get("models", []):
        reasons = "；".join(model.get("reasons", [])) or "满足研究门槛，仍须同源执行回放"
        lines.append("| {model} | {decision} | {trades} | {net} | {pf} | {expectancy} | 禁止 | {reasons} |".format(
            model=model["modelCode"], decision=model["decision"], trades=model.get("tradeCount", "-"),
            net=(f"{float(model['netProfit']):.4f}" if model.get("netProfit") is not None else "-"),
            pf=(f"{float(model['profitFactor']):.2f}" if model.get("profitFactor") is not None else "-"),
            expectancy=(f"{float(model['expectancy']):.4f}" if model.get("expectancy") is not None else "-"),
            reasons=reasons))
    if policy.get("reason"):
        lines.extend(["", f"证据不足原因：{policy['reason']}"])
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--quality-report", type=Path, required=True)
    parser.add_argument("--acceptance-report", type=Path, required=True)
    parser.add_argument("--json-out", type=Path, required=True)
    parser.add_argument("--markdown-out", type=Path, required=True)
    args = parser.parse_args()
    policy = build_policy(json.loads(args.manifest.read_text()), json.loads(args.quality_report.read_text()),
                          json.loads(args.acceptance_report.read_text()), source_digests={
                              "manifestSha256": digest(args.manifest), "qualityReportSha256": digest(args.quality_report),
                              "acceptanceReportSha256": digest(args.acceptance_report)})
    args.json_out.parent.mkdir(parents=True, exist_ok=True)
    args.markdown_out.parent.mkdir(parents=True, exist_ok=True)
    args.json_out.write_text(json.dumps(policy, ensure_ascii=False, indent=2) + "\n")
    args.markdown_out.write_text(markdown(policy))
    print(json.dumps({"datasetKey": policy.get("datasetKey"), "decision": policy["decision"],
                      "modelCount": len(policy["models"])}, ensure_ascii=False))


if __name__ == "__main__":
    main()
