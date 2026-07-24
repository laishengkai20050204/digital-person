# 正式人物对话 API

本文记录 Digital Person 的正式用户对话入口、运行条件、上下文组装、长期记忆写回和错误处理。

## 1. 接口

```http
POST /api/persons/{personId}/dialogues
```

请求头：

```text
X-Internal-Token: PERSON_API_TOKEN
Content-Type: application/json
```

请求正文：

```json
{
  "message": "你还记得我喜欢什么电影吗？"
}
```

响应示例：

```json
{
  "personId": "567f1d4e-2aab-427b-a4ca-dd69a00c06df",
  "replies": [
    "当然记得，你最喜欢科幻片。"
  ],
  "occurredAt": "2026-07-25T01:00:00Z",
  "memoryStatus": "PROCESSED",
  "memoryMutationCount": 1
}
```

`decisionSummary` 不通过 HTTP 暴露。客户端只能看到最终人物回复。

## 2. 启用条件

正式对话要求：

```bash
PERSON_API_ENABLED=true
PERSON_API_TOKEN=replace-with-long-random-token
MYSQL_PERSISTENCE_ENABLED=true
LLM_ENABLED=true
LLM_BASE_URL=https://openrouter.ai/api/v1
LLM_API_KEY=replace-with-provider-key
LLM_MODEL=provider/model-name
```

长期记忆是可选增强：

```bash
MEM0_ENABLED=true
MEM0_RETRIEVAL_ENABLED=true
MEM0_REQUIRED=false
MEM0_MINIMUM_RELEVANCE=0.30
```

`MEM0_REQUIRED=false` 时，Mem0 临时故障不会阻止人物回复。

## 3. 对话参数

```bash
DIALOGUE_MAX_MEMORY_ITEMS=8
DIALOGUE_MAX_CONVERSATION_TURNS=12
DIALOGUE_MAX_OUTPUT_TOKENS=1200
DIALOGUE_TEMPERATURE=0.7
```

含义：

- `DIALOGUE_MAX_MEMORY_ITEMS`：一次模型调用最多检索的长期记忆条数。
- `DIALOGUE_MAX_CONVERSATION_TURNS`：近期对话读取端口一次最多请求的轮次。
- `DIALOGUE_MAX_OUTPUT_TOKENS`：人物回复的最大输出 token。
- `DIALOGUE_TEMPERATURE`：直接对话模型温度，允许范围为 `0.0` 到 `2.0`。

用户单条消息最多 `16000` 个 Java 字符。

## 4. 当前调用链

```text
HTTP 用户消息
    ↓
校验 X-Internal-Token
    ↓
加载 Person 聚合
    ↓
以原始用户消息作为 relevanceSeed
    ↓
PersonModelContextAssembler
    ├── 人物身份与人格
    ├── 当前状态与活动效果
    ├── 当前和近期事件
    ├── Mem0 相关长期记忆
    ├── 近期对话读取端口
    └── 时间上下文
    ↓
LanguageModelGateway
    ↓
生成一条直接人物回复
    ↓
DialogueMemoryRecorder
    ↓
Mem0 infer=true 提取、更新或删除长期记忆
    ↓
返回 HTTP 响应
```

当前不会先调用额外模型生成 Mem0 查询参数。原始用户消息和事件上下文由 Java 组成相关性查询，再调用 Mem0 向量检索。

## 5. 长期记忆状态

响应中的 `memoryStatus`：

```text
PROCESSED
DISABLED
FAILED
```

- `PROCESSED`：Mem0 已完成本轮处理；`memoryMutationCount` 可以是 `0`，表示模型认为没有需要新增、更新或删除的长期记忆。
- `DISABLED`：当前未启用 Mem0 写入。
- `FAILED`：回复已经生成，但 Mem0 写入失败；服务采用 fail-open，仍返回人物回复。

Mem0 错误不会把用户对话正文或供应商响应正文暴露给客户端。

## 6. 测试命令

```bash
TOKEN="$(sudo sed -n 's/^PERSON_API_TOKEN=//p' /etc/person-ai/person-ai.env)"
PERSON_ID='567f1d4e-2aab-427b-a4ca-dd69a00c06df'

cat >/tmp/dialogue-request.json <<'JSON'
{
  "message": "你还记得我喜欢什么电影吗？"
}
JSON

curl -sS \
  -X POST \
  -H "X-Internal-Token: $TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/dialogue-request.json \
  "http://127.0.0.1:8080/api/persons/${PERSON_ID}/dialogues" \
  | jq
```

## 7. 错误契约

```text
401 UNAUTHORIZED
400 INVALID_REQUEST
404 PERSON_NOT_FOUND
502 DIALOGUE_GENERATION_FAILED
500 INTERNAL_ERROR
```

`DIALOGUE_GENERATION_FAILED` 不回传模型原始异常、提示词或上下文。

## 8. 近期对话现状

项目已经有 provider-neutral 的 `RecentConversationGateway` 读取端口，但当前生产默认实现仍是 `NoOpRecentConversationGateway`，尚未建立正式写入端口和持久化表。因此：

- 当前跨请求连续性主要来自 Mem0 长期记忆、人物状态和事件；
- 尚不能把最近几轮原始对话可靠保存到 MySQL；
- 不使用 JVM 内存列表冒充正式对话存储，避免重启丢失和多实例不一致。

下一阶段应增加独立的近期对话写入端口、MySQL 表、按人物分页读取和保留期清理，再替换默认 NoOp 实现。
