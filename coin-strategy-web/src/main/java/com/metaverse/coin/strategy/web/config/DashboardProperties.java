// 已废弃 — 新架构下 web 不再 HTTP 调用 job，相关配置项 dashboard.job-base-url 一并下线。
// 文件保留为空 stub，避免 IDE/构建工具因路径变化报错。删除该文件需要同时清理：
//   - application-*.yml 中的 dashboard: 配置块
//   - deploy/linux/env/* 中的 DASHBOARD_JOB_BASE_URL 环境变量
//   - 引用本类的 JobApiForwardService / *ProxyController（已同步清空）
package com.metaverse.coin.strategy.web.config;
