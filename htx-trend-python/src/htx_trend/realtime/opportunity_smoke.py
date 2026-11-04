"""Credential-free, one-shot public API/structure smoke. Never constructs a broker."""
import argparse
from collections import Counter
from datetime import UTC, datetime
from decimal import Decimal as D
import json
from pathlib import Path
import time

from ..htx_v5 import HtxV5Client
from ..market_data import HtxMarketData
from ..research.store import FileEvidenceStore, primitive
from .opportunities import discover
from .plans import OPPORTUNITY, Parameters
from .selection import features


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--contract", default="ETH-USDT")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    args.output.mkdir(parents=True, exist_ok=True)
    store = FileEvidenceStore(args.output / "evidence", compress=True)
    client = HtxV5Client()
    try:
        market = HtxMarketData(client, evidence_store=store)
        windows = {p: market.kline_window(args.contract, p, 160) for p in ("15min", "60min", "4hour")}
        contract = market.contract_spec(args.contract)
        requested_ms = int(time.time()*1000)
        book = market.order_book(args.contract)
        received_ms = int(time.time()*1000)
        audit = []
        plans = discover(windows["15min"], windows["60min"], contract,
                         now_ms=received_ms, parameters=Parameters(candidate=OPPORTUNITY,
                         min_net_r=D(0), target_mode="OWN_STRUCTURE"), audit=audit)
        result = primitive({"at": datetime.now(UTC), "scope": "PUBLIC_READ_ONLY_NO_EXECUTION",
            "contract": contract, "windows": windows, "book": book,
            "book_requested_ms": requested_ms, "book_received_ms": received_ms,
            "decisions": audit, "families": Counter(
                s for p in plans for s in p.sources if s.startswith("FAMILY:")),
            "features": {p.plan_id: features(p, windows, book, received_ms) for p in plans}})
        (args.output / "public-smoke.json").write_text(json.dumps(result, indent=2)+"\n")
        print(json.dumps({"output": str(args.output), "plans": len(plans),
                          "families": result["families"], "trading": False}))
    finally:
        client.close()


if __name__ == "__main__":
    main()
