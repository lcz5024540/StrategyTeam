"""Pure trend-exit-v5 kernel. It has no clock, storage, network, or order access."""
from __future__ import annotations

from dataclasses import replace
from decimal import Decimal, ROUND_DOWN

from .exit_contract_v5 import (
    EntryThesisV5, EventKind, ExecutionState, ExitActionV5, ExitDecisionV5,
    ExitEventV5, ExitPolicyV5, ExitStateV5, ExitTransitionV5, ModelState,
    ProfitPhase, Side, ZERO, canonical_hash,
)

INDEPENDENT_SOFT_GROUPS = frozenset({"E-H", "E-B", "E-F"})


def decide_v5(contract: EntryThesisV5, prior: ExitStateV5, event: ExitEventV5,
              policy: ExitPolicyV5 = ExitPolicyV5()) -> ExitTransitionV5:
    _validate(contract, prior, event, policy)
    input_hash = canonical_hash({"contract": contract, "state": prior, "event": event, "policy": policy})
    groups = tuple(sorted(set(event.evidence_groups)))

    # Never infer a close quantity when private position ownership is unknown.
    if not event.ownership_confirmed:
        state = replace(prior, revision=prior.revision + 1, execution_state=ExecutionState.UNKNOWN)
        return _result(state, event, ExitActionV5.RECONCILE_REQUIRED,
                       event.confirmed_remaining_contracts, ("OWNERSHIP_UNCERTAIN",), groups, input_hash)
    # A verified owner and reference price keep hard risk live even when optional
    # soft evidence is degraded.
    if _hard_stop(contract.side, event.reference_price, contract.immutable_initial_stop):
        state = replace(prior, revision=prior.revision + 1)
        return _result(state, event, ExitActionV5.FULL_CLOSE, 0, ("INITIAL_HARD_STOP",), groups, input_hash)
    if event.data_quality != "COMPLETE":
        state = replace(prior, revision=prior.revision + 1, execution_state=ExecutionState.UNKNOWN)
        return _result(state, event, ExitActionV5.RECONCILE_REQUIRED,
                       event.confirmed_remaining_contracts, ("DATA_QUALITY_INCOMPLETE",), groups, input_hash)
    if event.kind is EventKind.TICK:
        state = replace(prior, revision=prior.revision + 1)
        return _result(state, event, ExitActionV5.HOLD, event.confirmed_remaining_contracts,
                       ("AWAIT_CLOSED_BAR",), groups, input_hash)
    if not _eligible_closed_bar(contract, prior, event, policy):
        return _result(prior, event, ExitActionV5.HOLD, event.confirmed_remaining_contracts,
                       ("INELIGIBLE_OR_DUPLICATE_BAR",), groups, input_hash)

    # A boundary calculated on the previous eligible event becomes active now.
    active = prior.pending_soft_boundary if prior.pending_boundary_id is not None else prior.active_soft_boundary
    active_id = prior.pending_boundary_id if prior.pending_boundary_id is not None else prior.active_boundary_id
    close = event.close_price
    assert close is not None
    best = close if prior.best_close is None else (
        max(prior.best_close, close) if contract.side is Side.LONG else min(prior.best_close, close)
    )
    eligible = prior.eligible_bars + 1
    failure_bars = prior.structure_failure_bars + 1 if event.model_state is ModelState.INVALID else 0
    breach = prior.soft_breach_bars + 1 if _crossed(contract.side, close, active) else 0
    state = replace(prior, revision=prior.revision + 1, last_bar_end_ms=event.bar_end_ms,
                    eligible_bars=eligible, structure_failure_bars=failure_bars, best_close=best,
                    active_soft_boundary=active, active_boundary_id=active_id,
                    pending_soft_boundary=None, pending_boundary_id=None, soft_breach_bars=breach)

    if failure_bars >= policy.structure_failure_confirm_bars:
        return _result(state, event, ExitActionV5.FULL_CLOSE, 0,
                       ("MODEL_INVALIDATION_CONFIRMED",), groups, input_hash)

    mature = eligible >= policy.maturity_bars
    independent_failure = bool(INDEPENDENT_SOFT_GROUPS.intersection(groups))
    if active is not None and breach >= policy.soft_breach_confirm_bars:
        if independent_failure:
            return _result(state, event, ExitActionV5.FULL_CLOSE, 0,
                           ("CONFIRMED_TREND_FAILURE",), groups, input_hash)
        state = replace(state, soft_breach_bars=breach)
        return _result(state, event, ExitActionV5.HOLD, event.confirmed_remaining_contracts,
                       ("EXIT_WATCH_INSUFFICIENT_INDEPENDENT_EVIDENCE",), groups, input_hash)

    if (mature and event.target_reached and independent_failure
            and state.partial_stage < policy.max_partial_stages):
        close_qty = int((Decimal(event.confirmed_remaining_contracts) * policy.partial_fraction)
                        .to_integral_value(rounding=ROUND_DOWN))
        economic = event.partial_net_pnl is not None and event.partial_net_pnl >= (
            event.round_trip_cost * policy.partial_net_cost_multiple)
        if close_qty >= 1 and event.confirmed_remaining_contracts - close_qty >= 1 and economic:
            target = event.confirmed_remaining_contracts - close_qty
            return _result(replace(state, phase=ProfitPhase.HARVEST), event,
                           ExitActionV5.PARTIAL_CLOSE, target, ("STRUCTURE_HARVEST",), groups, input_hash)
        return _result(state, event, ExitActionV5.HOLD, event.confirmed_remaining_contracts,
                       ("PARTIAL_CLOSE_NOT_ECONOMIC_OR_NOT_DIVISIBLE",), groups, input_hash)

    favorable = close - contract.entry_price if contract.side is Side.LONG else contract.entry_price - close
    unit = Decimal(event.confirmed_remaining_contracts) * contract.contract_size
    cost_distance = event.round_trip_cost / unit if unit > ZERO else ZERO
    arm_distance = max(contract.atr_at_entry * policy.arm_atr_multiplier,
                       cost_distance * policy.arm_cost_distance_multiple)
    arm = (mature and favorable >= arm_distance
           and event.current_net_pnl >= event.round_trip_cost * policy.arm_net_cost_multiple)
    extension = state.extension_achieved or arm
    if eligible >= policy.no_extension_horizon_bars and not extension:
        return _result(replace(state, extension_achieved=False), event, ExitActionV5.FULL_CLOSE, 0,
                       ("THESIS_TIMEOUT",), groups, input_hash)

    candidate = None
    if arm or state.phase in {ProfitPhase.ESTABLISHED, ProfitPhase.HARVEST, ProfitPhase.RUNNER}:
        atr = max(contract.atr_at_entry, event.atr_at_close or ZERO)
        distance = atr * policy.noise_atr_multiplier
        candidate = best - distance if contract.side is Side.LONG else best + distance
        too_close = abs(close - candidate) < atr * policy.noise_min_atr
        if too_close:
            candidate = None
        else:
            candidate = _tighten(contract.side, active, candidate)
    if candidate is not None and candidate != active:
        boundary_id = canonical_hash({"barEnd": event.bar_end_ms, "price": candidate})[:32]
        state = replace(state, phase=ProfitPhase.ESTABLISHED, extension_achieved=extension,
                        armed_bar_end_ms=state.armed_bar_end_ms or event.bar_end_ms,
                        pending_soft_boundary=candidate, pending_boundary_id=boundary_id)
        return _result(state, event, ExitActionV5.UPDATE_SOFT_BOUNDARY,
                       event.confirmed_remaining_contracts, ("SOFT_BOUNDARY_STAGED_FOR_NEXT_BAR",), groups, input_hash)

    state = replace(state, extension_achieved=extension,
                    phase=ProfitPhase.ESTABLISHED if extension else state.phase)
    reason = "SOFT_MATURITY_NOT_REACHED" if not mature else "THESIS_VALID_HOLD"
    return _result(state, event, ExitActionV5.HOLD, event.confirmed_remaining_contracts,
                   (reason,), groups, input_hash)


