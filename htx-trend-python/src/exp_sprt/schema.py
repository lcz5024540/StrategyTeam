"""Shared labels and canonical decimal text for EXP-SPRT Phase-0."""
from __future__ import annotations

from decimal import Decimal
from hashlib import sha256

from . import EXP_ID, PHASE, SCHEMA_VERSION, TRACK

FEE_RATE = Decimal("0.0004")
ZONE_WIDTH_FRACTION = Decimal("0.15")
STRUCTURE_BARS = 20
SWING_ANCHOR_BARS = 10
DEDUPE_WINDOW_SECONDS = 900
PLAN_EXPIRY_BARS = 16
GENERATOR_RULE = "phase0-swing20-band15pct-v0"
MAPPING_SENTENCE = (
    "v0.1区界映射（无独立break_level字段）：空突破 S := z_lo，"
    "取本计划指定边 zone_support[0].low；多突破 S := z_hi，"
    "取本计划指定边 zone_resistance[0].high。"
)


def money(value: Decimal) -> str:
    text = format(value, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text or "0"


def sha256_text(text: str) -> str:
    return "sha256:" + sha256(text.encode()).hexdigest()


def track_fields() -> dict[str, str]:
    return {"exp_id": EXP_ID, "phase": PHASE, "track": TRACK, "schema_version": SCHEMA_VERSION}
