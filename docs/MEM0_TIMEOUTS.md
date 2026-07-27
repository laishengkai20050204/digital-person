# Mem0 请求超时策略

## 目标

Mem0 检索和记忆写入具有不同延迟特征：

- 搜索、健康检查和删除通常只访问 Mem0 与向量存储，应快速失败；
- `POST /memories` 在 `infer=true` 时还会等待记忆提取 LLM 和 embedding，因此需要更长的后台处理预算。

Java 客户端不再让两类操作共享同一个 30 秒超时。

## 配置

```bash
MEM0_CONNECT_TIMEOUT=2s
MEM0_REQUEST_TIMEOUT=30s
MEM0_RECORDING_TIMEOUT=120s
```

含义：

- `MEM0_CONNECT_TIMEOUT`：建立到 Mem0 服务连接的最长时间；
- `MEM0_REQUEST_TIMEOUT`：搜索、健康检查和删除的请求预算；
- `MEM0_RECORDING_TIMEOUT`：对话完成后异步调用 `POST /memories` 的独立预算。

`MEM0_RECORDING_TIMEOUT` 必须为正时长。未配置时默认使用 `120s`，不会继承较短的 `MEM0_REQUEST_TIMEOUT`。

## 为什么写入超时不自动重试

HTTP 客户端超时时，Mem0 可能已经接收并处理了 POST，只是响应尚未返回。此时自动重试可能重复创建或强化同一条记忆。因此客户端会：

1. 把错误归类为 `Mem0 recording timed out`；
2. 明确记录“完成状态未知”；
3. 不对该 POST 做盲重试；
4. 保持正常对话回复成功，因为记忆写入仍是回复后的异步流程。

后续若需要可靠补偿，应使用带幂等键的持久化 outbox，而不是在 HTTP 客户端内直接重发。

## 生产验证

安全查看运行时非敏感配置：

```bash
PID="$(sudo systemctl show person-ai -p MainPID --value)"

sudo sh -c "tr '\0' '\n' < /proc/${PID}/environ" \
  | grep -E '^(MEM0_ENABLED|MEM0_REQUEST_TIMEOUT|MEM0_RECORDING_TIMEOUT)='
```

检查最近写入日志：

```bash
sudo journalctl -u person-ai --since '15 minutes ago' --no-pager -o cat \
  | grep -A8 -B3 -Ei \
  'Dialogue memory recording completed|Mem0 recording timed out|Dialogue memory recording failed'
```

如果环境文件显式设置了旧的统一超时，保留 `MEM0_REQUEST_TIMEOUT=30s`，并增加：

```bash
MEM0_RECORDING_TIMEOUT=120s
```
