// 已废弃 — 新架构下 web 不再透传 /smart-wallet/** 的 POST/PUT/PATCH/DELETE 到 job。
//
// 影响范围：除已有专门 controller 覆盖的写入路径（settlement / claim / admin /
// profile-write / accounting / follow-tasks / allowlist / blocklist / trade-events /
// trade-orders / wallet-features / clob-write / polymarket-write 等）外，
// 其他原本"未本地化、走透传"的写入路径将直接 404。
//
// 历史 INTERNAL_ONLY_PATH_PATTERNS 已不再需要——job 现在不再对外暴露任何 HTTP 端点
// （仅监听 127.0.0.1，外部无法访问），所以也不存在"暴露给前端的运维路径"风险。
//
// 详情参见同步清空的 JobApiForwardService.java 注释。
package com.metaverse.coin.strategy.web.controller.smartwallet;
