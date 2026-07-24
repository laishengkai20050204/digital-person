# Mem0 长期记忆适配与部署

## 当前范围

本批只提供基础设施和应用端口，不自动把记忆写入聊天流程，也不默认把检索结果注入模型：

- `ops/ensure-mem0.sh`：检测、下载固定版本并启动 Mem0；
- `Mem0StartupProbe`：Spring Boot 启动时检查服务；
- `Mem0PersonMemoryGateway`：实现长期记忆搜索；
- `Mem0PersonMemoryStore`：实现对话写入和单条删除；
- Mem0 不可用时，默认以无记忆模式继续运行；
- `MEM0_RETRIEVAL_ENABLED=false` 时仍使用 `NoOpPersonMemoryGateway`。

## 部署原则

Java 应用不执行 `git clone`、`docker compose` 或系统软件安装。依赖准备属于部署脚本；Java 只检查和调用 REST API。

Mem0 固定为：

```text
v2.0.4
```

可通过 `MEM0_VERSION` 显式升级。升级前必须先验证兼容性，不能自动跟随 `latest`。

## 环境变量

应用配置位于 `/etc/person-ai/person-ai.env`：

```bash
MEM0_ENABLED=true
MEM0_REQUIRED=false
MEM0_RETRIEVAL_ENABLED=false
MEM0_BASE_URL=http://127.0.0.1:8888
MEM0_API_KEY=<至少16字符的长随机值>
MEM0_CONNECT_TIMEOUT=2s
MEM0_REQUEST_TIMEOUT=30s
```

部署脚本建议使用单独的 `/etc/person-ai/mem0.env`：

```bash
MEM0_AUTO_INSTALL_ENABLED=true
MEM0_VERSION=v2.0.4

# 记忆提取模型，可直接复用当前 OpenRouter 配置
MEM0_LLM_API_KEY=<OpenRouter 或其他 OpenAI 兼容接口密钥>
MEM0_LLM_BASE_URL=https://openrouter.ai/api/v1
MEM0_LLM_MODEL=<OpenRouter 模型 ID>

# 向量嵌入必须单独配置；OpenRouter 不提供 embeddings API
MEM0_EMBEDDER_API_KEY=<embedding 服务密钥>
MEM0_EMBEDDER_BASE_URL=<OpenAI 兼容 embedding 接口>
MEM0_EMBEDDER_MODEL=<embedding 模型 ID>

MEM0_API_KEY=<与应用配置相同的值>
MEM0_JWT_SECRET=<长随机值>
```

文件必须限制权限：

```bash
sudo chown root:deploy /etc/person-ai/mem0.env
sudo chmod 640 /etc/person-ai/mem0.env
```

如果 `MEM0_API_KEY` 未提供，安装脚本会生成一个，并写入：

```text
/opt/person-ai/mem0/generated-admin-api-key
```

生成后仍需把相同值配置进应用的 `MEM0_API_KEY`，否则受保护的记忆接口会返回 `401`。

## 模型与向量服务

Mem0 的记忆提取 LLM 和向量嵌入是两套独立依赖。部署脚本会在服务启动后通过受保护的 `/configure` 接口写入两套配置：

- LLM 可复用数字人现有的 OpenRouter `LLM_API_KEY`、`LLM_BASE_URL` 和 `LLM_MODEL`；
- embedding 必须配置 `MEM0_EMBEDDER_*`；
- 不能把 OpenRouter 当作 embedding 接口；
- 如果 LLM 和 embedding 都使用官方 OpenAI，`MEM0_EMBEDDER_API_KEY` 可省略并复用 LLM Key。

配置会由 Mem0 持久化到自身数据库；数据库和环境文件都必须按敏感凭据保护。即使 Mem0 已经运行，后续部署也会重新调用 `/configure`，使模型或 embedding 配置变更生效。

## 首次准备

自动部署会调用：

```bash
bash ops/ensure-mem0.sh
```

也可以先手工执行：

```bash
cd /opt/person-ai/builds/<commit>
bash ops/ensure-mem0.sh
```

检查：

```bash
curl -fsS http://127.0.0.1:8888/auth/setup-status | jq
curl -fsS http://127.0.0.1:8080/actuator/health | jq
```

## 开关语义

```text
MEM0_ENABLED=false
不创建 Mem0 客户端、启动探针和写适配器。

MEM0_ENABLED=true
创建 Mem0 客户端、写适配器、启动探针和可选健康组件。

MEM0_RETRIEVAL_ENABLED=false
暂不把 Mem0 搜索结果送入人物上下文。

MEM0_RETRIEVAL_ENABLED=true
用 Mem0PersonMemoryGateway 替换 NoOpPersonMemoryGateway。

MEM0_REQUIRED=false
Mem0 故障时应用继续运行，健康组件保持整体 UP 并标记 available=false。

MEM0_REQUIRED=true
启动检测失败时拒绝启动，健康组件报告 DOWN。
```

生产第一阶段建议：

```bash
MEM0_ENABLED=true
MEM0_REQUIRED=false
MEM0_RETRIEVAL_ENABLED=false
```

先验证写入和搜索，再打开检索注入。

## Mem0 REST 映射

```text
PersonMemoryQuery        -> POST /search（数量字段为 `top_k`）
PersonMemoryWriteRequest -> POST /memories
PersonMemoryStore.delete -> DELETE /memories/{memory_id}
```

数字人物隔离使用：

```json
{
  "filters": {
    "agent_id": "<personId>"
  }
}
```

写入时同样使用 `agent_id=<personId>`。记忆分区从 `metadata.section` 映射到 `MemorySection`；缺失或未知值按 `EPISODIC` 处理。

## 下一批

下一批再实现：

1. 对话完成后的异步记忆写入；
2. 写入失败补偿队列；
3. 影子检索日志和质量评估；
4. 检索结果 token 限制；
5. 验证后打开聊天 Prompt 注入。
