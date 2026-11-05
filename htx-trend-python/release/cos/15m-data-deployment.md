# 15m数据专用部署

用于公开15min/60min K线及4hour上下文、BBO、深度、逐笔、公开资金费率和合约规格采集；不加载交易env、不接数据库、不生成订单。独立目录、服务和版本，不更换已有MANAGEMENT_ONLY worker或其绑定许可。

1. 从确切已提交源码 `release/package.py build --paper --realtime`，verify后冻结tar及SHA。复制到独立 `/opt/coinstrategy/15m-market-data/releases/<version>`，原子切独立current；禁止切共享htx-trend-py/current。
2. 为该数据服务创建独立venv，安装包声明依赖并冻结pip清单；不得假设旧交易venv包含WebSocket库。`/etc/coinstrategy/15m-cos-release.env`只包含`HTX_PUBLIC_CAPTURE_RELEASE_ID=<version>`，每次启动有COLLECTION_SESSION事件记录版本。固定现有15m池到 `/etc/coinstrategy/15m-cos-pool.json`。保留contracts、原池依据、采集提交及包SHA。同一root恢复要求池/观测依据/容量/磁盘下限一致；未来改池或版本要显式规划源连续性，不能静默复用不同身份。
3. 安装本目录data service。固定root `/home/ubuntu/coinstrategy-data/15m-cos/capture`，`--segmented --resume` 可在正常停止/24h轮次后原目录续采。旧段/原capture.json保留，status的counts仅指本进程。源硬限制仍2GiB数据、根盘最低5GiB；容量或瞬时故障退出后 systemd 每60秒重试，且禁止因启动限流永久停采。重试本身不删除文件。
4. 首次目录由data进程初始化成功后，再启动COS服务。COS配置单源 `kind=segments` 指向capture/segments，独立workspace；schedule.enabled=true、interval_seconds=900、audit_seconds=604800。COS service只写该源的.sealing和归档workspace，行情对象只读。不能照抄通用模板而漏掉.sealing写权限。
5. 检查52池各类采集覆盖、真实K线窗/时钟/来源异常。sealed阈值64MiB/20000对象，退出时尝试封存不足阈值的末段，仍须通过consumer及证据故障检查；一次人工封存可用于部署验收。归档timer每轮最多上传4代。随后按 `verified-release.md` 配置并人工授权release policy；archive与release两个timer必须启用并纳入监控，否则2GiB最终仍会触发保护停采。只有达到`COS_VERIFIED`、重新下载验哈并通过本地依赖检查的payload可以释放；不删除COS对象。
6. 下载实际manifest段并用public_capture.read_reference/segment_codec读取，校验SHA和K线/盘口时间；记录版本/包/池/配置/unit SHA及15m MANAGEMENT入口状态、4H PID。监控 `status.json`：`continuity` 必须为 `HEALTHY`，`observed_ms`不得超过30秒未更新，`stale_kline_channels`与`stale_price_channels`必须为空；其他状态均告警。K线窗口拒绝“内部连续但不是最新闭合周期”的陈旧响应；WebSocket重连同时留下`GAP`与`GAP_RECOVERED.gap_ms`，不得静默抹平。公开行情可供回测输入，不等于订单/费用/候选样本完整。

回滚：`systemctl stop coinstrategy-15m-cos-archive.timer coinstrategy-15m-cos-archive.service coinstrategy-15m-market-data.service`，禁用对应自启动；保留新数据/控制库/COS对象/归档work，恢复该独立服务旧current+配置（首次安装则保持停止）。不修改管理worker/4H/交易账本。不同源合同不能直接复用scheduler workspace。存在冷对象时先按SEGMENT-SEALING.md恢复全本地再运行不支持分段的旧读者。

重要容量限制：不能以连续性为由取消2GiB保护或清理未验证数据。生产连续运行依赖已有人为书面授权、限定绝对路径且逐代COS验哈的release流程；没有该授权时保持数据并允许容量保护停采。

整合mdqueue-b时保留分批重订阅、ReconnectGovernor、DEPTH catch-up和分层heartbeat。`GAP_RECOVERED`仅表示本次连接尝试已收到有效行情；`gap_ms`从该次CONNECT_OR_RECONNECT开始，不包含此前退避等待，也不表示全池恢复或历史补齐。控制消息及队列溢出不能触发恢复标记。发布只替换data unit；archive仍随独立current加载，已冻结的maintenance/release unit与授权保持原样，并核验新旧sealing读写兼容，不盲复制全部unit。
