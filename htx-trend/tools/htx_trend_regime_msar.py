#!/usr/bin/env python3
"""Offline two-state Markov-switching AR(1) research for HTX 4h/1d market context.

This is a high-timeframe comparator to GHMM, not a live signal.  It measures
whether conditional market return and volatility switch between a lower-noise
state and a high-volatility transition state using only completed frozen bars.
"""
from __future__ import annotations

import argparse
import json
import math
from dataclasses import dataclass, asdict
from pathlib import Path
from statistics import mean, pstdev
from typing import Sequence

from htx_trend_backtest import load_bars
import hashlib

from htx_trend_regime_ghmm import _read_manifest

SCHEMA = "htx-trend-msar-regime-report-v1"
MODEL_NAME = "MARKOV_SWITCHING_AR"
MODEL_VERSION = "msar-market-context-v1"
PERIODS = ("4hour", "1day")


@dataclass(frozen=True)
class MsarConfig:
    states: int = 2
    max_iterations: int = 120
    convergence_tolerance: float = 1e-7
    minimum_variance: float = 1e-8
    transition_pseudocount: float = 1e-3
    initial_stay_probability: float = 0.85
    min_source_bars: int = 128


def _read_msar_manifest(path: Path) -> dict:
    """Read a frozen high-timeframe dataset without inheriting the GHMM 15m contract.

    GHMM deliberately requires one 15m public file per contract because its features
    are intraday.  MS-AR consumes only the two high timeframes below.  Reusing that
    validator made an otherwise complete high-timeframe study fail for an unrelated
    15m-data reason.
    """
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("schema") != "htx-trend-research-dataset-manifest-v1":
        raise ValueError("unsupported research manifest schema")
    if manifest.get("inputIntegrity") != "COMPLETE":
        raise ValueError("MS-AR research requires a COMPLETE frozen dataset")
    if manifest.get("sourceKind") not in {"FROZEN_PUBLIC_KLINE", "FROZEN_RUNTIME_KLINE"}:
        raise ValueError("MS-AR research requires frozen public or runtime K-line input")
    contracts = manifest.get("contracts") or []
    if not contracts:
        raise ValueError("manifest has no contracts")
    inputs = manifest.get("inputs") or []
    for contract in contracts:
        for period in PERIODS:
            matching = [row for row in inputs if row.get("contract") == contract and row.get("period") == period]
            if len(matching) != 1 or matching[0].get("coverage") != "COMPLETE":
                raise ValueError(f"manifest requires one COMPLETE {period} input per contract")
    return manifest


def _logsumexp(values: Sequence[float]) -> float:
    maximum = max(values)
    return maximum if not math.isfinite(maximum) else maximum + math.log(sum(math.exp(value - maximum) for value in values))


def _normalize(values: Sequence[float]) -> list[float]:
    total = _logsumexp(values)
    return [value - total for value in values]


