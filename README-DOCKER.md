# 快速开始

```bash
# 1. 准备环境变量
cp .env.production.example .env.production
# 编辑 .env.production，至少填好：
#   MYSQL_ROOT_PASSWORD / JWT_SECRET / DEEPSEEK_API_KEY / PUBLIC_BASE_URL

# 2. 构建并启动
docker compose build
docker compose up -d

# 3. 验证
docker compose ps                  # 应该都 healthy
curl http://localhost/api/health   # 返回 {"status":"UP",...}
# 浏览器打开 http://localhost 即可
```

停止：`docker compose down`  （保留数据）
销毁（⚠️ 数据清空）：`docker compose down -v`

完整文档：[docs/deploy-tencent-cloud.md](./docs/deploy-tencent-cloud.md)
