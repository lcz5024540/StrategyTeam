# Generated from ray-agent docs/skills/setup-cicd/templates/Dockerfile.frontend (2026-05-27)
# 填充参数：
#   <NODE_VERSION>    = 22
#   <NGINX_VERSION>   = 1.27-alpine
#   <FRONTEND_PORT>   = 80

# ========== Stage 1: build ==========
FROM node:22-alpine AS build

WORKDIR /app

# 先拷 lockfile，layer cache
COPY package.json package-lock.json ./
RUN npm ci

# 拷源码，build
COPY . .
RUN npm run build

# ========== Stage 2: runtime ==========
FROM nginx:1.27-alpine AS runtime

# 静态文件
COPY --from=build /app/dist /usr/share/nginx/html

# nginx 配置（SPA 路由 fallback）
COPY nginx.conf /etc/nginx/conf.d/default.conf

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost:80/ > /dev/null || exit 1

EXPOSE 80