class MarkovSwitchingAr:
    def __init__(self, config: MsarConfig):
        if config.states != 2:
            raise ValueError("MS-AR v1 has exactly two interpretable market-context states")
        if not 0 < config.initial_stay_probability < 1:
            raise ValueError("initial stay probability must be between zero and one")
        self.config = config
        self.start: list[float] = []
        self.transitions: list[list[float]] = []
        self.intercepts: list[float] = []
        self.phis: list[float] = []
        self.variances: list[float] = []
        self.iterations, self.log_likelihood, self.converged = 0, float("-inf"), False

    def _initialize(self, values: Sequence[float]) -> None:
        if len(values) < self.config.states * 24:
            raise ValueError("MS-AR training sample is too short")
        ordered = sorted(values)
        midpoint = len(ordered) // 2
        groups = (ordered[:midpoint], ordered[midpoint:])
        self.intercepts = [mean(group) for group in groups]
        self.phis = [0.0, 0.0]
        self.variances = [max(pstdev(group) ** 2, self.config.minimum_variance) for group in groups]
        self.start = [0.5, 0.5]
        stay = self.config.initial_stay_probability
        self.transitions = [[stay, 1 - stay], [1 - stay, stay]]

    def _emissions(self, values: Sequence[float]) -> list[list[float]]:
        result = []
        for index in range(1, len(values)):
            row = []
            for state in range(self.config.states):
                variance = self.variances[state]
                residual = values[index] - self.intercepts[state] - self.phis[state] * values[index - 1]
                row.append(-0.5 * (math.log(2 * math.pi * variance) + residual * residual / variance))
            result.append(row)
        return result

    def _forward(self, emissions: Sequence[Sequence[float]]) -> tuple[list[list[float]], float]:
        first = _normalize([math.log(self.start[state]) + emissions[0][state] for state in range(self.config.states)])
        alpha, likelihood = [first], _logsumexp([math.log(self.start[state]) + emissions[0][state] for state in range(self.config.states)])
        for emission in emissions[1:]:
            row = [emission[state] + _logsumexp([alpha[-1][prior] + math.log(self.transitions[prior][state])
                                                  for prior in range(self.config.states)])
                   for state in range(self.config.states)]
            scale = _logsumexp(row); alpha.append([value - scale for value in row]); likelihood += scale
        return alpha, likelihood

    def _backward(self, emissions: Sequence[Sequence[float]]) -> list[list[float]]:
        beta = [[0.0] * self.config.states for _ in emissions]
        for index in range(len(emissions) - 2, -1, -1):
            values = [_logsumexp([math.log(self.transitions[state][next_state]) + emissions[index + 1][next_state]
                                   + beta[index + 1][next_state] for next_state in range(self.config.states)])
                      for state in range(self.config.states)]
            beta[index] = _normalize(values)
        return beta

    def fit(self, values: Sequence[float]) -> "MarkovSwitchingAr":
        self._initialize(values)
        previous = None
        for iteration in range(1, self.config.max_iterations + 1):
            emissions = self._emissions(values); alpha, likelihood = self._forward(emissions); beta = self._backward(emissions)
            gamma = [[math.exp(value) for value in _normalize([alpha[t][state] + beta[t][state]
                                                                for state in range(self.config.states)])]
                     for t in range(len(emissions))]
            starts = [gamma[0][state] + self.config.transition_pseudocount for state in range(self.config.states)]
            self.start = [value / sum(starts) for value in starts]
            transitions = [[self.config.transition_pseudocount] * self.config.states for _ in range(self.config.states)]
            for t in range(len(emissions) - 1):
                values_xi = [alpha[t][state] + math.log(self.transitions[state][next_state]) + emissions[t + 1][next_state]
                             + beta[t + 1][next_state] for state in range(self.config.states) for next_state in range(self.config.states)]
                normalizer, offset = _logsumexp(values_xi), 0
                for state in range(self.config.states):
                    for next_state in range(self.config.states):
                        transitions[state][next_state] += math.exp(values_xi[offset] - normalizer); offset += 1
            self.transitions = [[value / sum(row) for value in row] for row in transitions]
            for state in range(self.config.states):
                weights = [row[state] for row in gamma]; prior, current = values[:-1], values[1:]
                total = sum(weights); x = sum(weight * value for weight, value in zip(weights, prior)); y = sum(weight * value for weight, value in zip(weights, current))
                xx = sum(weight * value * value for weight, value in zip(weights, prior)); xy = sum(weight * left * right for weight, left, right in zip(weights, prior, current))
                denominator = total * xx - x * x
                phi = 0.0 if abs(denominator) < 1e-12 else max(-0.99, min(0.99, (total * xy - x * y) / denominator))
                intercept = (y - phi * x) / total
                variance = sum(weight * (actual - intercept - phi * prior_value) ** 2
                               for weight, prior_value, actual in zip(weights, prior, current)) / total
                self.phis[state], self.intercepts[state], self.variances[state] = phi, intercept, max(variance, self.config.minimum_variance)
            self.iterations, self.log_likelihood = iteration, likelihood
            if previous is not None and abs(likelihood - previous) <= self.config.convergence_tolerance:
                self.converged = True; break
            previous = likelihood
        return self

    def filter(self, values: Sequence[float]) -> list[list[float]]:
        alpha, _ = self._forward(self._emissions(values))
        return [[math.exp(value) for value in row] for row in alpha]


