# P2 只读微观结构采集器

独立研究进程。命令不带参数时退出码为 2，**默认不启动**。当前命令只提供 `--dry-run`：使用录制的公开响应写本地样例，不打开网络，不读私钥，不写交易业务表，不安装服务，不修改现网 collector。

攒满 3–6 个月之前，这些文件不能当作信号输入，也不重开 G0。

## 币池

默认币池是仓库内冻结的 ring-2 43 个 HTX USDT 永续合约（含 WIN-USDT），文件为 `contracts_ring2_43.json`。其中包含 BTC-USDT 与 ETH-USDT。`universe.yaml` 的 `contracts_file` 指向这份清单；加载时若缺少 BTC-USDT 或 ETH-USDT 会拒绝启动。替换 `contracts_file` 即可改币池。

不再默认使用三个月研究宇宙的 52 个合约。

## 公开接口

只使用 HTX 线性永续公开 REST，主机 `https://api.hbdm.com`：

| 数据 | 路径 | 节奏 |
| --- | --- | --- |
| 持仓量 | `/linear-swap-api/v1/swap_open_interest` | 300 秒 |
| 强平 | `/linear-swap-api/v3/swap_liquidation_orders` | 60 秒 |
| 盘口 | `/linear-swap-ex/market/depth?type=step0`，只保留 20 档 | 60 秒 |
| 逐笔 | `/linear-swap-ex/market/history/trade`，可选，聚合成 1 分钟主动买卖量 | 60 秒 |

请求经令牌间隔限频。连接失败、429 和 5xx 按次数重连；错过节奏（间隔加宽限）写入 `gaps/`。同一持仓量时间戳、同一强平成交编号、同一盘口时间戳、同一逐笔编号重复写入会被丢掉。1 分钟柱按编号去重后再累加，重启后仍认同一编号。

## 目录

```text
open_interest/YYYY-MM-DD/{contract}.jsonl
liquidation/YYYY-MM-DD/{contract}.jsonl
depth/YYYY-MM-DD/{contract}.jsonl
trades_1m/YYYY-MM-DD/{contract}.jsonl
gaps/YYYY-MM-DD.jsonl
_index/keys.txt
```

不保留逐笔原文，只保留 1 分钟主动买量、主动卖量和成交编号索引。

## 每月存储量

字节来自 `estimate.canonical_record_bytes()` 的固定样例记录，不是线上实测。30 天，十进制 GB（10^9 字节）。强平按每合约每小时 1 条估计。盘口约 781 字节/张，1 分钟柱约 165 字节，成交编号索引约 31 字节/笔。

默认口径是 ring-2 43 个合约（含 WIN），单合约逐笔 1 笔/秒，约 5.25 GB/月（表中 5.252 GB）。不再按 52 合约估算。

| 合约数 | 单合约逐笔/秒 | 每月 |
| --- | --- | --- |
| 43（默认 ring-2，含 WIN） | 0.2 | 2.488 GB |
| 43（默认） | 1 | 5.252 GB |
| 43 | 5 | 19.073 GB |

主要体积是盘口 20 档和成交编号索引。若把逐笔原文也落盘，存储会按成交速率再升一档，默认不这么做。

## 短跑

在 `research/` 目录：

```sh
python -m msa_p2_collector
python -m msa_p2_collector --dry-run --out msa_p2_collector/samples --symbols BTC-USDT
```

第一条应退出码 2。第二条只写样例。
