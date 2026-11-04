"""Explicit new-model ownership; never masquerade as an EMA candidate."""

from enum import StrEnum
from ..models import ModelCode
from . import VERSION


class RealtimeModel(StrEnum):
    RT_INDICATOR_UNION = "RT_INDICATOR_UNION"
    RT_RANGE_REJECTION = "RT_RANGE_REJECTION"
    RT_BREAKOUT_RETEST = "RT_BREAKOUT_RETEST"


def parse_model(value):
    return RealtimeModel(value) if value in tuple(RealtimeModel) else ModelCode(value)


def supported_models():
    return tuple(ModelCode) + tuple(RealtimeModel)


def is_realtime(value):
    return value in tuple(RealtimeModel)


def supports_continuation(strategy):
    return strategy in ("HTX_TREND_15M_V2", VERSION)