def _market_returns(data_dir: Path, manifest: Path, period: str) -> tuple[list[int], list[float]]:
    dataset = _read_msar_manifest(manifest)
    by_contract = {contract: {bar.ts: bar for bar in load_bars(data_dir, contract, period)} for contract in dataset["contracts"]}
    common = sorted(set.intersection(*(set(values) for values in by_contract.values())))
    if len(common) < 2:
        return [], []
    returns = [sum(math.log(by_contract[contract][common[index]].close / by_contract[contract][common[index - 1]].close)
                   for contract in dataset["contracts"]) / len(dataset["contracts"])
               for index in range(1, len(common))]
    return common[1:], returns


def _folds(size: int) -> list[tuple[str, int, int, int]]:
    train_end, validation_end = size // 2, size * 3 // 4
    if train_end < 48 or validation_end >= size:
        raise ValueError("insufficient high-timeframe observations for MS-AR walk-forward")
    return [("VALIDATION", 0, train_end, validation_end), ("OUT_OF_SAMPLE", 0, validation_end, size)]


def _mapping(model: MarkovSwitchingAr) -> dict[int, str]:
    transition = max(range(2), key=lambda state: model.variances[state])
    return {transition: "HIGH_VOL_TRANSITION", 1 - transition: "STABLE_CONTEXT"}


def build_report(data_dir: Path, manifest: Path, config: MsarConfig = MsarConfig()) -> dict:
    dataset = _read_msar_manifest(manifest)
    output = {"schema": SCHEMA, "datasetKey": dataset["datasetKey"], "modelName": MODEL_NAME, "modelVersion": MODEL_VERSION,
              "decisionBoundary": "RESEARCH", "datasetManifestSha256": hashlib.sha256(manifest.read_bytes()).hexdigest(),
              "config": asdict(config), "periods": []}
    for period in PERIODS:
        times, values = _market_returns(data_dir, manifest, period)
        if len(values) + 1 < config.min_source_bars:
            output["periods"].append({"period": period, "decision": "INSUFFICIENT_EVIDENCE", "sourceBars": len(values) + 1,
                                      "reason": f"requires at least {config.min_source_bars} source bars"}); continue
        folds = []
        for name, start, train_end, end in _folds(len(values)):
            model = MarkovSwitchingAr(config).fit(values[start:train_end])
            probabilities = model.filter(values[start:end])
            mapping = _mapping(model); evaluated = probabilities[train_end - start:]
            states = [max(range(2), key=lambda state: row[state]) for row in evaluated]
            counts = {label: sum(mapping[state] == label for state in states) for label in mapping.values()}
            shares = {label: count / len(states) for label, count in counts.items()}
            health = model.converged and max(shares.values()) <= .90 and min(shares.values()) >= .10
            folds.append({"name": name, "trainingBars": train_end - start, "evaluationBars": end - train_end,
                          "fit": {"iterations": model.iterations, "converged": model.converged, "logLikelihood": model.log_likelihood},
                          "stateMapping": {str(k): v for k, v in mapping.items()}, "stateShares": shares, "stateCounts": counts,
                          "stateParameters": {mapping[state]: {"intercept": model.intercepts[state], "ar1": model.phis[state],
                              "standardDeviation": math.sqrt(model.variances[state])} for state in range(2)},
                          "decision": "PASS" if health else "NEEDS_CALIBRATION"})
        output["periods"].append({"period": period, "decision": "PASS" if all(fold["decision"] == "PASS" for fold in folds) else "NEEDS_CALIBRATION",
                                  "sourceBars": len(values) + 1, "folds": folds})
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True); parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(); report = build_report(args.data_dir, args.manifest)
    args.out.parent.mkdir(parents=True, exist_ok=True); args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"out": str(args.out), "periods": [{"period": item["period"], "decision": item["decision"]} for item in report["periods"]]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
