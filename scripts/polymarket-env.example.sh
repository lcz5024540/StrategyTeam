#!/usr/bin/env bash

# [SCRIPT][FIXED-ONCE] Execution wallet address on Polygon / Polymarket.
export POLYMARKET_EXECUTION_WALLET_ADDRESS=""
# [SCRIPT][FIXED-ONCE] Private key for the execution wallet. Keep only in local env, never commit.
export POLYMARKET_EXECUTION_PRIVATE_KEY=""
# [SCRIPT][FIXED-ONCE] Funder address used by Polymarket CLOB auth. Often same as execution wallet.
export POLYMARKET_EXECUTION_FUNDER_ADDRESS=""

# [SCRIPT][FIXED-ONCE] CLOB API key derived from wallet auth. Can stay empty before first derive.
export POLYMARKET_CLOB_API_KEY=""
# [SCRIPT][FIXED-ONCE] CLOB API secret derived from wallet auth.
export POLYMARKET_CLOB_API_SECRET=""
# [SCRIPT][FIXED-ONCE] CLOB API passphrase derived from wallet auth.
export POLYMARKET_CLOB_API_PASSPHRASE=""

# [SCRIPT][FIXED-ONCE] Python executable used by the official py-clob-client bridge.
export POLYMARKET_PYTHON_EXECUTABLE="/opt/homebrew/bin/python3.11"
