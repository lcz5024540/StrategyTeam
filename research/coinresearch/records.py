"""Stable provenance, deliberately without timestamps or output paths."""
import hashlib
from pathlib import Path
from .data import write_json


def file_hashes(paths):
    return {str(Path(p).resolve()): hashlib.sha256(Path(p).read_bytes()).hexdigest()
            for p in sorted(paths, key=str)}


def write_run_record(out, command, inputs, parameters, seed):
    write_json(Path(out)/'run_record.json', dict(command=command, input_sha256=inputs,
                                              parameters=parameters, seed=seed))