def _eligible_closed_bar(contract: EntryThesisV5, state: ExitStateV5, event: ExitEventV5,
                         policy: ExitPolicyV5) -> bool:
    return (event.bar_open_ms is not None and event.bar_end_ms is not None and event.close_price is not None
            and event.bar_open_ms >= contract.first_fill_time_ms
            and event.bar_end_ms + policy.close_grace_ms <= event.event_time_ms
            and (state.last_bar_end_ms is None or event.bar_end_ms > state.last_bar_end_ms))


def _result(state: ExitStateV5, event: ExitEventV5, action: ExitActionV5, target: int,
            reasons: tuple[str, ...], groups: tuple[str, ...], input_hash: str) -> ExitTransitionV5:
    return ExitTransitionV5(ExitDecisionV5(action, state.phase, target, reasons, groups, input_hash,
                                            state.active_soft_boundary, state.pending_soft_boundary,
                                            state.eligible_bars, state.soft_breach_bars), state)


def _hard_stop(side: Side, price: Decimal, stop: Decimal) -> bool:
    return price <= stop if side is Side.LONG else price >= stop


def _crossed(side: Side, price: Decimal, boundary: Decimal | None) -> bool:
    return boundary is not None and (price <= boundary if side is Side.LONG else price >= boundary)


def _tighten(side: Side, current: Decimal | None, candidate: Decimal) -> Decimal:
    if current is None:
        return candidate
    return max(current, candidate) if side is Side.LONG else min(current, candidate)


def _validate(contract: EntryThesisV5, state: ExitStateV5, event: ExitEventV5, policy: ExitPolicyV5) -> None:
    if contract.engine_version != "trend-exit-v5" or not contract.policy_hash:
        raise ValueError("v5 entry thesis requires an engine version and policy hash")
    if contract.contract_schema != "trend-entry-thesis-v5" or contract.model_adapter_version != "trend-model-holding-v5":
        raise ValueError("v5 entry thesis has an unsupported contract or model adapter version")
    if contract.policy_hash != canonical_hash(policy):
        raise ValueError("v5 policy does not match the entry thesis policy hash")
    if contract.filled_contracts <= 0 or contract.contract_size <= ZERO or contract.entry_price <= ZERO:
        raise ValueError("v5 entry thesis has invalid confirmed quantity or pricing")
    if event.confirmed_remaining_contracts <= 0 or event.confirmed_remaining_contracts > contract.filled_contracts:
        raise ValueError("event quantity is outside the entry contract")
    if event.reference_price <= ZERO or event.round_trip_cost < ZERO:
        raise ValueError("event pricing or cost is invalid")
    if policy.maturity_bars < 1 or policy.soft_breach_confirm_bars < 1 or policy.structure_failure_confirm_bars < 1:
        raise ValueError("v5 policy confirmation counts must be positive")
    if state.phase is ProfitPhase.CLOSED:
        raise ValueError("closed state cannot be evaluated")
