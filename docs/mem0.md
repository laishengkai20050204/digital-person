# Mem0 长期记忆部署与测试

本文记录 Digital Person 当前使用的自托管 Mem0 架构、服务器部署方式、Java 配置、测试接口和已知故障处理。目标是让 Mem0 的安装、恢复和端到端验证不依赖聊天功能。

## 1. 当前架构

```text
Digital Person Java 服务
├── MySQL 8.4
│   └── 人物聚合、人格、短期状态、事件时间线、活动效果和调度状态
└── Mem0 HTTP API（127.0.0.1:8888）
    └── PostgreSQL + pgvector（127.0.0.1:8432）
        └── 长期记忆、元数据、embedding 和向量索引
```

MySQL 与 PostgreSQL 当前不是重复存储：MySQL 是 Digital Person 的核心业务主库，PostgreSQL 是 Mem0 自己的存储依赖。短期内保留两者；若未来必须统一数据库，应评估把 Digital Person 主库迁移到 PostgreSQL，而不是让 Mem0 改用 MySQL。

当前固定 Mem0 源码版本：

```text
v2.0.4
```

## 2. Java 侧能力

Java 侧通过项目自己的 provider-neutral 端口访问长期记忆：

```text
PersonMemoryStore
├── add
└── delete

PersonMemoryGateway
└── retrieve
```

运行时实现：

```text
Mem0PersonMemoryStore
Mem0PersonMemoryGateway
```

配置开关：

```bash
MEM0_ENABLED=true
MEM0_REQUIRED=false
MEM0_RETRIEVAL_ENABLED=true
MEM0_BASE_URL=http://127.0.0.1:8888
MEM0_API_KEY=replace-with-admin-api-key
MEM0_CONNECT_TIMEOUT=2s
MEM0_REQUEST_TIMEOUT=30s
MEM0_HEALTH_PATH=/auth/setup-status
```

含义：

- `MEM0_ENABLED=true`：启用 Mem0 客户端、启动探针和对话记忆写入。
- `MEM0_RETRIEVAL_ENABLED=true`：把检索到的长期记忆注入人物模型上下文。
- `MEM0_REQUIRED=false`：Mem0 临时不可用时，主服务仍可启动和继续运行。

推荐生产配置继续使用 `MEM0_REQUIRED=false`，避免长期记忆基础设施故障拖垮人物核心服务。

## 3. Mem0 服务端环境文件

建议单独保存于：

```text
/etc/person-ai/mem0.env
```

示例字段：

```bash
MEM0_ENABLED=true
MEM0_AUTO_INSTALL_ENABLED=true
MEM0_VERSION=v2.0.4
MEM0_INSTALL_DIR=/opt/person-ai/mem0

MEM0_LLM_API_KEY=replace-with-llm-key
MEM0_LLM_BASE_URL=https://openrouter.ai/api/v1
MEM0_LLM_MODEL=provider/model-name

MEM0_EMBEDDER_API_KEY=replace-with-embedding-key
MEM0_EMBEDDER_BASE_URL=https://api.openai.com/v1
MEM0_EMBEDDER_MODEL=text-embedding-3-small

MEM0_API_KEY=replace-with-long-random-admin-key
MEM0_JWT_SECRET=replace-with-long-random-secret
```

注意：OpenRouter 目前不能作为 embedding 服务使用。记忆提取模型与 embedding 模型可以使用不同供应商和密钥。

权限建议：

```bash
sudo chown root:deploy /etc/person-ai/mem0.env
sudo chmod 640 /etc/person-ai/mem0.env
```

不要把实际密钥提交到 Git 仓库，也不要将环境文件内容粘贴到日志或工单。

## 4. 自动准备脚本

仓库脚本：

```text
ops/ensure-mem0.sh
```

推荐从 `deploy` 可以访问的目录执行。不要在 `/home/ubuntu` 中直接以 `deploy` 用户运行 Docker Compose，否则 Compose 可能检查当前目录 `.` 并返回 `stat .: permission denied`。

