# 前端镜像：Node 构建静态文件 → nginx 托管，并把 /api、/ws 反代到网关（与 vite 开发代理行为一致）
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
ENV GATEWAY_URL=http://gateway:8080
EXPOSE 80
