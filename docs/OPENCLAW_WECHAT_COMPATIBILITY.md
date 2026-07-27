# OpenClaw 微信兼容接口

本文说明如何让 OpenClaw 微信通道直接调用 Digital Person Java 服务，不再依赖 EchoText、SillyTavern、Playwright 或无头浏览器。

## 1. 链路

```text
微信用户消息
  ↓
OpenClaw Gateway / 微信通道
  ↓ OpenAI Chat Completions 协议
POST http://127.0.0.1:8080/v1/chat/completions
  ↓
OpenAiChatCompletionsController
  ↓
PersonDialogueService
  ├─ 人物身份、人格、状态和活动
  ├─ MySQL 近期对话与摘要
  ├─ Mem0 长期记忆
  └─ 内部 LLM
  ↓
choices[0].message.content
  ↓
OpenClaw 发送微信回复
```

OpenClaw 只承担微信收发和传输适配。人物上下文、历史、状态、记忆和回复生成全部由 Java 负责。

## 2. 接口

```http
POST /v1/chat/completions
Authorization: Bearer PERSON_API_TOKEN
Content-Type: application/json
```

请求示例：

```json
{
  "model": "shen-zhixia",
  "messages": [
    {"role": "system", "content": "OpenClaw transport prompt"},
    {"role": "user", "content": "你在干嘛？"}
  ],
  "stream": false
}
```

接口只使用最后一条 `role=user` 的文本。OpenClaw 自带的 system prompt 和历史不会重复注入 Java，因为 Java 会自行加载正式人物上下文和近期对话。

成功响应：

```json
{
  "id": "chatcmpl-...",
  "object": "chat.completion",
  "created": 1785141000,
  "model": "shen-zhixia",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "我刚刚在做作业，看到你消息就先回你了。"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 0,
    "completion_tokens": 0,
    "total_tokens": 0
  }
}
```

当前适配器只支持非流式文本请求。OpenClaw 必须配置 `streaming: false`。

## 3. Java 环境变量

在 `/etc/person-ai/person-ai.env` 中保留现有配置，并增加：

```bash
PERSON_API_TOKEN=replace-with-a-long-random-token
OPENAI_COMPAT_ENABLED=true
OPENAI_COMPAT_PERSON_ID=567f1d4e-2aab-427b-a4ca-dd69a00c06df
OPENAI_COMPAT_MODEL=shen-zhixia
```

含义：

- `PERSON_API_TOKEN`：Java 与 OpenClaw 之间共享的 Bearer Token；
- `OPENAI_COMPAT_ENABLED`：启用 `/v1/chat/completions`；
- `OPENAI_COMPAT_PERSON_ID`：所有微信消息映射到的人物 UUID；
- `OPENAI_COMPAT_MODEL`：OpenClaw 请求中必须使用的模型 ID。

现有正式人物接口是否启用仍由 `PERSON_API_ENABLED` 独立控制。兼容接口只复用其令牌配置。

重启 Java：

```bash
sudo systemctl restart person-ai.service
sudo systemctl status person-ai.service --no-pager -l
```

## 4. 先直接测试 Java

```bash
TOKEN="$(sudo sed -n 's/^PERSON_API_TOKEN=//p' /etc/person-ai/person-ai.env)"

curl -sS \
  -X POST \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json' \
  --data '{
    "model": "shen-zhixia",
    "messages": [
      {"role": "user", "content": "测试微信兼容接口"}
    ],
    "stream": false
  }' \
  http://127.0.0.1:8080/v1/chat/completions \
  | jq
```

预期：

```text
HTTP 200
choices[0].message.role = assistant
choices[0].message.content 非空
```

错误响应使用 OpenAI 风格：

```json
{
  "error": {
    "message": "Invalid API key",
    "type": "invalid_request_error",
    "code": "invalid_api_key"
  }
}
```

## 5. OpenClaw 自定义 provider

把以下内容合并到 OpenClaw 配置，不要覆盖已有微信通道配置：

```json5
{
  env: {
    PERSON_API_TOKEN: "与 Java 完全相同的令牌",
  },

  models: {
    mode: "merge",
    providers: {
      digitalperson: {
        baseUrl: "http://127.0.0.1:8080/v1",
        apiKey: "${PERSON_API_TOKEN}",
        api: "openai-completions",
        timeoutSeconds: 220,
        models: [
          {
            id: "shen-zhixia",
            name: "沈知夏",
            reasoning: false,
            input: ["text"],
            cost: {
              input: 0,
              output: 0,
              cacheRead: 0,
              cacheWrite: 0,
            },
            contextWindow: 32768,
            maxTokens: 1200,
          },
        ],
      },
    },
  },

  agents: {
    defaults: {
      model: {
        primary: "digitalperson/shen-zhixia",
      },
      models: {
        "digitalperson/shen-zhixia": {
          alias: "沈知夏",
          params: {
            streaming: false,
          },
        },
      },
      timeoutSeconds: 240,
    },
  },
}
```

OpenClaw 自定义 OpenAI-compatible provider 要同时注册：

1. `models.providers.digitalperson`；
2. `models.providers.digitalperson.models[]`；
3. `agents.defaults.model.primary`。

`baseUrl` 必须包含 `/v1`，OpenClaw 会在其后调用 `/chat/completions`。

## 6. 重启和观察 OpenClaw

```bash
systemctl --user daemon-reload
systemctl --user restart openclaw-gateway.service
systemctl --user status openclaw-gateway.service --no-pager -l
```

实时查看两端日志：

```bash
journalctl --user -u openclaw-gateway.service -f
```

另一个终端：

```bash
sudo journalctl -u person-ai.service -f
```

成功时应看到：

```text
OpenClaw 请求 http://127.0.0.1:8080/v1/chat/completions
Java 返回 HTTP 200
Java 日志出现 Dialogue memory retrieval completed
Java 随后写入 MySQL 近期对话并异步处理 Mem0
```

## 7. 当前边界

当前版本有意保持最小实现：

- 仅支持文本；
- 仅支持 `stream=false`；
- 一个兼容端点固定映射一个人物 UUID；
- 忽略 OpenClaw system、assistant 和 tool 历史，只取最后一条用户消息；
- 不提供同人物 FIFO 队列；连续消息仍可能并发处理；
- 不实现 OpenAI tool calls，因为人物工具和上下文由 Java 内部负责。

这些限制不影响先完成单人物微信对话接入。