```bash
sudo -u deploy -H bash -lc '
set -euo pipefail
cd /opt/person-ai

git --git-dir=/opt/person-ai/source.git \
  show refs/remotes/origin/main:ops/ensure-mem0.sh \
  | bash
'
```

脚本负责：

1. 读取 `/etc/person-ai/person-ai.env` 与 `/etc/person-ai/mem0.env`。
2. 拉取并校准 Mem0 `v2.0.4`。
3. 生成受保护的 Mem0 `.env`。
4. 启动 PostgreSQL 和 Mem0 API。
5. 调用 `/configure` 写入 LLM 与 embedding 配置。
6. 等待 `/auth/setup-status` 健康检查通过。

当前 Digital Person 不依赖 Mem0 dashboard，因此自动部署只需要启动 `mem0` 服务及其 PostgreSQL 依赖，不需要构建 dashboard。

## 5. Docker 权限

部署用户需要访问 Docker socket：

```bash
sudo usermod -aG docker deploy
```

验证：

```bash
sudo -u deploy -H bash -lc '
id
docker version
docker compose version
docker ps
'
```

若仍提示 Docker socket 权限错误：

```bash
sudo systemctl restart docker.socket docker
sudo -u deploy -H docker ps
```

加入 `docker` 组基本等同于授予该用户 root 级系统控制能力，因此只能给受控的部署账户使用。

## 6. 容器与端口

只启动后端：

```bash
sudo -u deploy -H bash -lc '
cd /opt/person-ai/mem0/source/server
docker compose up -d --build mem0
docker compose ps
'
```

预期容器：

```text
mem0-dev-mem0-1
mem0-dev-postgres-1
```

端口必须只监听本机：

```text
127.0.0.1:8888 -> Mem0 API 8000
127.0.0.1:8432 -> PostgreSQL 5432
```

检查：

```bash
sudo ss -lntp | grep -E ':8888|:8432'
```

不应出现：

```text
0.0.0.0:8888
0.0.0.0:8432
```

Mem0 和 PostgreSQL 不需要直接暴露到公网。

## 7. 健康检查

Mem0：

```bash
curl -fsS http://127.0.0.1:8888/auth/setup-status | jq
```

容器：

```bash
sudo -u deploy -H bash -lc '
cd /opt/person-ai/mem0/source/server
docker compose ps
docker compose logs --tail=200 mem0 postgres
'
```

Java：

```bash
curl -fsS http://127.0.0.1:8080/actuator/health | jq
```

启动日志应包含：

```text
Mem0 memory service is available: baseUrl=http://127.0.0.1:8888, retrievalEnabled=true
```

## 8. 临时 Mem0 测试 API

在正式对话链路完成前，可以使用独立测试 API 验证写入、检索和删除。接口默认关闭，并使用独立令牌，不复用人物 API 令牌。

启用：

```bash
MEMORY_TEST_API_ENABLED=true
MEMORY_TEST_API_TOKEN=replace-with-long-random-token
```

重启：

```bash
sudo systemctl restart person-ai
```

所有请求必须携带：

```text
X-Internal-Token: replace-with-long-random-token
```

### 8.1 写入或提取记忆

```bash
PERSON_ID='567f1d4e-2aab-427b-a4ca-dd69a00c06df'
TOKEN='replace-with-long-random-token'

curl -fsS -X POST \
  -H "X-Internal-Token: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [
      {"role": "USER", "content": "我最喜欢科幻电影"},
      {"role": "ASSISTANT", "content": "我记住了"}
    ],
    "metadata": {
      "section": "PREFERENCE",
      "source": "manual-memory-test"
    },
    "infer": true
  }' \
  "http://127.0.0.1:8080/internal/memory-test/persons/$PERSON_ID/memories" \
  | jq
```

`infer=true` 表示允许 Mem0 从消息中提取、更新或删除长期记忆。响应中的 `event` 通常为 `ADD`、`UPDATE` 或 `DELETE`。

支持的消息角色：

