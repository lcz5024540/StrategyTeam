// 已废弃 — 新架构下 web 不再透传 /smart-wallet/** 的 GET 到 job。
//
// 原 MIGRATED_PATH_PATTERNS 是"已本地化"白名单，落到本类即 404；其余未迁移路径走透传。
// 新架构下整个 fall-through 被切断，任何未在 web 端有具体 controller 的 GET 都会 404，
// 这相当于一次性强制完成 Job → Web 的查询下沉迁移。
//
// 部署后需要重点 smoke test：前端的所有 /smart-wallet/** GET 路径，确认全部命中 web 本地实现，
// 没有命中的 → 在 web 模块新建对应 controller + service + mapper 直查 DB。
//
// 详情参见同步清空的 JobApiForwardService.java 注释。
package com.metaverse.coin.strategy.web.controller.smartwallet;
