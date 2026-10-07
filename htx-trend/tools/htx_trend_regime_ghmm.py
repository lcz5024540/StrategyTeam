#!/usr/bin/env python3
"""Offline Gaussian-HMM market-regime research on a COMPLETE frozen HTX dataset.

This tool never contacts HTX, reads credentials, writes a database, or imports the
live strategy. It produces reproducible market-state evidence only. Structure
models remain responsible for entry, invalidation and target prices.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from dataclasses import dataclass
from pathlib import Path
from statistics import mean, pstdev
from typing import Dict, Iterable, List, Sequence, Tuple

from htx_trend_backtest import Bar, load_bars

SCHEMA = "htx-trend-ghmm-regime-report-v1"
MODEL_NAME = "GHMM_MARKET_REGIME"
MODEL_VERSION = "ghmm-market-regime-v1"
ENTRY_PERIOD = "15min"
FEATURE_NAMES = ("market_log_return", "realized_volatility", "long_breadth")


@dataclass(frozen=True)
class GhmmConfig:
    states: int = 4
    max_iterations: int = 60
    convergence_tolerance: float = 1e-5
    minimum_variance: float = 1e-4
    transition_pseudocount: float = 1e-3
    volatility_window: int = 16
    breadth_window: int = 8


def _read_manifest(path: Path) -> dict:
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("schema") != "htx-trend-research-dataset-manifest-v1":
        raise ValueError("unsupported research manifest schema")
    if manifest.get("inputIntegrity") != "COMPLETE":
        raise ValueError("GHMM research requires a COMPLETE frozen dataset")
    if manifest.get("sourceKind") != "FROZEN_PUBLIC_KLINE":
        raise ValueError("GHMM research requires FROZEN_PUBLIC_KLINE input")
    contracts = manifest.get("contracts") or []
    if not contracts:
        raise ValueError("manifest has no contracts")
    inputs = manifest.get("inputs") or []
    for contract in contracts:
        matching = [row for row in inputs if row.get("contract") == contract and row.get("period") == ENTRY_PERIOD]
        if len(matching) != 1 or matching[0].get("sourceKind") != "RAW_PUBLIC" or matching[0].get("coverage") != "COMPLETE":
            raise ValueError("manifest requires one COMPLETE RAW_PUBLIC 15min input per contract")
    return manifest


def _manifest_digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _safe_log_return(previous: float, current: float) -> float:
    if previous <= 0 or current <= 0:
        raise ValueError("close prices must be positive")
    return math.log(current / previous)


def market_feature_rows(data_dir: Path, manifest_path: Path, config: GhmmConfig) -> List[dict]:
    """Build causal market-level 15m features from a frozen cross-section."""
    manifest = _read_manifest(manifest_path)
    bars_by_contract: Dict[str, Dict[int, Bar]] = {}
    for contract in manifest["contracts"]:
        bars = load_bars(data_dir, contract, ENTRY_PERIOD)
        bars_by_contract[contract] = {bar.ts: bar for bar in bars}
    common_times = sorted(set.intersection(*(set(values) for values in bars_by_contract.values())))
    minimum = max(config.volatility_window, config.breadth_window) + 1
    if len(common_times) < minimum:
        raise ValueError("frozen market series is too short for GHMM features")

    rows: List[dict] = []
    market_returns: List[float] = []
    for index in range(1, len(common_times)):
        timestamp = common_times[index]
        previous_timestamp = common_times[index - 1]
        returns = [_safe_log_return(bars_by_contract[contract][previous_timestamp].close,
                                    bars_by_contract[contract][timestamp].close)
                   for contract in manifest["contracts"]]
        market_return = sum(returns) / len(returns)
        market_returns.append(market_return)
        if len(market_returns) < config.volatility_window or index < config.breadth_window:
            continue
        volatility = pstdev(market_returns[-config.volatility_window:])
        breadth_returns = [_safe_log_return(
            bars_by_contract[contract][common_times[index - config.breadth_window]].close,
            bars_by_contract[contract][timestamp].close)
            for contract in manifest["contracts"]]
        long_breadth = sum(value > 0 for value in breadth_returns) / len(breadth_returns)
        rows.append({"barTime": timestamp, "features": [market_return, volatility, long_breadth]})
    if not rows:
        raise ValueError("no GHMM feature rows after causal warm-up")
    return rows


def _logsumexp(values: Iterable[float]) -> float:
    values = list(values)
    maximum = max(values)
    if not math.isfinite(maximum):
        return maximum
    return maximum + math.log(sum(math.exp(value - maximum) for value in values))


def _normalize_log(values: Sequence[float]) -> List[float]:
    normalizer = _logsumexp(values)
    return [value - normalizer for value in values]


def _standardize(training: Sequence[Sequence[float]], values: Sequence[Sequence[float]]) -> Tuple[List[List[float]], List[float], List[float]]:
    width = len(training[0])
    centers = [mean(row[column] for row in training) for column in range(width)]
    scales = [max(pstdev(row[column] for row in training), 1e-9) for column in range(width)]
    return [[(row[column] - centers[column]) / scales[column] for column in range(width)] for row in values], centers, scales


class DiagonalGaussianHmm:
    """Small deterministic diagonal-covariance GHMM for offline research.

    Keeping the implementation local makes its parameters, causality and version
    inspectable. It is not a production inference dependency.
    """

    def __init__(self, config: GhmmConfig):
        self.config = config
        self.start: List[float] = []
        self.transitions: List[List[float]] = []
        self.means: List[List[float]] = []
        self.variances: List[List[float]] = []
        self.log_likelihood: float = float("-inf")
        self.iterations: int = 0
        self.converged: bool = False

    def _initialize(self, rows: Sequence[Sequence[float]]) -> None:
        if len(rows) < self.config.states * 8:
            raise ValueError("GHMM training sample is too short for configured state count")
        width = len(rows[0])
        ordered = sorted(range(len(rows)), key=lambda index: rows[index][0])
        groups: List[List[Sequence[float]]] = [[] for _ in range(self.config.states)]
        for rank, index in enumerate(ordered):
            groups[min(self.config.states - 1, rank * self.config.states // len(rows))].append(rows[index])
        self.means = [[mean(row[column] for row in group) for column in range(width)] for group in groups]
        self.variances = [[max(pstdev(row[column] for row in group) ** 2, self.config.minimum_variance)
                           for column in range(width)] for group in groups]
        self.start = [1.0 / self.config.states] * self.config.states
        stay = 0.82
        move = (1 - stay) / (self.config.states - 1)
        self.transitions = [[stay if row == column else move for column in range(self.config.states)]
                            for row in range(self.config.states)]

    def _emission_log_probability(self, row: Sequence[float], state: int) -> float:
        return sum(-0.5 * (math.log(2 * math.pi * variance) + ((value - center) ** 2) / variance)
                   for value, center, variance in zip(row, self.means[state], self.variances[state]))

    def _forward(self, rows: Sequence[Sequence[float]]) -> Tuple[List[List[float]], float]:
        first = _normalize_log([math.log(self.start[state]) + self._emission_log_probability(rows[0], state)
                                for state in range(self.config.states)])
        alpha = [first]
        cumulative = _logsumexp([math.log(self.start[state]) + self._emission_log_probability(rows[0], state)
                                 for state in range(self.config.states)])
        for row in rows[1:]:
            raw = [self._emission_log_probability(row, state) + _logsumexp(
                alpha[-1][previous] + math.log(self.transitions[previous][state])
                for previous in range(self.config.states)) for state in range(self.config.states)]
            step = _logsumexp(raw)
            alpha.append([value - step for value in raw])
            cumulative += step
        return alpha, cumulative

    def _backward(self, rows: Sequence[Sequence[float]]) -> List[List[float]]:
        beta = [[0.0] * self.config.states for _ in rows]
        for time in range(len(rows) - 2, -1, -1):
            next_row = rows[time + 1]
            # Each row is independently normalized. This avoids underflow while
            # preserving posterior ratios used by Baum-Welch.
            values = []
            for state in range(self.config.states):
                values.append(_logsumexp(
                    math.log(self.transitions[state][next_state])
                    + self._emission_log_probability(next_row, next_state)
                    + beta[time + 1][next_state]
                    for next_state in range(self.config.states)))
            normalizer = _logsumexp(values)
            beta[time] = [value - normalizer for value in values]
        return beta

    def fit(self, rows: Sequence[Sequence[float]]) -> "DiagonalGaussianHmm":
        self._initialize(rows)
        previous_likelihood = None
        for iteration in range(1, self.config.max_iterations + 1):
            alpha, likelihood = self._forward(rows)
            beta = self._backward(rows)
            gamma = []
            for time in range(len(rows)):
                gamma.append([math.exp(value) for value in _normalize_log(
                    [alpha[time][state] + beta[time][state] for state in range(self.config.states)])])
            starts = [gamma[0][state] + self.config.transition_pseudocount for state in range(self.config.states)]
            self.start = [value / sum(starts) for value in starts]
            transitions = [[self.config.transition_pseudocount for _ in range(self.config.states)]
                           for _ in range(self.config.states)]
            for time in range(len(rows) - 1):
                log_xi = []
                for state in range(self.config.states):
                    for next_state in range(self.config.states):
                        log_xi.append(alpha[time][state] + math.log(self.transitions[state][next_state])
                                      + self._emission_log_probability(rows[time + 1], next_state)
                                      + beta[time + 1][next_state])
                normalizer = _logsumexp(log_xi)
                offset = 0
                for state in range(self.config.states):
                    for next_state in range(self.config.states):
                        transitions[state][next_state] += math.exp(log_xi[offset] - normalizer)
                        offset += 1
            self.transitions = [[value / sum(row) for value in row] for row in transitions]
            width = len(rows[0])
            occupancies = [sum(gamma[time][state] for time in range(len(rows))) + self.config.transition_pseudocount
                           for state in range(self.config.states)]
            self.means = [[sum(gamma[time][state] * rows[time][column] for time in range(len(rows))) / occupancies[state]
                           for column in range(width)] for state in range(self.config.states)]
            self.variances = [[max(self.config.minimum_variance,
                                   sum(gamma[time][state] * (rows[time][column] - self.means[state][column]) ** 2
                                       for time in range(len(rows))) / occupancies[state])
                               for column in range(width)] for state in range(self.config.states)]
            self.log_likelihood = likelihood
            self.iterations = iteration
            if previous_likelihood is not None and abs(likelihood - previous_likelihood) <= self.config.convergence_tolerance:
                self.converged = True
                break
            previous_likelihood = likelihood
        return self

    def filter_probabilities(self, rows: Sequence[Sequence[float]]) -> List[List[float]]:
        alpha, _ = self._forward(rows)
        return [[math.exp(value) for value in row] for row in alpha]


def state_mapping(model: DiagonalGaussianHmm) -> Dict[int, str]:
    """Map arbitrary HMM indexes to unique business labels by fitted behavior."""
    high_volatility = max(range(model.config.states), key=lambda state: model.means[state][1])
    remaining = [state for state in range(model.config.states) if state != high_volatility]
    trend_up = max(remaining, key=lambda state: model.means[state][0])
    remaining.remove(trend_up)
    trend_down = min(remaining, key=lambda state: model.means[state][0])
    remaining.remove(trend_down)
    mapping = {high_volatility: "HIGH_VOL_RISK", trend_up: "TREND_UP", trend_down: "TREND_DOWN"}
    mapping[remaining[0]] = "RANGE"
    return mapping


def _fold_boundaries(size: int) -> List[Tuple[str, int, int, int]]:
    train_end = size // 2
    validation_end = (size * 3) // 4
    if train_end < 64 or validation_end >= size:
        raise ValueError("dataset is too short for train/validation/out-of-sample GHMM folds")
    return [("VALIDATION", 0, train_end, validation_end), ("OUT_OF_SAMPLE", 0, validation_end, size)]


def _state_parameters(model: DiagonalGaussianHmm, mapping: Dict[int, str], centers: Sequence[float], scales: Sequence[float]) -> dict:
    """Expose fitted state behavior in original feature units for audit."""
    values = {}
    for state, label in mapping.items():
        values[label] = {
            "mean": {FEATURE_NAMES[index]: model.means[state][index] * scales[index] + centers[index]
                     for index in range(len(FEATURE_NAMES))},
            "standardDeviation": {FEATURE_NAMES[index]: math.sqrt(model.variances[state][index]) * scales[index]
                                  for index in range(len(FEATURE_NAMES))}
        }
    return values


def _state_health(state_counts: Dict[str, int], total: int) -> dict:
    shares = {state: count / total for state, count in state_counts.items()}
    maximum = max(shares.values())
    minimum = min(shares.values())
    return {
        "stateShares": shares,
        "maxStateShare": maximum,
        "minStateShare": minimum,
        "decision": "PASS" if maximum <= 0.50 and minimum >= 0.05 else "NEEDS_CALIBRATION",
        "reason": "all states remain materially represented" if maximum <= 0.50 and minimum >= 0.05
                  else "one state dominates more than 50% or another has less than 5% of evaluated bars"
    }


def _probability_record(row: dict, probability: Sequence[float], mapping: Dict[int, str], model: DiagonalGaussianHmm) -> dict:
    probabilities = {mapping[state]: probability[state] for state in range(model.config.states)}
    ordered = sorted(probabilities, key=probabilities.get, reverse=True)
    current = ordered[0]
    current_index = next(index for index, value in mapping.items() if value == current)
    transition = {mapping[state]: model.transitions[current_index][state] for state in range(model.config.states)}
    return {"barTime": row["barTime"], "state": current, "stateProbability": probabilities[current],
            "regimeProbabilities": probabilities, "transitionProbability": transition,
            "featureValues": dict(zip(FEATURE_NAMES, row["features"]))}


def build_report(data_dir: Path, manifest_path: Path, config: GhmmConfig = GhmmConfig()) -> dict:
    manifest = _read_manifest(manifest_path)
    rows = market_feature_rows(data_dir, manifest_path, config)
    values = [row["features"] for row in rows]
    output = {"schema": SCHEMA, "datasetKey": manifest["datasetKey"], "inputIntegrity": "COMPLETE",
              "modelName": MODEL_NAME, "modelVersion": MODEL_VERSION, "manifestSha256": _manifest_digest(manifest_path),
              "decisionBoundary": "RESEARCH", "featureContract": {"entryPeriod": ENTRY_PERIOD,
              "features": list(FEATURE_NAMES), "volatilityWindowBars": config.volatility_window,
              "breadthWindowBars": config.breadth_window, "causality": "each feature uses the completed bar and earlier bars only"},
              "config": {"states": config.states, "maxIterations": config.max_iterations,
                         "minimumVariance": config.minimum_variance, "transitionPseudocount": config.transition_pseudocount},
              "folds": []}
    for name, start, train_end, end in _fold_boundaries(len(rows)):
        standardized, centers, scales = _standardize(values[start:train_end], values[start:end])
        model = DiagonalGaussianHmm(config).fit(standardized[:train_end - start])
        probabilities = model.filter_probabilities(standardized)
        mapping = state_mapping(model)
        evaluated = [_probability_record(rows[start + index], probabilities[index], mapping, model)
                     for index in range(train_end - start, end - start)]
        state_counts = {label: sum(record["state"] == label for record in evaluated)
                        for label in sorted(set(mapping.values()))}
        output["folds"].append({"name": name, "trainingBars": train_end - start,
                                "evaluationBars": end - train_end,
                                "trainingRange": {"startBarTime": rows[start]["barTime"], "endBarTime": rows[train_end - 1]["barTime"]},
                                "evaluationRange": {"startBarTime": rows[train_end]["barTime"], "endBarTime": rows[end - 1]["barTime"]},
                                "standardization": {"centers": dict(zip(FEATURE_NAMES, centers)), "scales": dict(zip(FEATURE_NAMES, scales))},
                                "stateMapping": {str(state): label for state, label in mapping.items()},
                                "transitionMatrix": {mapping[state]: {mapping[target]: model.transitions[state][target]
                                                      for target in range(config.states)} for state in range(config.states)},
                                "stateCounts": state_counts, "stateHealth": _state_health(state_counts, len(evaluated)),
                                "stateParameters": _state_parameters(model, mapping, centers, scales),
                                "fit": {"iterations": model.iterations, "converged": model.converged,
                                        "logLikelihood": model.log_likelihood},
                                "records": evaluated})
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    report = build_report(args.data_dir, args.manifest)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"out": str(args.out), "datasetKey": report["datasetKey"],
                      "folds": [{"name": item["name"], "states": item["stateCounts"]} for item in report["folds"]]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
