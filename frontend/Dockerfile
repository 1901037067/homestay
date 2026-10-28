# 前端镜像：构建产物交给 nginx 静态托管，/api 反代到后端容器。
# 用 nginx 而不是 `vite preview`：后者是开发服务器，不适合当部署入口。
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
