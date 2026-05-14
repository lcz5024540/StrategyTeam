# Job / Web 服务拆分方案

> 日期：2026-05-12
> 目标：将 `coin-strategy-job` 收敛为策略运行服务，将页面查询、检查与运维入口统一收敛到 `coin-strategy-web`

## 目标边界

### job 服务

1. 负责策略调度、数据处理、持仓同步、信号执行。
2. 仅作为内部运行节点存在。
3. 默认只监听 `127.0.0.1:8081`。

### web 服务

1. 作为统一对外 HTTP 入口。
2. 承接前端页面、查询接口、修复入口、运维检查入口。
3. 默认只监听 `127.0.0.1:8080`，由 nginx 对外反代。

## 当前实现策略

受当前仓库约束影响：

1. `coin-strategy-job` 使用 Spring Boot `2.7.x`
2. `coin-strategy-web` 使用 Spring Boot `3.1.x`

这意味着不能直接把 `job` 中的大量 Spring service/DTO 无成本挪到 `web` 复用。因此本次先落地 **Phase 1 过渡架构**：

1. nginx 只对外暴露 `web`
2. `web` 提供自身页面接口
3. `web` 对 `/smart-wallet/**`、`/strategy-platform/**`、`/strategy-engine/**` 等入口做统一代理
4. `job` 继续保留现有接口用于内网调用

这样先完成：

1. 双服务同机部署
2. 外部调用入口切换到 `web`
3. 后续逐步迁移查询/修复逻辑时，不影响前端和运维入口

## 分阶段方案

### Phase 1：入口拆分与双服务部署

本次已完成：

1. `web` 增加统一 API 代理入口。
2. `web` 配置中显式增加 `dashboard.job-base-url`。
3. 发布包同时包含：
   - `backend/app.jar` 作为 job jar
   - `web/app.jar` 作为 web jar
4. systemd 拆成：
   - `smart-wallet-backend`
   - `smart-wallet-web`
5. env 拆成：
   - `smart-wallet-job.env`
   - `smart-wallet-web.env`
6. nginx 统一代理到 `web:8080`

### Phase 2：查询/修复逻辑下沉到 web / 公共模块

下一阶段要做的事：

1. 把 `strategy-platform` 查询服务抽到共享模块或在 `web` 重写。
2. 把 `smart-wallet` 查询类接口、报表类接口、修复类接口逐步迁到 `web`。
3. `web` 自己直连 DB 或依赖抽取后的只读/运维服务。
4. 逐步减少 `web -> job HTTP` 代理范围。

### Phase 3：job 完全去掉查询/修复 Controller

最终形态：

1. `job` 不再对外暴露查询、修复、报表类 Controller。
2. 仅保留运行必需的内部能力，必要时改为内部事件/任务触发。
3. `web` 成为唯一对外 API 服务。

## 建议迁移顺序

1. 先迁 `strategy-platform` 查询接口。
2. 再迁 `smart-wallet` 页面直接依赖的查询接口。
3. 再迁修复/回填/重算类入口。
4. 最后清理 `job` 中冗余 Controller。

## 验收标准

### 本阶段

1. 同一台服务器上同时启动 `smart-wallet-backend` 与 `smart-wallet-web`。
2. nginx 只代理到 `web`。
3. 前端和运维检查统一调用 `web` 域名入口。
4. `web` 能透明访问当前依赖的 `job` API。

### 最终阶段

1. `job` 不再承接查询和修复类对外接口。
2. `web` 成为唯一外部 API 入口。
3. 页面查询、修复、检查均不再依赖 `job` Controller。
