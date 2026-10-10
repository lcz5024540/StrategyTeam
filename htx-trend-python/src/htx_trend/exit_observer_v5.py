"""Offline v5 observer; this module has no exchange SDK or order interface."""
from dataclasses import dataclass
from enum import StrEnum
from .exit_contract_v5 import EntryThesisV5, ExitEventV5, ExitPolicyV5, ExitStateV5, ExitTransitionV5
from .exit_kernel_v5 import decide_v5


class ObservationMode(StrEnum):
    OBSERVE = "OBSERVE"
    PAPER = "PAPER"


@dataclass(frozen=True, slots=True)
class ExitObservationV5:
    mode: ObservationMode
    transition: ExitTransitionV5


def observe_v5(mode: str, contract: EntryThesisV5, state: ExitStateV5, event: ExitEventV5,
               policy: ExitPolicyV5 = ExitPolicyV5()) -> ExitObservationV5:
    try:
        safe_mode = ObservationMode(mode)
    except ValueError as error:
        raise ValueError("trend exit v5 observer permits only OBSERVE or PAPER") from error
    return ExitObservationV5(safe_mode, decide_v5(contract, state, event, policy))
