// 已废弃 — 新架构：web 不再通过 HTTP 转发任何请求给 job。
// web 与 job 共享同一个 MySQL，业务接口由 web 端直接读写 DB；
// job 仅作为定时/后台任务的执行者，对外不开放任何接口。
//
// 历史用途：
//   - 将 /strategy/api/binance/** 透传到 job:8081
//   - 将 /strategy/api/smart-wallet/** 未本地化的 GET/POST/PUT/PATCH/DELETE 透传到 job:8081
//
// 已同步清空的关联文件（保留为 stub 防止编译失败）：
//   - JobApiProxyController.java
//   - SmartWalletReadProxyController.java
//   - SmartWalletWriteProxyController.java
//   - DashboardProperties.java
//
// 若前端在迁移期发现某些路径回退 404，说明该接口此前是靠 proxy 透传到 job，
// 必须在 web 端补本地实现（或确认对应能力下线）。
package com.metaverse.coin.strategy.web.service;
