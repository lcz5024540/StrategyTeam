"""核心回测运行器"""

from __future__ import annotations

import hashlib
import json
import math
import re
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
import yaml

from .adapters.htx.fees import create_fee_provider
from .conditions import C01DonchianEnsemble, C02ATRStop, DecisionContext
from .data import DatasetManager
from .data.dataset import require_timeframe
from .data.quality import (
    normalize_bad_tick_kind,
    require_quality_flag_columns,
    stop_on_bar_for_kind,
)
from .engine.backtest import CostScenario, SimBroker
from .engine.contract_value import coin_notional, periods_per_year, require_contract_size
from .engine.interfaces import Order
from .ops.stable_hash import stable_frame_hash
from .features import attach_funding_as_of, compute_all_features
from .features.funding_asof import apply_bar_settlements
from .features.indicators import FeatureCalculator, lift_valid_indicator, valid_feature_frame
from .context import compute_regime_labels
from .ledger import ExperimentRegistration, LedgerManager, RunRecord
from .ops import log_event, LogComponent, LogLevel, set_run_id, setup_logging
from .reports import ReportGenerator
from .risk import AccountLiquidationChecker, LiquidationChecker, PositionSizer
from .risk.position_sizing import SKIP_MIN_CONTRACT_EXCEEDS_RISK_BUDGET
from .strategy import Position, StateMachine, StrategyState
from .strategy_manifest import build_strategy_manifest, require_c02_enabled
from .version_info import get_version_info, load_package_manifest

IMPLEMENTED_CONDITIONS = frozenset({"C01", "C02"})
SMOKE_ID_RE = re.compile(r"^SMOKE-\d{8}-\d{2}$")
CANONICAL_DIFF_EXCLUDE = frozenset({
    "run_id",
    "parent_run_id",
    "registration",
    "stage_acceptance_approval_id",
    "strategy_manifest_sha256",
})
REQUIRED_RUN_FIELDS = (
    "dataset_id",
    "manifest_hash",
    "symbols",
    "split",
    "cost_scenario",
    "initial_equity",
    "risk_per_trade",
    "max_total_risk",
    "max_positions",
    "leverage_target",
)


def _is_unfrozen_placeholder(value: Any) -> bool:
    text = str(value or "").strip()
    if not text:
        return True
    upper = text.upper()
    return "TODO" in upper or "PLACEHOLDER" in upper


def _marked_equity(broker: Any) -> float:
    return float(broker.get_account_equity())


def require_signal_atr(metadata: dict[str, Any] | None) -> float:
    if not metadata or "signal_atr" not in metadata or metadata["signal_atr"] is None:
        raise ValueError(
            "ENTRY_PENDING missing metadata['signal_atr']; "
            "refusing to use the current (t+1) bar ATR"
        )
    atr = float(metadata["signal_atr"])
    if not math.isfinite(atr) or atr <= 0:
        raise ValueError(
            f"ENTRY_PENDING signal_atr={metadata['signal_atr']!r} is not usable; "
            "refusing to use the current (t+1) bar ATR"
        )
    return atr


def require_position_id(metadata: dict[str, Any] | None, *, context: str) -> str:
    position_id = (metadata or {}).get("position_id")
    if not position_id:
        raise ValueError(
            f"{context} missing metadata['position_id']; "
            "refusing to synthesize a position_id"
        )
    return str(position_id)


def annualized_sharpe_from_equity(equity: Any, ppy: float) -> float:
    series = pd.Series(equity, dtype="float64")
    rets = series.pct_change().dropna()
    if len(rets) < 2:
        return 0.0
    std = float(rets.std(ddof=1))
    if std <= 0:
        return 0.0
    return float(rets.mean() / std * (float(ppy) ** 0.5))


