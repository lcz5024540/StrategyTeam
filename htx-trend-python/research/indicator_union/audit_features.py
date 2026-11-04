"""Compare runtime Decimal indicators with the original frozen research formulas.

Offline only. Does not read results, tune parameters, or generate orders.
"""

import argparse
from decimal import Decimal
import gzip
from hashlib import sha256
import json
from pathlib import Path
import sys

from htx_trend.market_data import Candle, KlineWindow
from htx_trend.realtime.indicator_union import features


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--frames", type=Path, required=True)
    parser.add_argument("--research-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = args.research_root
    sys.path.insert(0, str(root / "cos_multitimeframe_probability"))
    import research as reference

    source_files = [
        root / "cos_multitimeframe_probability/research.py",
        root / "cos_expanded_fit/features.py",
        root / "cos_indicator_fit/study.py",
    ]
    count = 0
    contracts = set()
    maximum = {}
    sign_differences = []
    with gzip.open(args.frames, "rt") as source:
        for line in source:
            for record in json.loads(line)["frames"]:
                event = record["event"]

                def window(period):
                    values = []
                    for raw in event["windows"][period]["candles"]:
                        values.append(
                            Candle(
                                **{
                                    key: (
                                        value
                                        if key in ("open_time", "trade_count")
                                        or value is None
                                        else Decimal(value)
                                    )
                                    for key, value in raw.items()
                                }
                            )
                        )
                    return KlineWindow(
                        event["contract"], period, tuple(values), len(values)
                    )

                actual = features(window("15min"), window("60min"))
                base = reference.feature(event)
                hour = reference.timeframe(event, "60min", 3600)
                expected = {
                    "ema9": base["ema9"],
                    "atr": base["atr"],
                    "cci": hour["CCI"],
                    "macd": hour["MACD"],
                    "channel": hour["CHANNEL"],
                    "cmf": base["cmf"],
                    "lower": base["lower_wick"],
                    "upper": base["upper_wick"],
                }
                for key, value in expected.items():
                    if value is None:
                        assert actual[key] is None
                        continue
                    if key in ("macd", "cci", "cmf", "channel") and (actual[key] > 0, actual[key] < 0) != (value > 0, value < 0):
                        sign_differences.append({"contract":event["contract"],"key":key,"runtime":str(actual[key]),"research":value})
                    error = abs(float(actual[key]) - value)
                    maximum[key] = max(maximum.get(key, 0), error)
                    assert error <= 1e-8 * max(1, abs(value)), (
                        event["contract"],
                        key,
                        actual[key],
                        value,
                    )
                count += 1
                contracts.add(event["contract"])
    report = {
        "status": "PASS",
        "frames": count,
        "contracts": len(contracts),
        "comparisons": count * 8,
        "max_absolute_error": maximum,
        "direction_sign_differences": sign_differences,
        "source_sha256": sha256(args.frames.read_bytes()).hexdigest(),
        "reference_sources": {
            str(p): sha256(p.read_bytes()).hexdigest() for p in source_files
        },
        "scope": "Indicator formula equivalence only; not union entry timing, account return or live admission.",
    }
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
