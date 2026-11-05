# A/B场景验证证据索引

2026-11-05，Codex。仅本地候选与公开数据，不含私钥或生产交易指令。

- baseline.json / protocol.json：实际分支基线与用户批准规则。
- code-relations-final.json：最终Python AST影响范围、源码签名与4个真实账户fixture；不是codegraph输出。
- source-quality-audit.json：v2真实数据缺口逐条复核及首次报价漏单修复的真实ADA片段验证。
- recovery-final.json：最终源码下47万条真实事件，断点恢复与同前缀完整重放相同。
- test-summary-final.json / python-tests-final.log / mysql-tests.log：1295项全量通过，其中10项隔离MySQL。
- public-api.json：实际HTX公开规格/K线/盘口读取。
- flow.drawio / flow.png：规则、唯一执行writer、退出与离线概率流程图。
- packages.json：最终6包构建与校验清单（关闭入口）；本地制品路径和摘要。
- night-v3/：最终研究的协议、结果、逐笔、后验、FRAME审计与报告副本。

code-relations.json、recovery.json、test-summary.json和python-tests.log是修复前中间检查，保留审计；不替代final文件。v1中断目录和v2已识别缺陷结果保留在外部research-results目录，不作为最终策略结论。
