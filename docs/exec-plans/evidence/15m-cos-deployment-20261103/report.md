# 15m无交易采集 / COS部署交付

已部署独立15m公开数据服务，当前包 **0.1.7-realtime-candidate-869660ab**，代码提交869660ab0b657a71e9dd0b1c1258bbf4b81f5e32。未启动PAPER或LIVE策略worker，不加载账户凭据。原MANAGEMENT_ONLY 2502786d/入口false与4H均保持。

## 版本与数据

- 包SHA256：0401b4d84e3ff814fdefc1bccb4ac5b375485911876b3cb484ffc716afe0e70d。
- 冻结：/workspace/frozen/0.1.7-realtime-candidate-869660ab/，含tar、sidecar SHA、MANIFEST、BUILD。
- 独立current：/opt/coinstrategy/15m-market-data/current；独立venv：/opt/coinstrategy/15m-market-data/venvs/public-data-20261103，依赖锁见requirements.freeze.txt。不修改共享htx-trend-py/current或venv。
- 数据：/home/ubuntu/coinstrategy-data/15m-cos/capture；COS桶rayquant-strategy-1253235498，前缀coinstrategy/archive/v1/。仍按不可变manifest和引用恢复。
- 池取原15m的52合约。capture.json/pool.json保留首轮来源，当前版本以MANIFEST、status.release和每次COLLECTION_SESSION事件为准，避免改写历史采集身份。

## 实际验收

23:27 CST快照：52/52合约已有FRAME、BBO、DEPTH、TRADE、FUNDING记录；trading=false、stop_reason=null。FRAME保存15min/60min及4hour上下文，三个周期样本各160根。行情通道曾到达不等于每一时刻均新鲜；实际时间与缺口仍随原文记录。

原目录重启：PID2360780→2361464；对象171→183；原capture.json哈希不变、占用不清零。COS第3代manifest117202d987dc3e96794556fa697741d6b68b288fa831a446b824a393b96f211a，下载3个真实段551211B并验证FRAME/DEPTH/BBO/TRADE/COLLECTION_SESSION的对象及事件SHA，结果PASS。

数据服务常驻，上传服务为独立oneshot+timer。实测数据速率较高，实际采用本目录20-data-cadence覆盖：每5分钟直接segment-archive，单次最多4个封存代，CPUQuota50%。通用配置schedule块仍是run-once模板的15分钟配置；此单源部署由systemd定时器驱动，不通过通用scheduler节流。仅.sealing和归档work可写，行情文件只读；无自动删除命令。

23:29:56 CST同一systemd上传服务已处理第4/5代，Result=success、ExecMainStatus=0、pending_generations=0、source_deleted=false；下一轮定时为23:35:09 CST。

1082项Python回归通过，7项隔离MySQL测试未启；41项采集/分段定向测试通过。首次d2a64d34包使用旧venv缺websockets，验收识别为REST_ONLY_INCOMPLETE；已停下修复，未把该阶段当完整盘口或交易样本。DL-083记录依赖/线程退出误报及回归。

## 容量与回滚

23:27根盘75%，余15G，内存available2067MiB；新采集数据约133.4MiB。**本轮未授权/未执行本地历史删除**，COS上传不自动减少本地占用；仍保留2GiB数据上限及5GiB根盘余量保护，触发时会停采，需要后续核验、批准具体释放清单。不能宣称本地容量无限或完整交易回测准入已通过。

1. 回滚时停止并禁用coinstrategy-15m-market-data.service和coinstrategy-15m-cos-archive.timer；停止尚在运行的archive.service。
2. 保留capture、.sealing、COS对象、冻结包、配置、venv与归档work。第一次完整部署的安全回滚是保持这些数据服务停止，不回到已知缺WS的d2首轮配置。
3. 不动15m管理worker、4H、共享collector、MySQL、paper-state或原evidence。任何后续更换池/源路径/释放历史均使用对应可审计流程。

证据：final-package.json、installation.json、final-runtime.json、restart-verification.json、cos-download-verification.json、upload-service-verification.txt及两个cadence覆盖文件。
