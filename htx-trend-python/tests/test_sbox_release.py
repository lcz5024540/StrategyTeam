import json

import pytest
from htx_trend.realtime import VERSION
from htx_trend.realtime.plans import SBOX, Parameters
from htx_trend.research.store import primitive
from htx_trend.strategy_profiles import profile_strategy
from htx_trend.timeframes import TimeframeProfile
from test_realtime_live_release import package, payload, repack


def test_sbox_bundle_contains_confirmed_rule_and_offline_spec(tmp_path):
    path = package.build_live(tmp_path, sbox=True)
    receipt = package.verify(path)
    data = payload(path)
    cfg = json.loads(data["release/config.example.json"])
    p = Parameters(candidate=SBOX, target_mode="J1_BOX32")
    assert cfg["structural_candidate_selected"] is True
    assert (
        cfg["entry_enabled"] is False and cfg["financial_admission"] == "NOT_ELIGIBLE"
    )
    assert cfg["parameters"] == primitive(p.payload)
    assert (
        receipt["bindings"]["strategy_spec_hash"]
        == profile_strategy(
            TimeframeProfile.INTRADAY_15M,
            read_env=False,
            strategy_version=VERSION,
            realtime_parameters=p,
        ).spec_hash
    )
    assert (
        b"HTX_RT_CANDIDATE=SBOX_FACTS_CONSERVATIVE_V1"
        in data["deployment/realtime-live.env.example"]
    )
    assert b"HTX_RT_MIN_NET_R=1.5" in data["deployment/realtime-live.env.example"]
    assert "release/SBOX-LIVE.md" in data


@pytest.mark.parametrize(
    "key,value", [("min_net_r", "1"), ("target_mode", "J2_BOX32"), ("band_atr", "0.05")]
)
def test_rehashed_sbox_parameter_change_is_rejected(tmp_path, key, value):
    path = package.build_live(tmp_path, sbox=True)
    repack(path, lambda cfg, manifest, data: cfg["parameters"].update({key: value}))
    with pytest.raises(ValueError, match="SBOX"):
        package.verify(path)


def test_runtime_env_cannot_silently_use_old_candidate(tmp_path):
    path = package.build_live(tmp_path, sbox=True)

    def change(cfg, manifest, data):
        key = "deployment/realtime-live.env.example"
        data[key] = data[key].replace(SBOX.encode(), b"ORIGINAL_20_32_V1")

    repack(path, change)
    with pytest.raises(ValueError, match="SBOX runtime"):
        package.verify(path)


def test_offline_parameters_cannot_be_ignored_for_other_strategy():
    with pytest.raises(ValueError, match="realtime strategy"):
        profile_strategy(
            TimeframeProfile.INTRADAY_15M,
            read_env=False,
            realtime_parameters=Parameters(candidate=SBOX, target_mode="J1_BOX32"),
        )
