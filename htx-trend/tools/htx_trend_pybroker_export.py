#!/usr/bin/env python3
"""Export frozen HTX candles into a read-only, PyBroker-compatible research input.

No PyBroker package is imported or bundled.  The upstream project carries a Commons Clause,
so this exporter is deliberately limited to internal/offline research artifacts.
"""
from __future__ import annotations
import argparse, csv, hashlib, json
from datetime import datetime, timezone
from pathlib import Path

SCHEMA="htx-trend-pybroker-export-v1"
def sha(path: Path) -> str: return hashlib.sha256(path.read_bytes()).hexdigest()
def export(data_dir: Path, manifest_path: Path, output: Path, period: str="15min") -> dict:
    manifest=json.loads(manifest_path.read_text())
    if manifest.get("schema")!="htx-trend-research-dataset-manifest-v1" or manifest.get("inputIntegrity")!="COMPLETE":
        raise ValueError("PyBroker export requires a COMPLETE frozen dataset manifest")
    rows=[]; inputs=[]
    for item in sorted(manifest.get("inputs",[]), key=lambda value:(value.get("contract",""),value.get("period",""))):
        if item.get("period")!=period or item.get("coverage")!="COMPLETE" or item.get("sourceKind")!="RAW_PUBLIC": continue
        path=data_dir/item["file"]
        if not path.is_file() or sha(path)!=item.get("sha256"): raise ValueError(f"manifest checksum mismatch: {item.get('file')}")
        envelope=json.loads(path.read_text()); data=envelope.get("data",[])
        for bar in sorted(data,key=lambda value:value["id"]):
            rows.append({"date":datetime.fromtimestamp(int(bar["id"]),timezone.utc).isoformat(),"symbol":item["contract"],
                         "open":bar["open"],"high":bar["high"],"low":bar["low"],"close":bar["close"],"volume":bar.get("vol",bar.get("amount",0))})
        inputs.append({"contract":item["contract"],"period":period,"file":item["file"],"sha256":item["sha256"],"barCount":len(data)})
    if not rows: raise ValueError(f"no COMPLETE {period} inputs found")
    output.parent.mkdir(parents=True,exist_ok=True)
    with output.open("w",newline="") as fp:
        writer=csv.DictWriter(fp,fieldnames=["date","symbol","open","high","low","close","volume"]); writer.writeheader(); writer.writerows(rows)
    return {"schema":SCHEMA,"datasetKey":manifest["datasetKey"],"inputIntegrity":"COMPLETE","period":period,
            "csv":str(output),"csvSha256":sha(output),"rowCount":len(rows),"inputs":inputs,
            "executionBoundary":"READ_ONLY_INTERNAL_RESEARCH_ONLY","pybrokerRuntime":"NOT_BUNDLED"}
def main() -> None:
    p=argparse.ArgumentParser(description=__doc__); p.add_argument("--data-dir",type=Path,required=True); p.add_argument("--manifest",type=Path,required=True); p.add_argument("--period",default="15min"); p.add_argument("--csv-out",type=Path,required=True); p.add_argument("--report-out",type=Path,required=True); a=p.parse_args()
    report=export(a.data_dir,a.manifest,a.csv_out,a.period); a.report_out.parent.mkdir(parents=True,exist_ok=True); a.report_out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n"); print(json.dumps(report,ensure_ascii=False))
if __name__=="__main__": main()
