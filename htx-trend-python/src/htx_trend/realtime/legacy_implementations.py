"""Exact predecessor source vectors. Only legacy models may use this adapter.

Default-input equivalence is verified by the predecessor differential tests.
No policy or historical source hash is rewritten.
"""

LEGACY_IMPLEMENTATIONS = (
    {
        "exits.py": "dd0d58e9a1b7470d1622a0d752c3a049d01fd0f328df3a6c855c7c66244aa866",
        "continuation.py": "7cb1471018e262b4f3be7dd1e420c0096cef09e1adaa6b4c6c3eac4dbada3456",
        "java_entry_contract.py": "662a11f04785d12f7d2543894b8ad5076a0422ddaeb5851ef3630c80c4521831",
        "indicators.py": "b2d5c297f306c8014e50f5cc0a82ebe802d9a6ea0e1e66efd9fb42f0015f922c",
        "models.py": "0143c0dc07f6f9b6985ec0973e4319cdb73841aaeb86506e8c2e4cee297d5fe2",
        "timeframes.py": "cf68aee9b1ee5a6a970054f5ba7754264f32a558d2986bac519f2818557171f8",
        "position_manager.py": "741d825efe6e6af786e8a8b72cd4e17d29b7f479ef697d69018d6acdcceb6d58",
        "runtime.py": "d320f77c0a846abd697dc2d7116bc7e9fdc6866569beedbdb662181272ae8345",
    },
    {
        "exits.py": "dd0d58e9a1b7470d1622a0d752c3a049d01fd0f328df3a6c855c7c66244aa866",
        "continuation.py": "7cb1471018e262b4f3be7dd1e420c0096cef09e1adaa6b4c6c3eac4dbada3456",
        "java_entry_contract.py": "662a11f04785d12f7d2543894b8ad5076a0422ddaeb5851ef3630c80c4521831",
        "indicators.py": "b2d5c297f306c8014e50f5cc0a82ebe802d9a6ea0e1e66efd9fb42f0015f922c",
        "models.py": "0143c0dc07f6f9b6985ec0973e4319cdb73841aaeb86506e8c2e4cee297d5fe2",
        "timeframes.py": "cf68aee9b1ee5a6a970054f5ba7754264f32a558d2986bac519f2818557171f8",
        "position_manager.py": "7794b5f8c8e0381bd24ebc5832995b253555953798026d8ce030291cb5734467",
        "runtime.py": "fea726d48b80fc59d3deb35e4cf376be6897c7e83d093855881bd7c3f36a06d4",
    },
    {
        "exits.py": "a48376825731b7a4d70f6defd18d4e91f9e67e3cd3bb65c08072cd36ad6ee202",
        "continuation.py": "7cb1471018e262b4f3be7dd1e420c0096cef09e1adaa6b4c6c3eac4dbada3456",
        "java_entry_contract.py": "662a11f04785d12f7d2543894b8ad5076a0422ddaeb5851ef3630c80c4521831",
        "indicators.py": "b2d5c297f306c8014e50f5cc0a82ebe802d9a6ea0e1e66efd9fb42f0015f922c",
        "models.py": "0143c0dc07f6f9b6985ec0973e4319cdb73841aaeb86506e8c2e4cee297d5fe2",
        "timeframes.py": "cf68aee9b1ee5a6a970054f5ba7754264f32a558d2986bac519f2818557171f8",
        "position_manager.py": "1c93aa027fbeaa548f1c498f2c3b4c3266bdda443c9cc5be3f16c56bb99e88c5",
        "runtime.py": "5f9dcbc279f3259ca3eabb38045abb3ada394190d8de6844d8e881c1b5244316",
        "realtime/ownership.py": "afab71da17c6994a9c00ec98f88f41556b41f734eed1a054a521ec78e9f3e454",
        "realtime/management.py": "9fd0acb72c4da5628ab8ec4a8bb3d0dc1f49b047b9d0366254c81525f53cf516",
    },
)
