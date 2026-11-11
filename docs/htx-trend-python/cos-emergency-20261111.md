# 公共采集与COS应急修复 · 2026-11-11

基线 `support_htx_python@04e61815`，含已合并retention；变更分支 `codex/cos-capture-emergency-1111`。范围只含独立公共采集及其本地释放维护，不改LIVE交易策略、准入或池配置。

## 根因与修复

| 问题 | 现场证据 | 变更与边界 |
|---|---|---|
| 单币空盘口拖累52币连接 | 45秒公共探针：BOME bid/ask=null触发TypeError，depth bids/asks=[]触发ValueError；其他币12447 BBO/4159 DEPTH/238 TRADE | 仅公共BBO/DEPTH正常化边界将明确数据异常分类为InvalidPublicMarketData；留SOURCE_ERROR后继续同socket。不制造报价、不修改feed.py、网络/队列/存储故障仍原处理 |
| 无新释放对象却反复生成审批计划 | release-control共3125计划550436864字节，epoch856有919份335069184字节 | 既有outstanding验哈/身份/INTENT检查后，当前local全部仍被pin则不新建plan；解pin重新preview，继续原COS/pin/指纹检查；旧审批历史不删除 |
| 小批次长期留本地 | OPEN不足64MiB，5分钟上传检查反复无SEALED | 仅capture-public到900秒后下一次可用安全边界封代；非空/锁/交接条件仍适用，空代不生成。仍需等待上传，非15分钟必已到COS；runtime/PAPER规则不改 |
| 固定最大成员预留阻断小包归档/读回 | 首次上线后真实SEALED860上传报archive workspace free-space guard；磁盘仍高于5GiB，但不足5GiB+512MiB | 上传按实际stored写入量检查；完整pack读回逐块检查、无缓冲缓存并保留512MiB读取硬上限/EOF完整SHA；下载后再检查member+raw空间；catalogue按原始SQLite大小两倍+1024保守预留。不降低min_free、不删除未验数据 |

无效行情原始小报文每频道最多保存一条/分钟，完整拒绝计数在status；样本超过4096字符截断并标记。NaN/Infinity/溢出浮点保存为明确标注的文本样本，避免严格JSON记录器被诊断数据毒化；不放宽行情数值校验。

## 验证

先红后绿：公共15分钟封代、100次PINNED重复tick、真实空BBO/空depth、缺字段/短数组/非法数值、坏币后正常ETH和ping/pong继续处理、非有限数值诊断持久化。

回归保留：PUBLIC/RUNTIME区别、重启期限不重置、失败handoff、空代、解pin释放、INTENT原plan恢复、篡改与外源拒绝、cursor公平性、网络错误和队列溢出。首次全套1496 passed/13 skipped；非有限数值补测后定向113 passed，最终全套另随交付证据记录。未引入依赖。

五轴自审：正确性限制在公开输入与安全的“少做一次preview”；测试含原异常样本和持久化；设计复用原锁/验哈/审批状态；风格沿用模块；性能通过按频道限频避免异常日志泛滥。超过100行主要为回归矩阵，按用户“打包应急解决”保留同一可回退发布。Explore复核发现非有限诊断落盘问题，交付前补红绿测试修正。

源码关系：`Capture._pump_socket→normalize_ws→SOURCE_ERROR`（只有坏数据旁路）；`SegmentRecorder.segment_boundary→Control.seal`（时间/字节/文件数量任一到期）；`tick→outstanding→blocked_without_new_plan→preview/apply`（无新对象即保留原审批）。当前无可用codegraph工具，以精确源码及调用点复核替代，不声称生成图谱。

[安全边界图](diagrams/cos-emergency-20261111.drawio)。部署只切独立data current/release env及维护current，保留旧版本和配置备份；不重启交易服务、不删旧计划、行情或COS对象。真实上线结果由治理仓应急计划记录。

## 真实验收追加修复

87924978在17:20上线后，52币FRAME齐全、102/104盘口频道曾恢复、BOME坏盘口被隔离；不把“曾收到”冒充严格2秒新鲜度全部通过。17:21封代860后暴露固定512MiB预留，追加6条回归覆盖低余量正常完成、低于底线拒绝、真实写入前拒绝、下载中空间下降、缓存完成后二次目标盘检查、catalogue外尾部篡改整包验哈失败。上传/封代/释放定向89通过；全套结果以治理交付记录为准。

5GiB保持原配置；这是每次payload写入的准入检查，不是跨进程磁盘空间预留锁，SQLite inventory/journal及小元数据仍有独立开销，不能宣称并发写入下绝不越线。归档/释放失败始终不生成成功释放许可。维护service原PYTHONPATH固定869660ab，第二次部署必须只为该维护service显式指向新独立data库，不能仅切data current就声称读回代码生效。

短写复核：无缓冲FileIO.write可能返回小于请求的长度且不抛异常，两处pack写入均检查返回长度并在短写时抛ArchiveError。两个回归先红后绿，确保上传不发布manifest、恢复不写完成标记。最终定向91通过、全套1507通过/13跳过（74.44秒）。
