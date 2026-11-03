# 首次部署验收与修正
初始数据包d2a64d34已完成SHA校验/冻结/独立service安装；仅REST产生52个FRAME，0盘口。真实journal确认旧交易venv未装websockets，stream_loop线程导入异常而主进程继续。未把PID存在当成完整采集成功。
已停止仅本次新建的数据服务，保持15m MANAGEMENT PID2213018和4H PID705510。新增独立public-data-20261103 venv、pip check/freeze；代码主线程预检依赖并检测线程意外退出。41定向测试、1082全回归通过/7隔离数据库跳过。初始K线数据、已上传manifest7423fe89保留，时间段标明无盘口。修正包须另打、另冻结，不覆盖旧版本。
