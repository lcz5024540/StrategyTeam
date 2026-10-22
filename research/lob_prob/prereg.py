"""加载预注册文件。运行入口必须带上与文件字节一致的 SHA256，否则拒绝。"""
import argparse
import hashlib
import sys
from decimal import Decimal
from pathlib import Path

from calibration import MIN_BIN_COUNT_FLOOR, ThresholdLoweringForbidden
from ev import TAKER_BPS

DEFAULT_PREREG_PATH = Path(__file__).resolve().with_name("prereg.yaml")


class PreregRejected(Exception):
    """预注册哈希缺失或不一致，运行被拒绝。"""


def file_sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def _parse_scalar(token):
    if len(token) >= 2 and token[0] == token[-1] and token[0] in ("'", '"'):
        return token[1:-1]
    if token.startswith("[") and token.endswith("]"):
        inner = token[1:-1].strip()
        if not inner:
            return []
        return [_parse_scalar(part.strip()) for part in inner.split(",")]
    if token == "true":
        return True
    if token == "false":
        return False
    if token in ("null", "~"):
        return None
    if token.isdigit() or (token.startswith("-") and token[1:].isdigit()):
        return int(token)
    return token


def _parse_yaml_subset(text):
    """只解析本预注册用到的缩进映射、行内列表和标量。"""
    root = {}
    stack = [(-1, root)]
    for lineno, raw in enumerate(text.splitlines(), 1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        if "\t" in raw[:indent]:
            raise ValueError(f"第 {lineno} 行不允许用制表符缩进")
        line = raw.strip()
        if ":" not in line:
            raise ValueError(f"无法解析第 {lineno} 行")
        key, _, rest = line.partition(":")
        key = key.strip()
        rest = rest.strip()
        while stack and indent <= stack[-1][0]:
            stack.pop()
        if not stack or not isinstance(stack[-1][1], dict):
            raise ValueError(f"第 {lineno} 行的缩进对不上")
        parent = stack[-1][1]
        if rest == "":
            child = {}
            parent[key] = child
            stack.append((indent, child))
        else:
            parent[key] = _parse_scalar(rest)
    return root


def _validate(cfg):
    if cfg["tick_size"] <= 0:
        raise ValueError("tick_size 必须为正")
    if not cfg["k_ticks"] or any(
        isinstance(k, bool) or not isinstance(k, int) or k <= 0 for k in cfg["k_ticks"]
    ):
        raise ValueError("k_ticks 必须是正整数列表")
    if not cfg["horizon_s"] or any(
        isinstance(h, bool) or not isinstance(h, int) or h <= 0 for h in cfg["horizon_s"]
    ):
        raise ValueError("horizon_s 必须是正整数列表")
    if cfg["start_rule"] != "next_whole_second_after_signal":
        raise ValueError("起点必须是信号后下一整秒")
    if cfg["prior"]["name"] != "queue_race" or cfg["prior"]["p_up"] != "Qb/(Qb+Qa)":
        raise ValueError("先验必须是排队竞速 Qb/(Qb+Qa)")
    tilt = cfg["imbalance_tilt"]
    if tilt["windows_min"] != [1, 5, 15] or tilt["link"] != "logit_additive":
        raise ValueError("失衡倾斜必须是 1/5/15 分钟窗口上的 logit 加性项")
    if tilt["target"] != "contemporaneous_return_residual":
        raise ValueError("失衡只能对着同期收益残差")
    if cfg["calibration"]["lower_threshold"] != "forbidden":
        raise ThresholdLoweringForbidden()
    if cfg["calibration"]["min_bin_count"] < MIN_BIN_COUNT_FLOOR:
        raise ThresholdLoweringForbidden()
    if cfg["split"]["method"] != "time":
        raise ValueError("切分必须按时间")
    fraction = cfg["split"]["train_fraction"]
    if not (Decimal(0) < fraction < Decimal(1)):
        raise ValueError("train_fraction 必须在 (0, 1) 内")
    if Decimal(cfg["fees"]["taker_bps"]) != TAKER_BPS:
        raise ValueError("taker 费率必须是 12 bps")
    if cfg["fees"]["maker_bps"] != "measured":
        raise ValueError("maker 费率必须留作实测，不能写死")
    if cfg["fill_miss"]["parameter"] != "maker_miss_rate" or cfg["fill_miss"]["value"] != "measured":
        raise ValueError("漏成率必须留作实测的 maker_miss_rate")
    if cfg["fill_miss"]["unfilled_pnl_bps"] != 0:
        raise ValueError("未成交盈亏按 0 记账")
    return cfg


def load_prereg_bytes(payload):
    raw = _parse_yaml_subset(payload.decode("utf-8"))
    cfg = {
        "tick_size": Decimal(raw["tick_size"]),
        "k_ticks": list(raw["k_ticks"]),
        "horizon_s": list(raw["horizon_s"]),
        "start_rule": raw["start_rule"],
        "prior": {"name": raw["prior"]["name"], "p_up": raw["prior"]["p_up"]},
        "imbalance_tilt": {
            "optional": bool(raw["imbalance_tilt"]["optional"]),
            "windows_min": list(raw["imbalance_tilt"]["windows_min"]),
            "target": raw["imbalance_tilt"]["target"],
            "link": raw["imbalance_tilt"]["link"],
        },
        "calibration": {
            "min_bin_count": int(raw["calibration"]["min_bin_count"]),
            "lower_threshold": raw["calibration"]["lower_threshold"],
        },
        "split": {
            "method": raw["split"]["method"],
            "train_fraction": Decimal(raw["split"]["train_fraction"]),
        },
        "fees": {
            "taker_bps": int(raw["fees"]["taker_bps"]),
            "maker_bps": raw["fees"]["maker_bps"],
        },
        "fill_miss": {
            "parameter": raw["fill_miss"]["parameter"],
            "value": raw["fill_miss"]["value"],
            "unfilled_pnl_bps": int(raw["fill_miss"]["unfilled_pnl_bps"]),
        },
    }
    return _validate(cfg)


def load_prereg(path=DEFAULT_PREREG_PATH):
    return load_prereg_bytes(Path(path).read_bytes())


def assert_prereg_sha(path, expected_sha):
    """哈希必须等于文件原始字节。通过之后才解析，解析不会放宽门槛。"""
    payload = Path(path).read_bytes()
    actual = hashlib.sha256(payload).hexdigest()
    supplied = "" if expected_sha is None else str(expected_sha).strip().lower()
    if supplied == "" or supplied != actual:
        raise PreregRejected(f"预注册 SHA256 不一致，拒绝运行。文件实际为 {actual}")
    return load_prereg_bytes(payload)


def main(argv=None):
    """校验 --prereg-sha。校验通过只表示允许进入研究流程，这里不读行情、不跑实验。"""
    parser = argparse.ArgumentParser(description="校验订单簿概率预注册 SHA256")
    parser.add_argument("--prereg", default=str(DEFAULT_PREREG_PATH))
    parser.add_argument("--prereg-sha", required=True)
    args = parser.parse_args(list(argv) if argv is not None else None)
    try:
        assert_prereg_sha(args.prereg, args.prereg_sha)
    except (PreregRejected, ThresholdLoweringForbidden, ValueError) as exc:
        print(str(exc), file=sys.stderr)
        return 2
    print("预注册 SHA256 校验通过。未加载行情，未执行实验。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
