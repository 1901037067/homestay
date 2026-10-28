# 前端镜像：构建产物交给 nginx 静态托管，/api 反代到后端容器。
# 用 nginx 而不是 `vite preview`：后者是开发服务器，不适合当部署入口。
FROM node:22-alpine AS build
WORKDIR /app
# 与后端镜像里的 aliyun maven 镜像同一个理由：容器内的 DNS 往往拿不到外网直连，
# npm 官方源会 ETIMEDOUT（实测 288 秒后失败）。能直连的机器用
#   docker compose --profile app build --build-arg NPM_REGISTRY=https://registry.npmjs.org
# 覆盖回去即可
ARG NPM_REGISTRY=https://registry.npmmirror.com
RUN npm config set registry "$NPM_REGISTRY"
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
