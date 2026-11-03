# 15m数据专用部署

用于公开15min/60min K线及4hour上下文、BBO、深度、逐笔、公开资金费率和合约规格采集；不加载交易env、不接数据库、不生成订单。独立目录、服务和版本，不更换已有MANAGEMENT_ONLY worker或其绑定许可。

1. 从确切已提交源码 `release/package.py build --paper --realtime`，verify后冻结tar及SHA。复制到独立 `/opt/coinstrategy/15m-market-data/releases/<version>`，原子切独立current；禁止切共享htx-trend-py/current。
2. 为该数据服务创建独立venv，安装包声明依赖并冻结pip清单；不得假设旧交易venv包含WebSocket库。`/etc/coinstrategy/15m-cos-release.env`只包含`HTX_PUBLIC_CAPTURE_RELEASE_ID=<version>`，每次启动有COLLECTION_SESSION事件记录版本。固定现有15m池到 `/etc/coinstrategy/15m-cos-pool.json`。保留contracts、原池依据、采集提交及包SHA。同一root恢复要求池/观测依据/容量/磁盘下限一致；未来改池或版本要显式规划源连续性，不能静默复用不同身份。
3. 安装本目录data service。固定root `/home/ubuntu/coinstrategy-data/15m-cos/capture`，`--segmented --resume` 可在正常停止/24h轮次后原目录续采。旧段/原capture.json保留，status的counts仅指本进程。源硬限制仍2GiB数据、根盘最低5GiB；达到保护条件退出2，systemd不自动反复重试，也不删除文件。
4. 首次目录由data进程初始化成功后，再启动COS服务。COS配置单源 `kind=segments` 指向capture/segments，独立workspace；schedule.enabled=true、interval_seconds=900、audit_seconds=604800。COS service只写该源的.sealing和归档workspace，行情对象只读。不能照抄通用模板而漏掉.sealing写权限。
5. 检查52池各类采集覆盖、真实K线窗/时钟/来源异常。sealed阈值64MiB/20000对象，退出时尝试封存不足阈值的末段，仍须通过consumer及证据故障检查；一次人工封存可用于部署验收。timer每轮最多上传4代，不释放原数据。
6. 下载实际manifest段并用public_capture.read_reference/segment_codec读取，校验SHA和K线/盘口时间；记录版本/包/池/配置/unit SHA及15m MANAGEMENT入口状态、4H PID。公开行情可供回测输入，不等于订单/费用/候选样本完整。

回滚：`systemctl stop coinstrategy-15m-cos-archive.timer coinstrategy-15m-cos-archive.service coinstrategy-15m-market-data.service`，禁用对应自启动；保留新数据/控制库/COS对象/归档work，恢复该独立服务旧current+配置（首次安装则保持停止）。不修改管理worker/4H/交易账本。不同源合同不能直接复用scheduler workspace。存在冷对象时先按SEGMENT-SEALING.md恢复全本地再运行不支持分段的旧读者。

重要容量限制：本次没有授权本地历史删除，上传后本地仍占用；2GiB上限触发时需要先核验并批准具体释放清单才能继续，不能以部署名义取消保护或自动清理。