```text
USER
ASSISTANT
SYSTEM
```

### 8.2 检索记忆

```bash
curl -fsS -X POST \
  -H "X-Internal-Token: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "用户喜欢什么电影",
    "sections": ["PREFERENCE"],
    "maxItems": 5
  }' \
  "http://127.0.0.1:8080/internal/memory-test/persons/$PERSON_ID/search" \
  | jq
```

支持的 section：

```text
IDENTITY
RELATIONSHIP
PREFERENCE
GOAL
PLAN
COMMITMENT
EPISODIC
USER_PROFILE
ROUTINE
CONVERSATION_SUMMARY
```

不传 `sections` 或传空数组表示不按 section 过滤。`maxItems` 默认值为 `5`。

### 8.3 删除记忆

从写入或检索响应中取得 `memoryId`：

```bash
MEMORY_ID='memory-id-from-response'

curl -fsS -X DELETE \
  -H "X-Internal-Token: $TOKEN" \
  "http://127.0.0.1:8080/internal/memory-test/memories/$MEMORY_ID" \
  -o /dev/null -w '%{http_code}\n'
```

成功返回：

```text
204
```

该删除接口是受令牌保护的管理测试接口，按 memory ID 删除，不代表正式产品权限模型。

## 9. 测试 API 错误码

```text
401 UNAUTHORIZED
400 INVALID_REQUEST
503 MEMORY_TEST_UNAVAILABLE
502 MEMORY_PROVIDER_FAILED
```

- `MEMORY_TEST_UNAVAILABLE`：Mem0 写入未启用，或检索开关未启用。
- `MEMORY_PROVIDER_FAILED`：Mem0、LLM 或 embedding 供应商未能完成请求。
- 供应商响应正文和用户记忆内容不会通过错误消息回传。

测试结束后应关闭接口：

```bash
MEMORY_TEST_API_ENABLED=false
```

## 10. 已处理的部署故障

### `stat .: permission denied`

原因：`sudo -u deploy` 继承了 `/home/ubuntu` 作为当前工作目录，而 `deploy` 无权进入该目录。解决：执行脚本前 `cd /opt/person-ai`，或使用其他 `deploy` 可访问目录。

### Docker API permission denied

原因：`deploy` 无权访问 `/var/run/docker.sock`。解决：将受控部署用户加入 `docker` 组，并重新验证 `docker ps`。

### dashboard 构建出现 `node:sqlite`

Mem0 v2.0.4 dashboard Dockerfile 使用 Node 20，但 Corepack 可能下载要求 Node 22.13+ 的 pnpm 11，进而出现：

```text
ERR_UNKNOWN_BUILTIN_MODULE: No such built-in module: node:sqlite
```

Digital Person 不使用 dashboard，因此不应为了启动长期记忆后端构建 dashboard；只执行：

```bash
docker compose up -d --build mem0
```

### Spring 启动找不到 Jackson `ObjectMapper`

项目使用 Spring Boot 4.1 和 Jackson 3。Mem0 适配器已迁移到 `tools.jackson.databind.json.JsonMapper`，避免要求 Jackson 2 `ObjectMapper` Bean。

### `PersonMemoryGateway` 出现两个 Bean

检索开启后，默认 No-Op 网关与 Mem0 网关可能同时注册。Mem0 网关已标记为 `@Primary`，确保 `MEM0_RETRIEVAL_ENABLED=true` 时选择真实 Mem0 实现。

## 11. 运维建议

- 继续保持 `MEM0_REQUIRED=false`。
- Mem0 API、PostgreSQL 和测试 API都不要直接暴露公网。
- 测试 API 使用独立长随机令牌，测试结束后关闭。
- 定期备份 Docker volume `mem0-dev_postgres_db`。
- 升级 Mem0 版本前，先验证 Compose、数据库迁移、REST API 和 dashboard 构建变化。
- 不要直接编辑生产 volume 内的数据；记忆写入、检索和删除应通过 Mem0 API 或 Java 端口完成。