class BacktestRunner:
    def __init__(self, config_path: Path, workspace_dir: Path):
        self.config_path = config_path
        self.workspace_dir = workspace_dir

        with open(config_path) as f:
            self.config = yaml.safe_load(f) or {}

        self.run_id = self.config["run_id"]
        set_run_id(self.run_id)

        setup_logging(
            level=LogLevel.INFO,
            json_output=True,
            log_file=str(workspace_dir / "runs" / self.run_id / "run.log"),
        )

        self.ledger = LedgerManager(workspace_dir / "ledger.db")
        from .data.immutable_storage import ImmutableKlineStorage

        self.dataset_mgr = DatasetManager(
            workspace_dir / "data" / "datasets",
            storage=ImmutableKlineStorage(workspace_dir / "data" / "klines"),
            ledger=self.ledger,
            current_run_id=self.run_id,
        )
        self._strategy_version = get_version_info().version
        self._is_smoke = self._validate_run_type()

    def _validate_run_type(self) -> bool:
        run_type = self.config.get("run_type")
        if str(self.run_id).startswith("SMOKE-") and str(run_type or "").upper() != "SMOKE":
            raise ValueError("SMOKE- run_id requires run_type=SMOKE")
        if run_type is None or run_type == "BACKTEST":
            return False
        if run_type != "SMOKE":
            raise ValueError(f"Unsupported run_type {run_type!r}")
        if not SMOKE_ID_RE.match(str(self.run_id)):
            raise ValueError(
                f"SMOKE run_id must match SMOKE-YYYYMMDD-NN, got {self.run_id}"
            )
        split = self.config.get("split")
        if split != "train":
            raise ValueError("SMOKE runs may only use split=train")
        window = self.config.get("smoke_bar_window")
        if window is None:
            raise ValueError("SMOKE requires smoke_bar_window (e.g. 200)")
        if int(window) <= 0:
            raise ValueError("smoke_bar_window must be > 0")
        return True

    def run(self) -> dict[str, Any]:
        from ..arrow_runtime import configure_arrow

        configure_arrow()
        log_event(LogComponent.ENGINE, f"Starting backtest run: {self.run_id}")

        trial_id = str(uuid.uuid4())
        self._trial_id = trial_id
        family_id = self._determine_family_id()
        self._family_id = family_id
        self._changed_key = None
        self._require_run_fields()

        if not self._is_smoke:
            self.ledger.start_trial(
                trial_id=trial_id,
                run_id=self.run_id,
                parent_run_id=self.config.get("parent_run_id"),
                family_id=family_id,
                changed_key=None,
                status="started",
                started_at=datetime.now(timezone.utc),
            )

        results: dict[str, Any] | None = None
        status = "failed"
        error_message: str | None = None
        sharpe = None
        n_trades = 0
        return_series_path = None
        try:
            self._validate_conditions()
            self._validate_registration()

            git_commit = self._get_git_commit()
            strategy_manifest = build_strategy_manifest(
                self.config, git_commit=git_commit
            )
            if not self._is_smoke:
                expected = self.config.get("strategy_manifest_sha256")
                if not expected:
                    raise ValueError(
                        "strategy_manifest_sha256 is required for non-SMOKE runs"
                    )
                if expected != strategy_manifest["manifest_sha256"]:
                    raise ValueError(
                        "strategy_manifest_sha256 does not match the current "
                        "config+code manifest"
                    )

            data, frozen = self._load_dataset()
            environment = "SMOKE" if self._is_smoke else "BACKTEST"
            version = get_version_info()
            package_manifest = load_package_manifest()

            run_record = RunRecord(
                run_id=self.run_id,
                parent_run_id=self.config.get("parent_run_id"),
                git_commit=git_commit,
                dataset_hash=frozen.manifest_hash,
                environment=environment,
                config_snapshot=self.config,
                cost_scenario=self.config["cost_scenario"],
                created_at=datetime.now(timezone.utc),
                manifest_hash=self.config["manifest_hash"],
                strategy_manifest_sha256=strategy_manifest["manifest_sha256"],
                strategy_manifest=strategy_manifest,
                build_info_commit=version.commit,
                build_info_version=version.version,
                build_info_branch=version.branch,
                package_content_hash=package_manifest.get("content_hash"),
            )
            self.ledger.register_run(run_record)

            results = self._run_backtest(data, frozen)
            current_pnl = results["final_equity"] - results["initial_equity"]
            if self._is_smoke:
                self._record_smoke_outcome(results, status="completed")
            else:
                sharpe, n_trades, return_series_path = self._write_return_series(
                    results, frozen
                )
                results["sharpe"] = sharpe
                results["n_trades"] = n_trades
                self.ledger.update_run_metrics(
                    self.run_id,
                    {
                        "total_pnl": current_pnl,
                        "final_equity": results["final_equity"],
                        "initial_equity": results["initial_equity"],
                        "sharpe": sharpe,
                        "n_trades": n_trades,
                        "return_series_path": str(return_series_path)
                        if return_series_path
                        else None,
                    },
                )

            report_path = self._generate_report(results)
            log_event(
                LogComponent.ENGINE,
                f"Backtest complete: {self.run_id}",
                total_trades=len(results["fills"]),
                report=str(report_path),
            )
            status = "completed"
            return results
        except Exception as exc:
            error_message = str(exc)
            status = "failed"
            if self._is_smoke:
                self._record_smoke_outcome(results, status="failed", error_message=error_message)
            raise
        finally:
            self.close()
            if not self._is_smoke:
                pnl = None
                if results is not None:
                    pnl = results["final_equity"] - results["initial_equity"]
                self.ledger.finalize_trial(
                    trial_id=trial_id,
                    status=status,
                    completed_at=datetime.now(timezone.utc),
                    final_pnl=pnl,
                    final_sharpe=sharpe,
                    error_message=error_message,
                    changed_key=self._changed_key,
                    n_trades=n_trades,
                    return_series_path=str(return_series_path) if return_series_path else None,
                )

    def _require_run_fields(self) -> None:
        missing = [key for key in REQUIRED_RUN_FIELDS if key not in self.config]
        if missing:
            raise ValueError(
                "Config missing required fields: "
                + ", ".join(missing)
                + ". No silent defaults for risk/cost/sizing/data parameters."
            )
        if not self.config["manifest_hash"]:
            raise ValueError("manifest_hash is required; unverified dataset load is not allowed")
        if _is_unfrozen_placeholder(self.config.get("dataset_id")):
            raise ValueError(
                "dataset_id is a TODO placeholder; freeze the dataset first"
            )
        if _is_unfrozen_placeholder(self.config.get("manifest_hash")):
            raise ValueError(
                "manifest_hash is a TODO placeholder; freeze the dataset first"
            )
        if not self.config["cost_scenario"]:
            raise ValueError("cost_scenario is required")
        if not self.config["symbols"]:
            raise ValueError("symbols is required")
        if not self.config["split"]:
            raise ValueError("split is required")

    def _validate_conditions(self) -> None:
        conditions = self.config.get("conditions") or {}
        unknown = set(conditions) - IMPLEMENTED_CONDITIONS
        if unknown:
            raise ValueError(
                f"Unknown/unimplemented condition ids: {sorted(unknown)}. "
                f"Implemented: {sorted(IMPLEMENTED_CONDITIONS)}"
            )
        require_c02_enabled(self.config)

    def _determine_family_id(self) -> str:
        parent_id = self.config.get("parent_run_id")
        if not parent_id:
            return self.run_id
        current = parent_id
        seen: set[str] = set()
        while current and current not in seen:
            seen.add(current)
            parent_run = self.ledger.get_run(current)
            if not parent_run or not parent_run.parent_run_id:
                return current
            current = parent_run.parent_run_id
        return parent_id

    def _validate_registration(self) -> None:
        reg_config = self.config.get("registration", {})
        for field in ("hypothesis", "keep_rule", "drop_rule", "expected_effect", "change"):
            if not reg_config.get(field):
                raise ValueError(f"Registration incomplete: '{field}' is required")

        parent_run_id = self.config.get("parent_run_id")
        if parent_run_id:
            self._validate_single_change(parent_run_id)

        if self._changed_key is None:
            self._changed_key = "baseline" if not parent_run_id else reg_config["change"]

        trial_count = 0 if self._is_smoke else self.ledger.count_trials(self._family_id)
        exp = ExperimentRegistration(
            run_id=self.run_id,
            parent_run_id=parent_run_id,
            change=reg_config["change"],
            hypothesis=reg_config["hypothesis"],
            expected_effect=reg_config["expected_effect"],
            keep_rule=reg_config["keep_rule"],
            drop_rule=reg_config["drop_rule"],
            evidence_ref=reg_config.get("evidence_ref"),
            trial_count=trial_count,
            uses_holdout=self.config.get("split") == "test",
            conclusion=None,
            decision=None,
        )
        self.ledger.register_experiment(exp)

    def _canonical_config(self, config: dict[str, Any]) -> dict[str, Any]:
        return {key: value for key, value in config.items() if key not in CANONICAL_DIFF_EXCLUDE}

    def _changed_paths(self, parent: Any, current: Any, prefix: str = "") -> list[str]:
        if isinstance(parent, dict) and isinstance(current, dict):
            keys = set(parent) | set(current)
            changes: list[str] = []
            for key in sorted(keys):
                path = f"{prefix}.{key}" if prefix else str(key)
                if key not in parent or key not in current:
                    changes.append(path)
                else:
                    changes.extend(self._changed_paths(parent[key], current[key], path))
            return changes
        if parent != current:
            return [prefix or "<root>"]
        return []

    def _validate_single_change(self, parent_run_id: str) -> None:
        parent_run = self.ledger.get_run(parent_run_id)
        if not parent_run:
            raise ValueError(
                f"Parent run {parent_run_id} not found. "
                f"Cannot validate single-change requirement. "
                f"If this is a baseline run, remove parent_run_id from config."
            )
        changes = self._changed_paths(
            self._canonical_config(parent_run.config_snapshot),
            self._canonical_config(self.config),
        )
        if len(changes) == 0:
            raise ValueError(
                f"No changes detected vs parent {parent_run_id}. "
                f"Registration gate requires exactly ONE changed parameter."
            )
        if len(changes) == 1:
            self._changed_key = changes[0]
            log_event(
                LogComponent.ENGINE,
                f"Single change validated vs parent {parent_run_id}",
                changed_param=changes[0],
            )
            return
        raise ValueError(
            f"Multiple changes detected vs parent {parent_run_id}: {changes}. "
            f"Registration gate requires exactly ONE changed condition per run."
        )

    def _load_dataset(self) -> tuple[dict[str, pd.DataFrame], Any]:
        dataset_id = self.config["dataset_id"]
        symbols = self.config["symbols"]
        split = self.config["split"]
        if self._is_smoke and split == "test":
            raise ValueError("SMOKE runs cannot access the test split")
        approval_id = self.config.get("stage_acceptance_approval_id")
        if split == "test" and not approval_id:
            raise ValueError(
                "Test set access requires stage_acceptance_approval_id in config. "
                "Obtain approval first via CLI: approve --gate STAGE_ACCEPTANCE"
            )

        allow_missing_funding = bool(self.config.get("allow_missing_funding", False))
        data, frozen = self.dataset_mgr.load_dataset_with_verification(
            dataset_id,
            self.config["manifest_hash"],
            symbols,
            split=split,
            approval_id=approval_id,
            run_id=self.run_id,
            strategy_version=self._strategy_version,
            allow_missing_funding=allow_missing_funding,
            require_specs=True,
        )
        self._frozen = frozen

        processed_data = {}
        self._funding_missing = []
        self._funding_by_symbol: dict[str, pd.DataFrame] = {}
        verified_funding = getattr(frozen, "verified_funding_paths", {}) or {}
        for symbol, df in data.items():
            df = df.copy()
            require_quality_flag_columns(df, symbol=symbol)
            total_bars = len(df)
            non_comparable_count = df["non_comparable"].sum()
            bad_tick_count = df["bad_tick"].sum()
            external_count = df["external"].sum()
            df.loc[df["incomplete"] == True, "non_comparable"] = True
            df["_original_source"] = df.get("source", "htx")
            funding_path = verified_funding.get(symbol)
            if funding_path is not None:
                interval = require_timeframe(
                    getattr(frozen, "timeframe", None),
                    self.config.get("timeframe"),
                )
                funding_df = pd.read_parquet(funding_path)
                self._funding_by_symbol[symbol] = funding_df
                df = attach_funding_as_of(df, funding_df, interval)
            elif allow_missing_funding:
                self._funding_missing.append(symbol)
            else:
                raise ValueError(
                    f"Missing verified funding path for {symbol}. "
                    "Collect funding history or set allow_missing_funding=true"
                )
            df = compute_all_features(df)
            source = valid_feature_frame(df)
            atr_period = ((self.config.get("conditions") or {}).get("C02") or {}).get("atr_period")
            if atr_period:
                atr_col = f"atr{atr_period}"
                if atr_col not in df.columns:
                    atr_values = FeatureCalculator.atr(source, int(atr_period))
                    df[atr_col] = (
                        atr_values if source is df else lift_valid_indicator(atr_values, df.index)
                    )
            c01_periods = ((self.config.get("conditions") or {}).get("C01") or {}).get("periods") or []
            for period in c01_periods:
                high_col = f"donchian_high_{period}"
                low_col = f"donchian_low_{period}"
                if high_col not in df.columns:
                    high_values = FeatureCalculator.donchian_high(source["high"], int(period))
                    df[high_col] = (
                        high_values if source is df else lift_valid_indicator(high_values, df.index)
                    )
                if low_col not in df.columns:
                    low_values = FeatureCalculator.donchian_low(source["low"], int(period))
                    df[low_col] = (
                        low_values if source is df else lift_valid_indicator(low_values, df.index)
                    )
            df = compute_regime_labels(df)
            processed_data[symbol] = df
            log_event(
                LogComponent.DATA,
                f"Dataset loaded: {symbol}",
                split=split,
                total_bars=total_bars,
                non_comparable=int(non_comparable_count),
                bad_tick=int(bad_tick_count),
                external=int(external_count),
                usable_bars=len(df[~df["non_comparable"] & ~df["bad_tick"]]),
            )
        return processed_data, frozen

    def _get_git_commit(self) -> str:
        info = get_version_info()
        commit = (info.commit or "").strip()
        if not commit or commit == "unknown":
            if self._is_smoke:
                return "unknown"
            raise ValueError(
                "git_commit is unknown; refusing to run without git or _build_info"
            )
        return commit

    def _load_contract_spec(self, symbol: str, frozen: Any = None) -> dict[str, Any]:
        verified = getattr(frozen, "verified_spec_paths", None) or {}
        path = verified.get(symbol)
        if path is None:
            raise ValueError(
                f"Missing verified contract spec path for {symbol}. "
                "Collect via collect_contract_spec and freeze spec_files into the manifest"
            )
        spec = json.loads(Path(path).read_text())
        if "contract_size" not in spec or "min_order_size" not in spec:
            raise ValueError(f"Contract spec {path} missing contract_size/min_order_size")
        return spec

    def _run_backtest(
        self, data: dict[str, pd.DataFrame], frozen: Any
    ) -> dict[str, Any]:
        initial_equity = self.config["initial_equity"]
        cost_scenario = CostScenario(self.config["cost_scenario"])
        fee_config = self.config.get("fees") or {}
        fee_provider = create_fee_provider(
            use_authenticated=fee_config.get("use_authenticated", False),
            client=None,
            maker_rate=fee_config.get("maker_rate"),
            taker_rate=fee_config.get("taker_rate"),
        )
        specs = {symbol: self._load_contract_spec(symbol, frozen) for symbol in data}
        contract_sizes = {
            symbol: float(spec["contract_size"]) for symbol, spec in specs.items()
        }
        broker = SimBroker(
            initial_equity,
            cost_scenario,
            fee_provider=fee_provider,
            contract_sizes=contract_sizes,
        )
        self._broker = broker
        self._contract_sizes = contract_sizes
        sizer = PositionSizer(
            risk_per_trade_pct=self.config["risk_per_trade"],
            max_total_risk_pct=self.config["max_total_risk"],
            max_positions=self.config["max_positions"],
            target_leverage=self.config["leverage_target"],
        )

        liq_mode = self.config.get("liquidation_check_mode", "account")
        if liq_mode not in {"account", "per_position"}:
            raise ValueError("liquidation_check_mode must be 'account' or 'per_position'")
        account_liq = AccountLiquidationChecker()
        position_liq = LiquidationChecker() if liq_mode == "per_position" else None

        c01_config = (self.config.get("conditions") or {}).get("C01") or {}
        if "enabled" not in c01_config:
            raise ValueError("C01 config missing required field 'enabled'")
        if not c01_config["enabled"]:
            raise ValueError("C01 must be enabled (required entry condition)")
        if not c01_config.get("periods"):
            raise ValueError("C01 config missing required field 'periods'")
        if "threshold" not in c01_config:
            raise ValueError("C01 config missing required field 'threshold'")
        c01 = C01DonchianEnsemble(
            periods=c01_config["periods"],
            threshold=c01_config["threshold"],
        )

        c02_config = (self.config.get("conditions") or {}).get("C02") or {}
        if c02_config.get("enabled") is not True:
            raise ValueError("C02 must be declared with enabled: true")
        for field in ("atr_period", "atr_multiplier", "tick_size", "stop_slippage_ticks"):
            if field not in c02_config or c02_config[field] is None:
                raise ValueError(f"C02 config missing required field '{field}'")
        c02 = C02ATRStop(
            atr_period=c02_config["atr_period"],
            atr_multiplier=c02_config["atr_multiplier"],
            tick_size=c02_config["tick_size"],
            stop_slippage_ticks=c02_config["stop_slippage_ticks"],
        )
        atr_col = f"atr{c02.atr_period}"
        slippage_amount = c02.slippage_amount()

        state_machines = {symbol: StateMachine() for symbol in data}
        all_decisions = []
        equity_curve: list[dict[str, Any]] = []
        interval = require_timeframe(
            getattr(frozen, "timeframe", None),
            self.config.get("timeframe"),
        )

        from collections import defaultdict

        events: dict[pd.Timestamp, list[tuple[str, int]]] = defaultdict(list)
        for symbol, df in data.items():
            log_event(LogComponent.ENGINE, f"Processing symbol: {symbol}", bars=len(df))
            if self._is_smoke:
                start_idx = max(0, len(df) - int(self.config["smoke_bar_window"]))
            else:
                start_idx = 200
            times = pd.to_datetime(df["bar_time"], utc=True)
            for idx in range(start_idx, len(df)):
                events[times.iloc[idx]].append((symbol, idx))

        for bar_time in sorted(events):
            for symbol, idx in sorted(events[bar_time], key=lambda item: item[0]):
                df = data[symbol]
                spec = specs[symbol]
                bar = df.iloc[idx]
                incomplete = bar["incomplete"]
                non_comparable = bar["non_comparable"]
                is_bad_tick = bar["bad_tick"]
                kind = bar.get("bad_tick_kind")
                if is_bad_tick:
                    kind = normalize_bad_tick_kind(kind)
                skip_signals = incomplete or non_comparable or is_bad_tick
                check_stop = (not incomplete) and (
                    (not is_bad_tick)
                    or stop_on_bar_for_kind(kind, self.config.get("bad_tick_stop"))
                )
                if is_bad_tick:
                    allow_entry_fills = False
                    allow_exit_fills = stop_on_bar_for_kind(
                        kind, self.config.get("bad_tick_stop")
                    )
                elif incomplete:
                    allow_entry_fills = False
                    allow_exit_fills = False
                else:
                    allow_entry_fills = True
                    allow_exit_fills = True
                bar_payload = bar.to_dict()
                broker.process_next_bar(
                    symbol,
                    bar_payload,
                    allow_entry_fills=allow_entry_fills,
                    allow_exit_fills=allow_exit_fills,
                )
                funding_df = getattr(self, "_funding_by_symbol", {}).get(symbol)
                if funding_df is not None:
                    apply_bar_settlements(broker, symbol, bar_payload, interval, funding_df)
                sm = state_machines[symbol]
                current_state = sm.get_state(symbol)
                current_position = sm.get_position(symbol)
                ctx = DecisionContext(
                    symbol=symbol,
                    bar_time=bar["bar_time"],
                    df=df,
                    current_idx=idx,
                    current_position=None,
                )
                decision_record = {
                    "run_id": self.run_id,
                    "symbol": symbol,
                    "bar_time": bar["bar_time"],
                    "close": bar["close"],
                    "regime": bar.get("regime", "UNKNOWN"),
                    "state": current_state.value,
                    "action": "HOLD",
                    "eligible_for_entry": not skip_signals,
                }
                if skip_signals and not check_stop:
                    decision_record["skip_reason"] = "bad_tick" if is_bad_tick else "blocked_bar"
                    if is_bad_tick:
                        decision_record["bad_tick_kind"] = bar.get("bad_tick_kind")
                    all_decisions.append(decision_record)
                    continue

                if current_state == StrategyState.FLAT:
                    if skip_signals:
                        decision_record["action"] = "HOLD"
                        decision_record["skip_reason"] = "bad_tick" if is_bad_tick else "blocked_bar"
                        if is_bad_tick:
                            decision_record["bad_tick_kind"] = bar.get("bad_tick_kind")
                        all_decisions.append(decision_record)
                        continue
                    ctx.current_position = None
                    c01_result = c01.evaluate(ctx, {})
                    decision_record["c01_status"] = c01_result.status
                    decision_record["c01_value"] = c01_result.value
                    if c01_result.status == "PASS" and c01_result.value is not None:
                        side = "LONG" if c01_result.value > 0 else "SHORT"
                        if atr_col not in bar.index or pd.isna(bar[atr_col]):
                            raise ValueError(
                                f"signal bar missing {atr_col}; refusing to size/stop without ATR"
                            )
                        atr = float(bar[atr_col])
                        if not math.isfinite(atr) or atr <= 0:
                            raise ValueError(
                                f"signal bar {atr_col}={bar[atr_col]!r} is not usable; "
                                "refusing to size/stop without a finite positive ATR"
                            )
                        stop_loss = c02.calculate_stop_loss(bar["close"], atr, side)
                        existing_for_sizer = []
                        for existing in broker.get_positions():
                            sm_pos = state_machines[existing["symbol"]].get_position(
                                existing["symbol"]
                            )
                            if sm_pos is None or sm_pos.stop_loss is None:
                                raise ValueError(
                                    f"existing position {existing.get('symbol')} missing "
                                    "stop_loss; refusing to treat risk as 0"
                                )
                            entry = float(existing["entry_price"])
                            size = float(existing["size"])
                            contract_size = float(
                                specs[existing["symbol"]]["contract_size"]
                            )
                            notional = coin_notional(
                                entry, size, contract_size, existing["symbol"]
                            )
                            item = dict(existing)
                            item["stop_loss"] = float(sm_pos.stop_loss)
                            item["risk_amount"] = (
                                abs(entry - float(sm_pos.stop_loss)) / entry * notional
                            )
                            existing_for_sizer.append(item)
                        pos_size = sizer.calculate_position(
                            symbol=symbol,
                            side=side,
                            entry_price=bar["close"],
                            stop_loss=stop_loss,
                            account_equity=broker.get_account_equity(),
                            contract_size=float(spec["contract_size"]),
                            min_order_size=int(spec["min_order_size"]),
                            existing_positions=existing_for_sizer,
                        )
                        if pos_size.skip_reason:
                            decision_record["action"] = "SKIP"
                            decision_record["skip_reason"] = pos_size.skip_reason
                            decision_record["side"] = side
                            if pos_size.skip_reason == SKIP_MIN_CONTRACT_EXCEEDS_RISK_BUDGET:
                                close_px = float(bar["close"])
                                decision_record["signal_atr"] = atr
                                decision_record["atr_pct"] = atr / close_px
                                decision_record["one_contract_risk_usdt"] = (
                                    pos_size.one_contract_risk_usdt
                                )
                                decision_record["risk_budget_usdt"] = (
                                    pos_size.risk_budget_usdt
                                )
                                decision_record["eligible_for_entry"] = False
                            else:
                                decision_record["eligible_for_entry"] = True
                        else:
                            proposed = {
                                "symbol": symbol,
                                "side": side,
                                "size": pos_size.size,
                                "contract_size": float(spec["contract_size"]),
                                "entry_price": bar["close"],
                                "stop_loss": stop_loss,
                            }
                            strategy_positions = []
                            all_positions = []
                            for existing in broker.get_positions():
                                sm_pos = state_machines[existing["symbol"]].get_position(
                                    existing["symbol"]
                                )
                                item = dict(existing)
                                item["contract_size"] = float(
                                    specs[existing["symbol"]]["contract_size"]
                                )
                                if sm_pos:
                                    item["stop_loss"] = sm_pos.stop_loss
                                strategy_positions.append(item)
                                all_positions.append(item)
                            if position_liq is not None:
                                ok, _liq_dist, msg = position_liq.check_liquidation_distance(
                                    bar["close"], stop_loss, side, pos_size.leverage
                                )
                            else:
                                ok, _liq_dist, msg = account_liq.check_account_liquidation_risk(
                                    broker.get_account_equity(),
                                    all_positions + [proposed],
                                    strategy_positions + [proposed],
                                )
                            if ok and pos_size.size > 0:
                                position_id = (
                                    f"{symbol}:{pd.Timestamp(bar['bar_time']).isoformat()}:{side}"
                                )
                                c02.initialize_position_stop(position_id, stop_loss)
                                sm.signal_entry(
                                    symbol=symbol,
                                    side=side,
                                    size=pos_size.size,
                                    entry_price=bar["close"],
                                    entry_time=bar["bar_time"],
                                    stop_loss=stop_loss,
                                    leverage=pos_size.leverage,
                                    metadata={
                                        "position_id": position_id,
                                        "signal_atr": float(atr),
                                    },
                                )
                                broker.submit_order(
                                    Order(
                                        symbol=symbol,
                                        side=side,
                                        size=pos_size.size,
                                        order_type="MARKET",
                                        stop_slippage=slippage_amount,
                                        client_order_id=position_id,
                                    )
                                )
                                decision_record["action"] = "ENTER"
                                decision_record["eligible_for_entry"] = True
                                decision_record["side"] = side
                                decision_record["size"] = pos_size.size
                                decision_record["stop_loss"] = stop_loss
                                decision_record["position_id"] = position_id
                            else:
                                decision_record["action"] = "SKIP"
                                decision_record["skip_reason"] = msg

                elif current_state == StrategyState.ENTRY_PENDING:
                    current_position = sm.get_position(symbol)
                    if current_position is None:
                        raise ValueError(
                            f"ENTRY_PENDING for {symbol} has no position; refusing to continue"
                        )
                    position_id = require_position_id(
                        current_position.metadata, context="ENTRY_PENDING"
                    )
                    entry_fills = [
                        fill
                        for fill in broker.fills
                        if fill.is_entry
                        and getattr(fill, "position_id", None) == position_id
                    ]
                    if not entry_fills:
                        decision_record["action"] = "HOLD"
                        decision_record["skip_reason"] = "entry_unfilled"
                        decision_record["position_id"] = position_id
                        all_decisions.append(decision_record)
                        continue
                    actual_price = float(entry_fills[-1].price)
                    signal_atr = require_signal_atr(current_position.metadata)
                    fill_stop = c02.calculate_stop_loss(
                        actual_price, signal_atr, current_position.side
                    )
                    sm.confirm_entry(symbol, actual_entry_price=actual_price)
                    current_position = sm.get_position(symbol)
                    current_position.stop_loss = fill_stop
                    position_id = require_position_id(
                        current_position.metadata, context="CONFIRM_ENTRY"
                    )
                    c02.initialize_position_stop(position_id, fill_stop)
                    ctx.current_position = {
                        "entry_price": actual_price,
                        "side": current_position.side,
                        "position_id": position_id,
                        "stop_loss": fill_stop,
                    }
                    c02_result = c02.evaluate(ctx, {})
                    decision_record["action"] = "CONFIRM_ENTRY"
                    decision_record["stop_loss"] = fill_stop
                    decision_record["entry_price"] = actual_price
                    decision_record["position_id"] = position_id
                    if c02_result.status == "PASS":
                        sm.signal_exit(symbol, "stop_loss")
                        broker.submit_order(
                            Order(
                                symbol=symbol,
                                side="SHORT" if current_position.side == "LONG" else "LONG",
                                size=current_position.size,
                                order_type="MARKET",
                                reduce_only=True,
                                is_stop=True,
                                stop_price=fill_stop,
                                stop_slippage=slippage_amount,
                                client_order_id=position_id,
                            )
                        )
                        decision_record["action"] = "EXIT"
                        decision_record["exit_reason"] = "stop_loss"
                        decision_record["entry_bar_stop"] = True

                elif current_state == StrategyState.OPEN:
                    if current_position:
                        position_id = require_position_id(
                            current_position.metadata, context=f"OPEN {symbol}"
                        )
                        ctx.current_position = {
                            "entry_price": current_position.entry_price,
                            "side": current_position.side,
                            "position_id": position_id,
                            "stop_loss": current_position.stop_loss,
                        }
                        c02_result = c02.evaluate(ctx, {})
                        decision_record["c02_status"] = c02_result.status
                        decision_record["c02_value"] = c02_result.value
                        decision_record["position_id"] = position_id
                        if position_id not in c02._position_stops:
                            raise ValueError(
                                f"C02 has no stop for {position_id}; "
                                "refusing to fall back to position.stop_loss"
                            )
                        decision_record["stop_loss"] = c02._position_stops[position_id]
                        if c02_result.status == "PASS":
                            sm.signal_exit(symbol, "stop_loss")
                            broker.submit_order(
                                Order(
                                    symbol=symbol,
                                    side="SHORT" if current_position.side == "LONG" else "LONG",
                                    size=current_position.size,
                                    order_type="MARKET",
                                    reduce_only=True,
                                    is_stop=True,
                                    stop_price=c02._position_stops[position_id],
                                    stop_slippage=slippage_amount,
                                    client_order_id=position_id,
                                )
                            )
                            decision_record["action"] = "EXIT"
                            decision_record["exit_reason"] = "stop_loss"

                elif current_state == StrategyState.EXIT_PENDING:
                    closed_pos = sm.confirm_exit(symbol, bar["close"], bar["bar_time"])
                    if closed_pos:
                        position_id = require_position_id(
                            closed_pos.metadata, context="CONFIRM_EXIT"
                        )
                        c02.reset_position_stop(position_id)
                        decision_record["action"] = "CONFIRM_EXIT"
                        decision_record["position_id"] = position_id

                all_decisions.append(decision_record)

            equity_curve.append(
                {
                    "bar_time": bar_time,
                    "equity": _marked_equity(broker),
                }
            )

        decisions_df = pd.DataFrame(all_decisions)
        fee_rates_record = {}
        for symbol, rates in broker.fee_rates_used.items():
            fee_rates_record[symbol] = rates.to_dict()
        return {
            "fills": broker.fills,
            "decisions": decisions_df,
            "final_equity": broker.get_account_equity(),
            "initial_equity": initial_equity,
            "fee_rates_used": fee_rates_record,
            "funding_missing": list(getattr(self, "_funding_missing", [])),
            "funding_events": list(broker.funding_events),
            "equity_curve": equity_curve,
        }

    def _fills_frame(self, fills: list[Any]) -> pd.DataFrame:
        if not fills:
            return pd.DataFrame()
        return pd.DataFrame(
            [
                {
                    "symbol": fill.symbol,
                    "side": fill.side,
                    "size": fill.size,
                    "price": fill.price,
                    "time": fill.time,
                    "fees": fill.fees,
                    "slippage": fill.slippage,
                    "is_entry": fill.is_entry,
                    "contract_size": fill.contract_size,
                    "notional": fill.notional,
                    "funding": fill.funding,
                    "position_id": getattr(fill, "position_id", None),
                    "client_order_id": getattr(fill, "client_order_id", None),
                }
                for fill in fills
            ]
        )

    def _hash_decisions(self, decisions: pd.DataFrame | None) -> str:
        if decisions is None:
            return "unavailable"
        return stable_frame_hash(decisions)

    def _hash_fills(self, fills: list[Any] | None) -> str:
        if fills is None:
            return "unavailable"
        return stable_frame_hash(self._fills_frame(fills))

    def _record_smoke_outcome(
        self,
        results: dict[str, Any] | None,
        status: str,
        error_message: str | None = None,
    ) -> None:
        decisions = results["decisions"] if results else None
        fills = results["fills"] if results else None
        self.ledger.record_smoke_run(
            run_id=self.run_id,
            dataset_id=self.config.get("dataset_id"),
            manifest_hash=self.config.get("manifest_hash"),
            bar_window=int(self.config.get("smoke_bar_window") or 0),
            decisions_hash=self._hash_decisions(decisions),
            fills_hash=self._hash_fills(fills),
            status=status,
            error_message=error_message,
        )

    def close(self) -> None:
        broker = getattr(self, "_broker", None)
        if broker is not None:
            broker.close()
            self._broker = None

    def _write_return_series(
        self, results: dict[str, Any], frozen: Any
    ) -> tuple[float | None, int, Path | None]:
        output_dir = self.workspace_dir / "runs" / self.run_id
        output_dir.mkdir(parents=True, exist_ok=True)
        fills = results["fills"]
        trades: list[dict[str, Any]] = []
        open_fills: dict[str, Any] = {}
        for fill in fills:
            if fill.is_entry:
                open_fills[fill.symbol] = fill
                continue
            entry = open_fills.pop(fill.symbol, None)
            if entry is None:
                continue
            contract_size = require_contract_size(
                getattr(entry, "contract_size", None), entry.symbol
            )
            entry_notional = coin_notional(
                entry.price, entry.size, contract_size, entry.symbol
            )
            if entry.side == "LONG":
                price_pnl = coin_notional(
                    fill.price - entry.price, entry.size, contract_size, entry.symbol
                )
            else:
                price_pnl = coin_notional(
                    entry.price - fill.price, entry.size, contract_size, entry.symbol
                )
            costs = (
                float(entry.fees)
                + float(fill.fees)
                + float(entry.slippage)
                + float(fill.slippage)
                + float(getattr(fill, "funding", 0.0) or 0.0)
            )
            net_pnl = price_pnl - costs
            ret = net_pnl / entry_notional if entry_notional else 0.0
            trades.append(
                {
                    "symbol": fill.symbol,
                    "entry_time": entry.time,
                    "exit_time": fill.time,
                    "side": entry.side,
                    "return": ret,
                    "net_pnl": net_pnl,
                    "fees": float(entry.fees) + float(fill.fees),
                    "funding": float(getattr(fill, "funding", 0.0) or 0.0),
                }
            )
        n_trades = len(trades)
        path = output_dir / "return_series.parquet"
        ppy = periods_per_year(
            require_timeframe(
                getattr(frozen, "timeframe", None),
                self.config.get("timeframe"),
            )
        )
        columns = [
            "symbol",
            "entry_time",
            "exit_time",
            "side",
            "return",
            "net_pnl",
            "fees",
            "funding",
        ]
        curve = pd.DataFrame(results.get("equity_curve") or [])
        sharpe = (
            annualized_sharpe_from_equity(curve["equity"], ppy)
            if not curve.empty
            else None
        )
        if trades:
            df = pd.DataFrame(trades)
            df.to_parquet(path, index=False)
            return sharpe, n_trades, path
        pd.DataFrame(columns=columns).to_parquet(path, index=False)
        return sharpe, 0, path

    def _load_stitch_points(self) -> dict[str, Any] | None:
        frozen = getattr(self, "_frozen", None)
        points = getattr(frozen, "stitch_points", None) if frozen is not None else None
        if points:
            return points
        return self.config.get("stitch_points")

    def _load_overlap_deviation(self) -> dict[str, Any] | None:
        frozen = getattr(self, "_frozen", None)
        aux = getattr(frozen, "verified_aux_paths", None) or {}
        path = aux.get("overlap_deviation")
        if path is not None:
            return json.loads(Path(path).read_text())
        return self.config.get("overlap_deviation")

    def _generate_report(self, results: dict[str, Any]) -> Path:
        output_dir = self.workspace_dir / "runs" / self.run_id
        output_dir.mkdir(parents=True, exist_ok=True)

        fills_df = self._fills_frame(results["fills"])
        fills_data = fills_df.to_dict(orient="records") if not fills_df.empty else []
        if not fills_df.empty:
            fills_df.to_parquet(output_dir / "fills.parquet", index=False)
        decisions_df = results["decisions"]
        if not decisions_df.empty:
            decisions_df.to_parquet(output_dir / "decisions.parquet", index=False)

        generator = ReportGenerator(self.run_id, output_dir)
        registration = self.ledger.get_registration(self.run_id)
        parent_comparison = None
        parent_run_id = self.config.get("parent_run_id")
        if parent_run_id:
            parent_run = self.ledger.get_run(parent_run_id)
            if parent_run:
                current_pnl = results["final_equity"] - results["initial_equity"]
                parent_metrics = parent_run.metrics or {}
                parent_pnl = parent_metrics.get("total_pnl")
                if parent_pnl is None:
                    parent_comparison = {
                        "parent_run_id": parent_run.run_id,
                        "changed_key": self._changed_key,
                        "current_pnl": current_pnl,
                        "parent_pnl": None,
                        "pnl_diff": None,
                        "pct_change": None,
                    }
                else:
                    parent_pnl = float(parent_pnl)
                    pnl_diff = current_pnl - parent_pnl
                    pct_change = (
                        (pnl_diff / abs(parent_pnl) * 100.0) if parent_pnl != 0 else None
                    )
                    parent_comparison = {
                        "parent_run_id": parent_run.run_id,
                        "changed_key": self._changed_key
                        or self.config.get("registration", {}).get("change", "N/A"),
                        "change": self.config.get("registration", {}).get("change", "N/A"),
                        "current_pnl": current_pnl,
                        "parent_pnl": parent_pnl,
                        "pnl_diff": pnl_diff,
                        "pct_change": pct_change,
                    }

        return generator.generate_report(
            config=self.config,
            fills=fills_data,
            decisions=decisions_df,
            parent_comparison=parent_comparison,
            registration=registration,
            overlap_deviation=self._load_overlap_deviation(),
            smoke=self._is_smoke,
            funding_missing=results.get("funding_missing") or [],
            stitch_points=self._load_stitch_points(),
            funding_events=list(results.get("funding_events") or []),
            initial_equity=results.get("initial_equity"),
            final_equity=results.get("final_equity"),
        )
